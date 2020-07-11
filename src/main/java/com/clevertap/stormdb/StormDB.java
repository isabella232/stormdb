package com.clevertap.stormdb;

import com.clevertap.stormdb.exceptions.InconsistentDataException;
import com.clevertap.stormdb.exceptions.ReservedKeyException;
import com.clevertap.stormdb.exceptions.StormDBException;
import com.clevertap.stormdb.utils.ByteUtil;
import com.clevertap.stormdb.utils.RecordUtil;
import gnu.trove.map.hash.TIntIntHashMap;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * Protocol: key (4 bytes) | value (fixed bytes).
 * <p>
 * Notes:
 * <ul>
 *     <li>0xffffffff is reserved for internal structures (used as the sync marker)</li>
 * </ul>
 * <p>
 */
public class StormDB {

    public static final int RECORDS_PER_BLOCK = 128;
    public static final int CRC_SIZE = 4; // CRC32.
    private static final long COMPACTION_WAIT_TIMEOUT_MS = 1000 * 10 * 60; // 10 minutes

    protected static final int RESERVED_KEY_MARKER = 0xffffffff;
    private static final int NO_MAPPING_FOUND = 0xffffffff;

    protected static final int KEY_SIZE = 4;

    private static final String FILE_NAME_DATA = "data";
    private static final String FILE_NAME_WAL = "wal";
    private static final String FILE_TYPE_NEXT = ".next";
    private static final String FILE_TYPE_DELETE = ".del";

    protected static final int FOUR_MB = 4 * 1024 * 1024;

    /**
     * Key: The actual key within this KV store.
     * <p>
     * Value: The offset (either in the data file, or in the WAL file)
     * <p>
     * Note: Negative offset indicates an offset in the
     */
    // TODO: 03/07/2020 change this map to one that is array based (saves 1/2 the size)
    private final TIntIntHashMap index = new TIntIntHashMap(10_000_000, 0.95f, Integer.MAX_VALUE,
            NO_MAPPING_FOUND);
    // TODO: 08/07/20 Revisit bitset memory optimization later.
    private BitSet dataInWalFile = new BitSet();
    private BitSet dataInNextFile; // TODO: 09/07/20 We can get rid of this bitset. revisit.
    private BitSet dataInNextWalFile;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private final ThreadLocal<RandomAccessFileWrapper> walReader = new ThreadLocal<>();
    private final ThreadLocal<RandomAccessFileWrapper> walNextReader = new ThreadLocal<>();
    private final ThreadLocal<RandomAccessFileWrapper> dataReader = new ThreadLocal<>();
    private final ThreadLocal<RandomAccessFileWrapper> dataNextReader = new ThreadLocal<>();


    /**
     * Align to the nearest 4096 block, based on the size of the value. This improves sequential
     * reads by reading data in bulk. Based on previous performance tests, reading just {@link
     * #valueSize} bytes at a time is slower.
     */
    private final int blockSize;

    private final WriteBuffer writeBuffer;

    private final int valueSize;
    private final int recordSize;

    private long bytesInWalFile = -1; // Will be initialised on the first write.
    private final File dbDirFile;

    private File dataFile;
    private File walFile;
    private File nextWalFile;
    private File nextDataFile;

    private DataOutputStream walOut;

    private Thread tCompaction;
    private final Object compactionSync = new Object();
    private final Object compactionLock = new Object();
    private boolean shutDown = false;

    private static final Logger logger = Logger.getLogger("StormDB");

