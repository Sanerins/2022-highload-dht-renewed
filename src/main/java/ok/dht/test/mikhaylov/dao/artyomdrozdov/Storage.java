package ok.dht.test.mikhaylov.dao.artyomdrozdov;

import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import ok.dht.test.mikhaylov.dao.BaseEntry;
import ok.dht.test.mikhaylov.dao.Config;
import ok.dht.test.mikhaylov.dao.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

final class Storage implements Closeable {

    static final int INDEX_HEADER_SIZE = Long.BYTES * 3;
    static final int INDEX_RECORD_SIZE = Long.BYTES;
    private static final Cleaner CLEANER = Cleaner.create(r -> {
                Thread t = new Thread(r, "Storage-Cleaner");
                t.setDaemon(true);
                return t;
            }
    );

    private static final long VERSION = 0;

    // supposed to have fresh files first

    private final ResourceScope scope;
    private final List<MemorySegment> sstables;
    private final boolean hasTombstones;

    static Storage load(Config config) throws IOException {
        Path basePath = config.basePath();
        Path compactedFile = basePath.resolve(FileUtilities.COMPACTED_FILE);
        if (Files.exists(compactedFile)) {
            FileUtilities.finishCompact(basePath, compactedFile);
        }

        ArrayList<MemorySegment> sstables = new ArrayList<>();
        ResourceScope scope = ResourceScope.newSharedScope(CLEANER);

        for (int i = 0; ; i++) {
            Path nextFile = FileUtilities.getFileName(basePath, i);
            if (!Files.exists(nextFile)) {
                break;
            }
            sstables.add(FileUtilities.mapForRead(scope, nextFile));
        }

        boolean hasTombstones = !sstables.isEmpty() && MemoryAccess.getLongAtOffset(sstables.get(0), 16) == 1;
        return new Storage(scope, sstables, hasTombstones);
    }

    // it is supposed that entries can not be changed externally during this method call
    static void save(
            Config config,
            Storage previousState,
            Collection<Entry<MemorySegment>> entries) throws IOException {
        int nextSSTableIndex = previousState.sstables.size();
        Path sstablePath = FileUtilities.getFileName(config.basePath(), nextSSTableIndex);
        save(entries::iterator, sstablePath);
    }

    private static void save(
            Data entries,
            Path sstablePath
    ) throws IOException {

        Path sstableTmpPath =
                sstablePath.resolveSibling(FileUtilities.getTmpFileName(sstablePath.getFileName().toString()));

        Files.deleteIfExists(sstableTmpPath);
        Files.createFile(sstableTmpPath);

        try (ResourceScope writeScope = ResourceScope.newConfinedScope()) {
            long size = 0;
            long entriesCount = 0;
            boolean hasTombstone = false;
            for (Entry<MemorySegment> entry : entries) {
                size += FileUtilities.getSize(entry);
                if (entry.isTombstone()) {
                    hasTombstone = true;
                }
                entriesCount++;
            }

            long dataStart = INDEX_HEADER_SIZE + INDEX_RECORD_SIZE * entriesCount;

            MemorySegment nextSSTable = MemorySegment.mapFile(
                            sstableTmpPath,
                            0,
                            dataStart + size,
                            FileChannel.MapMode.READ_WRITE,
                            writeScope
            );

            long index = 0;
            long offset = dataStart;
            for (Entry<MemorySegment> entry : entries) {
                MemoryAccess.setLongAtOffset(nextSSTable,
                        INDEX_HEADER_SIZE + index * INDEX_RECORD_SIZE, offset);

                offset += writeRecord(nextSSTable, offset, entry.key());
                offset += writeRecord(nextSSTable, offset, entry.value());

                index++;
            }

            MemoryAccess.setLongAtOffset(nextSSTable, 0, VERSION);
            MemoryAccess.setLongAtOffset(nextSSTable, 8, entriesCount);
            MemoryAccess.setLongAtOffset(nextSSTable, 16, hasTombstone ? 1 : 0);

            nextSSTable.force();
        }

        Files.move(sstableTmpPath, sstablePath, StandardCopyOption.ATOMIC_MOVE);
    }

    public static long getSizeOnDisk(Entry<MemorySegment> entry) {
        return FileUtilities.getSize(entry) + INDEX_RECORD_SIZE;
    }

    private static long writeRecord(MemorySegment nextSSTable, long offset, MemorySegment record) {
        if (record == null) {
            MemoryAccess.setLongAtOffset(nextSSTable, offset, -1);
            return Long.BYTES;
        }
        long recordSize = record.byteSize();
        MemoryAccess.setLongAtOffset(nextSSTable, offset, recordSize);
        nextSSTable.asSlice(offset + Long.BYTES, recordSize).copyFrom(record);
        return Long.BYTES + recordSize;
    }