    public StormDB(final int valueSize, final String dbDir) throws IOException, StormDBException {
        this.valueSize = valueSize;
        dbDirFile = new File(dbDir);
        //noinspection ResultOfMethodCallIgnored
        dbDirFile.mkdirs();

        recordSize = valueSize + KEY_SIZE;

        blockSize = (4096 / recordSize) * recordSize;
        writeBuffer = new WriteBuffer(valueSize);

        dataFile = new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_DATA);
        walFile = new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_WAL);

        // Open DB.
        final File metaFile = new File(dbDir + "/meta");
        if (metaFile.exists()) {
            // Ensure that the valueSize has not changed.
            final byte[] bytes = Files.readAllBytes(metaFile.toPath());
            final ByteBuffer meta = ByteBuffer.wrap(bytes);
            final int valueSizeFromMeta = meta.getInt();
            if (valueSizeFromMeta != valueSize) {
                throw new IOException("The path " + dbDir
                        + " contains a StormDB database with the value size "
                        + valueSizeFromMeta + " bytes. "
                        + "However, " + valueSize + " bytes was provided!");
            }

            // Now do compaction if we stopped just while doing compaction.
            if (new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_DATA +
                    FILE_TYPE_NEXT).exists()) {
                // Recover implicitly builds index.
                recover();
            }
            buildIndex(false);
            buildIndex(true);
        } else {
            // New database. Write value size to the meta.
            final ByteBuffer out = ByteBuffer.allocate(4);
            out.putInt(valueSize);
            Files.write(metaFile.toPath(), out.array());
        }

        walOut = new DataOutputStream(new FileOutputStream(walFile, true));
        bytesInWalFile = walFile.length();

        if (walFile.length() % recordSize != 0) {
            // Corrupted WAL - somebody should run compact!
            throw new IOException("WAL file corrupted! Compact DB before writing again!");
        }

        // TODO: 10/07/20 Revisit this bit