    public static void compact(Config config, Data data) throws IOException {
        Path compactedFile = config.basePath().resolve(FileUtilities.COMPACTED_FILE);
        save(data, compactedFile);
        FileUtilities.finishCompact(config.basePath(), compactedFile);
    }

    private Storage(ResourceScope scope, List<MemorySegment> sstables, boolean hasTombstones) {
        this.scope = scope;
        this.sstables = sstables;
        this.hasTombstones = hasTombstones;
    }

    private long greaterOrEqualEntryIndex(MemorySegment sstable, MemorySegment key) {
        long index = entryIndex(sstable, key);
        if (index < 0) {
            return ~index;
        }
        return index;
    }

    // file structure:
    // (fileVersion)(entryCount)((entryPosition)...)|((keySize/key/valueSize/value)...)
    private long entryIndex(MemorySegment sstable, MemorySegment key) {
        long fileVersion = MemoryAccess.getLongAtOffset(sstable, 0);
        if (fileVersion != 0) {
            throw new IllegalStateException("Unknown file version: " + fileVersion);
        }
        long recordsCount = MemoryAccess.getLongAtOffset(sstable, 8);
        if (key == null) {
            throw new IllegalArgumentException("Key can not be null");
        }

        long left = 0;
        long right = recordsCount - 1;

        while (left <= right) {
            long mid = (left + right) >>> 1;

            long keyPos = MemoryAccess.getLongAtOffset(sstable,
                    INDEX_HEADER_SIZE + mid * INDEX_RECORD_SIZE);
            long keySize = MemoryAccess.getLongAtOffset(sstable, keyPos);

            MemorySegment keyForCheck = sstable.asSlice(keyPos + Long.BYTES, keySize);
            int comparedResult = MemorySegmentComparator.INSTANCE.compare(key, keyForCheck);
            if (comparedResult > 0) {
                left = mid + 1;
            } else if (comparedResult < 0) {
                right = mid - 1;
            } else {
                return mid;
            }
        }

        return ~left;
    }

    private Entry<MemorySegment> entryAt(MemorySegment sstable, long keyIndex) {
        try {
            long offset = MemoryAccess.getLongAtOffset(sstable,
                    INDEX_HEADER_SIZE + keyIndex * INDEX_RECORD_SIZE);
            long keySize = MemoryAccess.getLongAtOffset(sstable, offset);
            long valueOffset = offset + Long.BYTES + keySize;
            long valueSize = MemoryAccess.getLongAtOffset(sstable, valueOffset);
            return new BaseEntry<>(
                    sstable.asSlice(offset + Long.BYTES, keySize),
                    valueSize == -1 ? null : sstable.asSlice(valueOffset + Long.BYTES, valueSize)
            );
        } catch (IllegalStateException e) {
            throw checkForClose(e);
        }
    }

    public Entry<MemorySegment> get(MemorySegment key) {
        try {
            for (int i = sstables.size() - 1; i >= 0; i--) {
                MemorySegment sstable = sstables.get(i);
                long keyFromPos = entryIndex(sstable, key);
                if (keyFromPos >= 0) {
                    return entryAt(sstable, keyFromPos);
                }
            }
            return null;
        } catch (IllegalStateException e) {
            throw checkForClose(e);
        }
    }

    private Iterator<Entry<MemorySegment>> iterate(MemorySegment sstable, MemorySegment keyFrom, MemorySegment keyTo) {
        long keyFromPos = greaterOrEqualEntryIndex(sstable, keyFrom);
        long keyToPos = greaterOrEqualEntryIndex(sstable, keyTo);

        return new Iterator<>() {
            long pos = keyFromPos;

            @Override
            public boolean hasNext() {
                return pos < keyToPos;
            }

            @Override
            public Entry<MemorySegment> next() {
                Entry<MemorySegment> entry = entryAt(sstable, pos);
                pos++;
                return entry;
            }
        };
    }

    // last is newer
    // it is ok to mutate list after
    public List<Iterator<Entry<MemorySegment>>> iterate(MemorySegment keyFrom, MemorySegment keyTo) {
        try {
            List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>(sstables.size());
            for (MemorySegment sstable : sstables) {
                iterators.add(iterate(sstable, keyFrom, keyTo));
            }
            return iterators;
        } catch (IllegalStateException e) {
            throw checkForClose(e);
        }
    }

    private RuntimeException checkForClose(IllegalStateException e) {
        return isClosed() ? new StorageClosedException(e) : e;
    }

    @Override
    public void close() throws IOException {
        while (scope.isAlive()) {
            try {
                scope.close();
                return;
            } catch (IllegalStateException ignored) {
                // ignored
            }
        }
    }

    public boolean isClosed() {
        return !scope.isAlive();
    }

    public boolean isCompacted() {
        if (sstables.isEmpty()) {
            return true;
        }
        if (sstables.size() > 1) {
            return false;
        }
        return !hasTombstones;
    }

}