//        tCompaction = new Thread(() -> {
//            while (!shutDown) {
//                try {
//                    compactionSync.wait(COMPACTION_WAIT_TIMEOUT_MS);
//                    if(shouldCompact()) {
//                        compact();
//                    }
//                } catch (InterruptedException | IOException e) {
//                    e.printStackTrace();
//                }
//            }
//        });
//        tCompaction.start();
    }

    private boolean shouldCompact() {
        // TODO: 09/07/20 Decide and add criteria for compaction here.
        return true;
    }

    private void recover() throws IOException {
        // This scenario can happen only at start.

        // 1. Simply append wal.next to wal file and compact
        File nextWalFile = new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_DATA +
                FILE_TYPE_NEXT);
        File currentWalFile = new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_DATA);

        FileInputStream inStream = new FileInputStream(nextWalFile);
        FileOutputStream outStream = new FileOutputStream(currentWalFile, true);

        byte[] buffer = new byte[FOUR_MB];
        int length;
        while ((length = inStream.read(buffer)) > 0) {
            outStream.write(buffer, 0, length);
        }

        inStream.close();
        outStream.close();

        // 2. For cleanliness sake, delete .next files.
        if (!nextWalFile.delete()) {
            // TODO: 09/07/20 log error
        }
        File nextDataFile = new File(dbDirFile.getAbsolutePath() + "/" +
                FILE_NAME_DATA + FILE_TYPE_NEXT);
        if (nextDataFile.exists()) {
            if (!nextDataFile.delete()) {
                // TODO: 09/07/20 log error
            }
        }

        // 3. Now finally call the compact procedure
        compact();
    }

    public void compact() throws IOException {
        synchronized (compactionLock) {
            File prevDataFile = dataFile;
            // 1. Move wal to wal.prev and create new wal file.
            try {
                rwLock.writeLock().lock();

                // First flush all data.
                // This is because we will be resetting writeOffsetWal below and we need to get all
                // buffer to file so that their offsets are honoured.
                flush();

                // TODO: 10/07/2020 revise message
                logger.info("Starting compaction. CurrentWriteWalOffset = " + bytesInWalFile);
                // Check whether there was any data coming in. If not simply bail out.
                if (bytesInWalFile == 0) {
                    return;
                }

                // Now create wal bitset for next file
                dataInNextFile = new BitSet();
                dataInNextWalFile = new BitSet();

                nextWalFile = new File(dbDirFile.getAbsolutePath() + "/" +
                        FILE_NAME_WAL + FILE_TYPE_NEXT);

                // Create new walOut File
                walOut = new DataOutputStream(new FileOutputStream(nextWalFile));
                bytesInWalFile = 0;

                // TODO: 08/07/20 Remember to invalidate/refresh file handles in thread local

            } finally {
                rwLock.writeLock().unlock();
            }

            // 2. Process wal.current file and out to data.next file.
            // 3. Process data.current file.
            nextDataFile = new File(dbDirFile.getAbsolutePath() + "/" +
                    FILE_NAME_DATA + FILE_TYPE_NEXT);
            DataOutputStream nextDataOut = new DataOutputStream(
                    new FileOutputStream(nextDataFile));
            ByteBuffer nextWriteBuffer = ByteBuffer.allocate((FOUR_MB / recordSize) * recordSize);
            final int[] nextFileOffset = {0};
            iterate(false, false, (key, data, offset) -> {
                nextWriteBuffer.putInt(key);
                nextWriteBuffer.put(data, offset, valueSize);

                // Optimize batched writes and updates to structures for lesser contention
                if (nextWriteBuffer.remaining() == 0) {
                    nextFileOffset[0] = flushNext(nextDataOut, nextWriteBuffer, nextFileOffset[0]);
                }
            });
            if (nextWriteBuffer.position() != 0) {
                nextFileOffset[0] = flushNext(nextDataOut, nextWriteBuffer, nextFileOffset[0]);
            }
            nextDataOut.close();

            File walFileToDelete = new File(dbDirFile.getAbsolutePath() + "/" +
                    FILE_NAME_WAL + FILE_TYPE_DELETE);
            File dataFileToDelete = new File(dbDirFile.getAbsolutePath() + "/" +
                    FILE_NAME_DATA + FILE_TYPE_DELETE);

            try {
                rwLock.writeLock().lock();

                // First rename prevWalFile and prevDataFile so that .next can be renamed
                walFile.renameTo(walFileToDelete);
                dataFile.renameTo(dataFileToDelete);

                // Now make bitsets point right.
                dataInWalFile = dataInNextWalFile;
                dataInNextFile = null;
                dataInNextWalFile = null;

                // Create new file references for Thread locals to be aware of.
                // Rename *.next to *.current
                walFile = new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_WAL);
                nextWalFile.renameTo(walFile);
                nextWalFile = null;
                dataFile = new File(dbDirFile.getAbsolutePath() + "/" + FILE_NAME_DATA);
                nextDataFile.renameTo(dataFile);
                nextDataFile = null;

            } finally {
                rwLock.writeLock().unlock();
            }

            // 4. Delete old data and wal
            if (walFileToDelete.exists()) {
                if (!walFileToDelete.delete()) {
                    // TODO: 09/07/20 log error
                    logger.warning("Unable to delete file - " + walFileToDelete.getName());
                }
            }
            if (dataFileToDelete.exists()) {
                if (!dataFileToDelete.delete()) {
                    // TODO: 09/07/20 log error
                    logger.warning("Unable to delete file - " + dataFileToDelete.getName());
                }
            }
            logger.info("Finished compaction.");
        }
    }

    private int flushNext(DataOutputStream nextDataOut, ByteBuffer nextWriteBuffer,
            int nextFileOffset) throws IOException {
        nextDataOut.write(nextWriteBuffer.array(), 0, nextWriteBuffer.position());
        nextDataOut.flush();

        try {
            rwLock.writeLock().lock();
            for (int i = 0; i < nextWriteBuffer.position(); i += recordSize) {
                index.put(nextWriteBuffer.getInt(i), nextFileOffset++);
                dataInNextFile.set(nextWriteBuffer.getInt(i));
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        nextWriteBuffer.clear();

        return nextFileOffset;
    }

    public void put(final byte[] key, final byte[] value, final int valueOffset)
            throws IOException {
        put(ByteUtil.toInt(key, 0), value, valueOffset);
    }

    public void put(final byte[] key, final byte[] value) throws IOException {
        put(ByteUtil.toInt(key, 0), value);
    }

    public void put(int key, byte[] value) throws IOException {
        put(key, value, 0);
    }

    public void put(int key, byte[] value, int valueOffset) throws IOException {
        if (key == RESERVED_KEY_MARKER) {
            throw new ReservedKeyException(RESERVED_KEY_MARKER);
        }
        rwLock.writeLock().lock();

        try {
            // TODO: 07/07/2020 Optimisation: if the current key is in the write buffer,
            // TODO: 07/07/2020 don't append, but perform an inplace update

            if (writeBuffer.isFull()) {
                flush();
            }

            // Write to the write buffer.
            final int addressInBuffer = writeBuffer.add(key, value, valueOffset);

            final int address = RecordUtil.addressToIndex(recordSize,
                    bytesInWalFile + addressInBuffer, true);
            index.put(key, address);

            if (dataInNextWalFile != null) {
                dataInNextWalFile.set(key);
            } else {
                dataInWalFile.set(key);
            }

        } finally {
            rwLock.writeLock().unlock();
        }
    }


    private void flush() throws IOException {
        rwLock.writeLock().lock();
        try {
            // walOut is initialised on the first write to the writeBuffer.
            if (walOut == null || !writeBuffer.isDirty()) {
                return;
            }

            bytesInWalFile += writeBuffer.flush(walOut);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void iterate(final EntryConsumer consumer) throws IOException {
        iterate(true, true, consumer);
    }

    private void iterate(final boolean useLatestWalFile, final boolean readInMemoryBuffer,
            final EntryConsumer consumer) throws IOException {
        List<RandomAccessFile> files = new ArrayList<>(3);
        byte[] inMemoryKeyValues;
        rwLock.readLock().lock();
        try {
            if (nextWalFile != null) {
                RandomAccessFile reader = getReadRandomAccessFile(walNextReader, nextWalFile);
                reader.seek(reader.length());
                files.add(reader);
            }

            // TODO: 05/07/2020 Keep a mem buffer since a block needs to be flushed fully for backwards iteration.
            // TODO: 05/07/2020 consider partial writes and alignment
            // TODO: 05/07/2020 best to ensure that the last 4 bytes are the sync bytes
            if (walFile.exists()) {
                RandomAccessFile reader = getReadRandomAccessFile(walReader, walFile);
                reader.seek(walFile.length());
                files.add(reader);
            }

            if (dataFile.exists()) {
                RandomAccessFile reader = getReadRandomAccessFile(walReader, dataFile);
                reader.seek(dataFile.length());
                files.add(reader);
            }

            inMemoryKeyValues = writeBuffer.array().clone();
        } finally {
            rwLock.readLock().unlock();
        }

        // Always 4 MB, regardless of the value of this.blockSize. Since RandomAccessFile cannot
        // be buffered, we must make a large get request to the underlying native calls.
        final int blockSize = (FOUR_MB / recordSize) * recordSize;

        final ByteBuffer buf = ByteBuffer.allocate(blockSize);

        final BitSet keysRead = new BitSet(index.size());

        // TODO: 09/07/20 Read from inMemoryKeyValues

        // TODO: 09/07/20 Handle incomplete writes to disk while iteration. Needed esp. while recovery.
        for (RandomAccessFile file : files) {
            while (file.getFilePointer() != 0) {
                buf.clear();

                final long validBytesRemaining = file.getFilePointer() - blockSize;
                file.seek(Math.max(validBytesRemaining, 0));

                final int bytesRead = file.read(buf.array());

                // Set the position again, since the read op moved the cursor back ahead.
                file.seek(Math.max(validBytesRemaining, 0));

                // Note: There's the possibility that we'll read the head of the file twice,
                // but that's okay, since we iterate in a backwards fashion.
                buf.limit(bytesRead);
                // TODO: 05/07/2020 assert that this is in perfect alignment of 1 KV pair
                buf.position(buf.limit());

                while (buf.position() != 0) {
                    buf.position(buf.position() - recordSize);
                    final int key = buf.getInt();
                    // TODO: 08/07/2020 if we need to support the whole range of 4 billion keys, we should use a long as the bitset is +ve
                    final boolean b = keysRead.get(key);
                    if (!b) {
                        consumer.accept(key, buf.array(), buf.position());
                        keysRead.set(key);
                    }

                    // Do this again, since we read the buffer backwards too.
                    // -4 because we read the key only.
                    buf.position(buf.position() - KEY_SIZE);
                }
            }
        }
    }

    public byte[] randomGet(final int key) throws IOException, StormDBException {
        int recordIndex;
        RandomAccessFile f;
        byte[] value;
        rwLock.readLock().lock();
        final long address;
        try {
            recordIndex = index.get(key);
            if (recordIndex == NO_MAPPING_FOUND) { // No mapping value.
                return null;
            }

            value = new byte[valueSize];
            if ((dataInNextWalFile != null && dataInNextWalFile.get(key))
                    || dataInWalFile.get(key)) {
                address = RecordUtil.indexToAddress(recordSize, recordIndex, true);
                if (address >= bytesInWalFile) {
                    System.arraycopy(writeBuffer.array(), (int) (address - bytesInWalFile),
                            value, 0, valueSize);
                    return value;
                }
            } else {
                address = RecordUtil.indexToAddress(recordSize, recordIndex, false);
            }

            if (dataInNextWalFile != null && dataInNextWalFile.get(key)) {
                f = getReadRandomAccessFile(walNextReader, nextWalFile);
            } else if (dataInNextFile != null && dataInNextFile.get(key)) {
                f = getReadRandomAccessFile(dataNextReader, nextDataFile);
            } else if (dataInWalFile.get(key)) {
                f = getReadRandomAccessFile(walReader, walFile);
            } else {
                f = getReadRandomAccessFile(dataReader, dataFile);
            }
        } finally {
            rwLock.readLock().unlock();
        }

        f.seek(address);
        if (f.readInt() != key) {
            throw new InconsistentDataException();
        }
        final int bytesRead = f.read(value);
        if (bytesRead != valueSize) {
            // TODO: 03/07/2020 perhaps it's more appropriate to return null (record lost)
            throw new StormDBException("Possible data corruption detected!");
        }
        return value;
    }

    private static RandomAccessFileWrapper getReadRandomAccessFile(
            ThreadLocal<RandomAccessFileWrapper> reader,
            File file) throws FileNotFoundException {
        RandomAccessFileWrapper f = reader.get();
        if (f == null || !f.isSameFile(file)) {
            f = new RandomAccessFileWrapper(file, "r");
            reader.set(f);
        }
        return f;
    }

    /**
     * Builds a key to record index by reading the following:
     * <p>
     * 1. Data file (as a result of the last compaction)
     * <p>
     * 2. WAL file (contains the most recently written records)
     * <p>
     * Both files are iterated over sequentially.
     */
    private void buildIndex(final boolean walContext) throws IOException {
        final File dataFile = walContext ? this.walFile : this.dataFile;
        if (!dataFile.exists()) {
            return;
        }
        final BufferedInputStream bufIn = new BufferedInputStream(new FileInputStream(dataFile));
        final DataInputStream in = new DataInputStream(bufIn);

        final ByteBuffer buf = ByteBuffer.allocate(blockSize);

        int recordIndex = 0;

        while (true) {
            buf.clear();

            final int limit = in.read(buf.array());
            if (limit == -1) {
                break;
            }
            buf.limit(limit);

            while (buf.remaining() >= recordSize) {
                // TODO: 07/07/2020 assert alignment
                // TODO: 10/07/2020 verify crc32 checksum
                // TODO: 10/07/2020 verify sync marker
                // TODO: 10/07/2020 support auto recovery
                final int key = buf.getInt();
                buf.position(buf.position() + valueSize);
                index.put(key, recordIndex);
                if (walContext) {
                    dataInWalFile.set(key);
                }

                recordIndex++;
            }
        }
    }

    public void close() throws IOException, InterruptedException {
        flush();
        shutDown = true;
        compactionSync.notify();
        tCompaction.join();
    }
}
