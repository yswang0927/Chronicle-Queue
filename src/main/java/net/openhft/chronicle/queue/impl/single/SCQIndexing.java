
package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.Byteable;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.ref.BinaryLongReference;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.values.LongArrayValues;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.StreamCorruptedException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static java.lang.ThreadLocal.withInitial;
import static net.openhft.chronicle.wire.Wires.NOT_INITIALIZED;

/**
 * Created by peter on 22/05/16.
 */
class SCQIndexing implements Demarshallable, WriteMarshallable, Closeable {
    private final int indexCount, indexCountBits;
    private final int indexSpacing, indexSpacingBits;
    private final LongValue index2Index;
    private final LongValue nextEntryToBeIndexed;
    private final ThreadLocal<LongArrayValuesHolder> index2indexArray;
    private final ThreadLocal<LongArrayValuesHolder> indexArray;
    private final WriteMarshallable index2IndexTemplate;
    private final WriteMarshallable indexTemplate;
    LongValue writePosition;

    /**
     * used by {@link Demarshallable}
     *
     * @param wire a wire
     */
    @UsedViaReflection
    private SCQIndexing(@NotNull WireIn wire) {
        this(wire.read(IndexingFields.indexCount).int32(),
                wire.read(IndexingFields.indexSpacing).int32(),
                wire.read(IndexingFields.index2Index).int64ForBinding(wire.newLongReference()),
                wire.read(IndexingFields.lastIndex).int64ForBinding(wire.newLongReference()),
                wire::newLongArrayReference);
    }

    SCQIndexing(@NotNull WireType wireType, int indexCount, int indexSpacing) {
        this(indexCount, indexSpacing, wireType.newLongReference().get(), wireType.newLongReference().get(), wireType.newLongArrayReference());
    }

    public SCQIndexing(int indexCount, int indexSpacing, LongValue index2Index, LongValue nextEntryToBeIndexed, Supplier<LongArrayValues> longArraySupplier) {
        this.indexCount = indexCount;
        this.indexCountBits = Maths.intLog2(indexCount);
        this.indexSpacing = indexSpacing;
        this.indexSpacingBits = Maths.intLog2(indexSpacing);
        this.index2Index = index2Index;
        this.nextEntryToBeIndexed = nextEntryToBeIndexed;
        this.index2indexArray = withInitial(() -> new LongArrayValuesHolder(longArraySupplier.get()));
        this.indexArray = withInitial(() -> new LongArrayValuesHolder(longArraySupplier.get()));
        this.index2IndexTemplate = w -> w.writeEventName(() -> "index2index").int64array(indexCount);
        this.indexTemplate = w -> w.writeEventName(() -> "index").int64array(indexCount);
    }

    public long toAddress0(long index) {
        long siftedIndex = index >> (indexSpacingBits + indexCountBits);
        long mask = indexCount - 1L;
        long maskedShiftedIndex = mask & siftedIndex;
        // convert to an offset
        return maskedShiftedIndex;
    }

    long toAddress1(long index) {
        long siftedIndex = index >> indexSpacingBits;
        long mask = indexCount - 1L;
        // convert to an offset
        return mask & siftedIndex;
    }

    @Override
    public void close() {
    }

    @Override
    public void writeMarshallable(@NotNull WireOut wire) {
        wire.write(IndexingFields.indexCount).int64(indexCount)
                .write(IndexingFields.indexSpacing).int64(indexSpacing)
                .write(IndexingFields.index2Index).int64forBinding(0L, index2Index)
                .write(IndexingFields.lastIndex).int64forBinding(0L, nextEntryToBeIndexed);
    }

    /**
     * atomically gets or creates the address of the first index the index is create and another
     * except into the queue, however this except is treated as meta data and does not increment the
     * last index, in otherword it is not possible to access this except by calling index(), it
     * effectively invisible to the end-user
     *
     * @param recovery
     * @param wire     the current wire
     * @return the position of the index
     */
    long indexToIndex(StoreRecovery recovery, @NotNull final Wire wire, long timeoutMS) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException {
        long index2Index = this.index2Index.getVolatileValue();
        return index2Index > 0 ? index2Index : acquireIndex2Index(recovery, wire, timeoutMS);
    }

    long acquireIndex2Index(StoreRecovery recovery, Wire wire, long timeoutMS) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException {
        try {
            return acquireIndex2Index0(recovery, wire, timeoutMS);
        } catch (TimeoutException fallback) {
            return recovery.recoverIndex2Index(this.index2Index, () -> acquireIndex2Index0(recovery, wire, timeoutMS), timeoutMS);
        }
    }

    long acquireIndex2Index0(StoreRecovery recovery, Wire wire, long timeoutMS) throws EOFException, TimeoutException, UnrecoverableTimeoutException, StreamCorruptedException {
        long start = System.currentTimeMillis();
        try {
            do {
                long index2Index = this.index2Index.getVolatileValue();

                if (index2Index == BinaryLongReference.LONG_NOT_COMPLETE) {
                    wire.pauser().pause(timeoutMS, TimeUnit.MILLISECONDS);
                    continue;
                }

                if (index2Index != NOT_INITIALIZED)
                    return index2Index;

                if (!this.index2Index.compareAndSwapValue(NOT_INITIALIZED, BinaryLongReference.LONG_NOT_COMPLETE))
                    continue;
                long index = NOT_INITIALIZED;
                try {
                    index = newIndex(recovery, wire, true, timeoutMS);
                } finally {
                    this.index2Index.setOrderedValue(index);
                }
                return index;
            } while (System.currentTimeMillis() < start + timeoutMS);
        } finally {
            wire.pauser().reset();
        }
        throw new TimeoutException("index2index NOT_COMPLETE for too long.");
    }

    @NotNull
    private LongArrayValues arrayForAddress(@NotNull Wire wire, long secondaryAddress) {
        LongArrayValuesHolder holder = indexArray.get();
        if (holder.address == secondaryAddress)
            return holder.values;
        holder.address = secondaryAddress;
        wire.bytes().readPositionRemaining(secondaryAddress, 256 << 20);
        wire.readMetaDataHeader();
        return array(wire, holder.values, false);
    }

    @NotNull
    private LongArrayValues array(@NotNull WireIn w, @NotNull LongArrayValues using, boolean index2index) {
        final StringBuilder sb = Wires.acquireStringBuilder();
        @NotNull final ValueIn valueIn = w.readEventName(sb);
        String name = index2index ? "index2index" : "index";
        if (!name.contentEquals(sb))
            throw new IllegalStateException("expecting index, was " + sb);

        valueIn.int64array(using, this, (o1, o2) -> {
        });
        return using;
    }

    /**
     * Creates a new Excerpt containing and index which will be 1L << 17L bytes long, This method is
     * used for creating both the primary and secondary indexes. Chronicle Queue uses a root primary
     * index ( each entry in the primary index points to a unique a secondary index. The secondary
     * index only records the address of every 64th except, the except are linearly scanned from
     * there on.  )
     *
     * @param wire the current wire
     * @return the address of the Excerpt containing the usable index, just after the header
     */
    long newIndex(StoreRecovery recovery, @NotNull Wire wire, boolean index2index, long timeoutMS) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException {
        long writePosition = this.writePosition.getValue();
        wire.bytes().writePosition(writePosition);

        long position = recovery.writeHeader(wire, Wires.UNKNOWN_LENGTH, timeoutMS,
                this.writePosition);
        WriteMarshallable writer = index2index ? index2IndexTemplate : indexTemplate;
        writer.writeMarshallable(wire);
        wire.updateHeader(position, true);

        this.writePosition.setMaxValue(wire.bytes().writePosition());
        return position;
    }

    long newIndex(StoreRecovery recovery, Wire wire, LongArrayValues index2Index, long index2, long timeoutMS) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException, TimeoutException {
        try {
            if (index2Index.compareAndSet(index2, NOT_INITIALIZED, BinaryLongReference.LONG_NOT_COMPLETE)) {
                long pos = newIndex(recovery, wire, false, timeoutMS);
                if (pos < 0)
                    throw new IllegalStateException("pos: " + pos);
                if (index2Index.compareAndSet(index2, BinaryLongReference.LONG_NOT_COMPLETE, pos)) {
                    index2Index.setMaxUsed(index2 + 1);
                    return pos;
                }
                throw new IllegalStateException("Index " + index2 + " in index2index was altered");
            }
            for (; ; ) {
                long pos = index2Index.getVolatileValueAt(index2);
                if (pos == BinaryLongReference.LONG_NOT_COMPLETE) {
                    wire.pauser().pause(timeoutMS, TimeUnit.MILLISECONDS);
                } else {
                    wire.pauser().reset();
                    return pos;
                }
            }
        } catch (Exception e) {
            // reset the index as failed to add it.
            index2Index.compareAndSet(index2, BinaryLongReference.LONG_NOT_COMPLETE, NOT_INITIALIZED);
            throw e;
        }
    }

    /**
     * Moves the position to the {@code index} <p> The indexes are stored in many excerpts, so the
     * index2index tells chronicle where ( in other words the address of where ) the root first
     * level targetIndex is stored. The indexing works like a tree, but only 2 levels deep, the root
     * of the tree is at index2index ( this first level targetIndex is 1MB in size and there is only
     * one of them, it only holds the addresses of the second level indexes, there will be many
     * second level indexes ( created on demand ), each is about 1MB in size  (this second level
     * targetIndex only stores the position of every 64th excerpt), so from every 64th excerpt a
     * linear scan occurs.
     *
     * @param recovery
     * @param wire     the data structure we are navigating
     * @param index    the index we wish to move to
     * @return the position of the {@code targetIndex} or -1 if the index can not be found
     */
    ScanResult moveToIndex(StoreRecovery recovery, @NotNull final Wire wire, final long index, long timeoutMS) throws UnrecoverableTimeoutException, StreamCorruptedException {
        try {
            ScanResult scanResult = moveToIndex0(recovery, wire, index, timeoutMS);
            if (scanResult != null)
                return scanResult;
        } catch (EOFException fallback) {
            // scan from the start.
        }
        return moveToIndexFromTheStart(wire, index);
    }

    private ScanResult moveToIndexFromTheStart(@NotNull Wire wire, long index) {
        try {
            wire.bytes().readPosition(0);
            if (wire.readDataHeader())
                return linearScan(wire, index, 0, wire.bytes().readPosition());
        } catch (EOFException fallback) {
        }
        return ScanResult.NOT_FOUND;
    }

    ScanResult moveToIndex0(StoreRecovery recovery, @NotNull final Wire wire, final long index, long timeoutMS) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException {

        LongArrayValues index2index = getIndex2index(recovery, wire, timeoutMS);

        @NotNull final Bytes<?> bytes = wire.bytes();
        bytes.writeLimit(bytes.capacity()).readLimit(bytes.capacity());

        long primaryOffset = toAddress0(index);

        long secondaryAddress = 0;
        long startIndex = index & ~(indexSpacing - 1);
        while (primaryOffset >= 0) {
            secondaryAddress = index2index.getValueAt(primaryOffset);
            if (secondaryAddress == 0) {
                startIndex -= indexCount * indexSpacing;
                primaryOffset--;
            } else {
                break;
            }
        }

        if (secondaryAddress <= 0) {
            return null;
        }
        @NotNull final LongArrayValues array1 = arrayForAddress(wire, secondaryAddress);
        long secondaryOffset = toAddress1(index);

        do {
            long fromAddress = array1.getValueAt(secondaryOffset);
            if (fromAddress == 0) {
                secondaryOffset--;
                startIndex -= indexSpacing;
                continue;
            }

            if (index == startIndex) {
                bytes.readLimit(bytes.capacity()).readPosition(fromAddress);
                return ScanResult.FOUND;
            } else {
                return linearScan(wire, index, startIndex, fromAddress);
            }

        } while (secondaryOffset >= 0);
        return null; // no index,
    }

    /**
     * moves the context to the index of {@code toIndex} by doing a linear scans form a {@code
     * fromKnownIndex} at  {@code knownAddress} <p/> note meta data is skipped and does not count to
     * the indexes
     *
     * @param wire           if successful, moves the context to an address relating to the index
     *                       {@code toIndex }
     * @param toIndex        the index that we wish to move the context to
     * @param fromKnownIndex a know index ( used as a starting point )
     * @param knownAddress   a know address ( used as a starting point )
     * @see SCQIndexing#moveToIndex
     */

    private ScanResult linearScan(@NotNull final Wire wire,
                                  final long toIndex,
                                  final long fromKnownIndex,
                                  final long knownAddress) {
        @NotNull
        final Bytes<?> bytes = wire.bytes();

        long end = writePosition.getValue();
        bytes.readLimit(bytes.capacity()).readPosition(knownAddress);

        for (long i = fromKnownIndex; ; i++) {
            try {
                if (wire.readDataHeader()) {
                    if (i == toIndex)
                        return ScanResult.FOUND;
                    if (bytes.readPosition() > end)
                        return ScanResult.NOT_REACHED;
                    int header = bytes.readInt();
                    if (Wires.isNotComplete(header))
                        return ScanResult.NOT_REACHED;
                    bytes.readSkip(Wires.lengthOf(header));
                    continue;
                }
            } catch (EOFException fallback) {
                // reached the end of the file.
            }
            return i == toIndex ? ScanResult.NOT_FOUND : ScanResult.NOT_REACHED;
        }
    }

    long linearScanByPosition(@NotNull final Wire wire,
                              final long toPosition,
                              final long indexOfNext,
                              final long startAddress) throws EOFException {
        assert toPosition >= 0;
        Bytes<?> bytes = wire.bytes();
        long rl = bytes.readLimit();
        long wl = bytes.writeLimit();
        long capacity = bytes.capacity();
        bytes.writeLimit(capacity)
                .readLimit(capacity);

        try {
            bytes.readPosition(startAddress);
            long i = indexOfNext - 1;
            while (bytes.readPosition() <= toPosition) {
                WireIn.HeaderType headerType = wire.readDataHeader(true);

                switch (headerType) {
                    case NONE:
                        if (toPosition == Long.MAX_VALUE)
                            return i + 1;
                        long pos = bytes.readPosition();
                        if (toPosition == pos)
                            return i;
                        throw new EOFException("toPosition=" + toPosition + ",pos=" + pos);
                    case META_DATA:
                        break;
                    case DATA:
                        ++i;
                        break;
                }

                if (bytes.readPosition() == toPosition)
                    return i;

                int header = bytes.readVolatileInt();
                int len = Wires.lengthOf(header);
                assert Wires.isReady(header);
                bytes.readSkip(len);
            }


            throw new IllegalArgumentException("position not the start of a message, bytes" +
                    ".readPosition()=" + bytes.readPosition() + ",toPosition=" + toPosition);
        } finally {
            bytes.writeLimit(wl).readLimit(rl);
        }
    }

    LongArrayValues getIndex2index(StoreRecovery recovery, Wire wire, long timeoutMS) throws
            EOFException, UnrecoverableTimeoutException, StreamCorruptedException {

        LongArrayValuesHolder holder = index2indexArray.get();
        LongArrayValues values = holder.values;
        if (((Byteable) values).bytesStore() != null || timeoutMS == 0)
            return values;
        final long indexToIndex0 = indexToIndex(recovery, wire, timeoutMS);

        Bytes<?> bytes = wire.bytes();
        long rl = bytes.readLimit();
        long wl = bytes.writeLimit();
        try {
            long capacity = bytes.capacity();
            bytes.writeLimit(capacity).readLimit(capacity);
            for (; ; ) {
                try (DocumentContext context = wire.readingDocument(indexToIndex0)) {
                    if (!context.isPresent() || !context.isMetaData()) {
                        wire.pauser().pause();
                        continue;
                    }

                    return array(wire, values, true);
                }
            }
        } finally {
            bytes.writeLimit(wl).readLimit(rl);
        }
    }

    public long nextEntryToBeIndexed() {
        return nextEntryToBeIndexed.getVolatileValue();
    }

    long sequenceForPosition(@NotNull StoreRecovery recovery,
                             @NotNull Wire wire,
                             final long position,
                             long timeoutMS)
            throws EOFException, StreamCorruptedException {

        if (((AbstractWire) wire).isInsideHeader()) {
            timeoutMS = 0;
        }
        Bytes<?> bytes = wire.bytes();
        long rl = bytes.readLimit();
        long wl = bytes.writeLimit();
        long capacity = bytes.capacity();
        bytes.writeLimit(capacity).readLimit(capacity);

        try {
            final LongArrayValues index2indexArr = getIndex2index(recovery, wire, timeoutMS);
            long indexOfNext = 0;
            long lastKnownAddress = 0;
            if (((Byteable) index2indexArr).bytesStore() == null)
                return linearScanByPosition(wire, position, indexOfNext, lastKnownAddress);

            int used2 = Maths.toUInt31(index2indexArr.getUsed());
            if (used2 == 0) {
                // create the first index: eagerly.
                getSecondaryAddress(recovery, wire, timeoutMS, index2indexArr, 0);
            }
            Outer:
            for (int index2 = used2 - 1; index2 >= 0; index2--) {
                long secondaryAddress = getSecondaryAddress(recovery, wire, timeoutMS, index2indexArr, index2);
                if (secondaryAddress == 0)
                    continue;

                LongArrayValues indexValues = arrayForAddress(wire, secondaryAddress);
                // TODO use a binary rather than linear search

                // check the first one to see if any in the index is appropriate.
                int used = Maths.toUInt31(indexValues.getUsed());
                assert used >= 0;
                if (used == 0)
                    continue;

                long posN = indexValues.getValueAt(0);
                assert posN > 0;
                if (posN > position)
                    continue;

                for (int index1 = used - 1; index1 >= 0; index1--) {
                    long pos = indexValues.getValueAt(index1);
                    assert pos > 0;

                    if (pos > position) {
                        continue;
                    }
                    lastKnownAddress = pos;
                    indexOfNext = ((long) index2 << (indexCountBits + indexSpacingBits)) + (index1 << indexSpacingBits);

                    if (lastKnownAddress == position)
                        return indexOfNext;

                    break Outer;
                }
            }

            return linearScanByPosition(wire, position, indexOfNext, lastKnownAddress);

        } finally {
            bytes.writeLimit(wl).readLimit(rl);
        }
    }

    long getSecondaryAddress(StoreRecovery recovery, Wire wire, long timeoutMS, LongArrayValues index2indexArr, int index2) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException {
        try {
            return getSecondaryAddress1(recovery, wire, timeoutMS, index2indexArr, index2);
        } catch (TimeoutException fallback) {
            wire.pauser().reset();
            return recovery.recoverSecondaryAddress(index2indexArr, index2, () -> getSecondaryAddress1(recovery, wire, timeoutMS, index2indexArr, index2), timeoutMS);
        }
    }

    long getSecondaryAddress1(StoreRecovery recovery, Wire wire, long timeoutMS, LongArrayValues index2indexArr, int index2) throws EOFException, TimeoutException, UnrecoverableTimeoutException, StreamCorruptedException {
        long secondaryAddress = index2indexArr.getValueAt(index2);
        if (secondaryAddress == 0) {
            if (timeoutMS == 0)
                return 0;
            secondaryAddress = newIndex(recovery, wire, index2indexArr, index2, timeoutMS);
            if (secondaryAddress > wire.bytes().capacity())
                throw new IllegalStateException("sa: " + secondaryAddress);
            long sa = index2indexArr.getValueAt(index2);
            if (sa != secondaryAddress)
                throw new AssertionError();

        } else if (secondaryAddress == BinaryLongReference.LONG_NOT_COMPLETE) {
            secondaryAddress = getSecondaryAddress0(wire, timeoutMS, index2indexArr, index2);

        } else if (secondaryAddress > wire.bytes().capacity()) {
            throw new IllegalStateException("sa: " + secondaryAddress);
        }

        return secondaryAddress;
    }

    private long getSecondaryAddress0(Wire wire, long timeoutMS, LongArrayValues index2indexArr, int index2) throws TimeoutException {
        long secondaryAddress;
        while (true) {
            secondaryAddress = index2indexArr.getVolatileValueAt(index2);
            if (secondaryAddress == BinaryLongReference.LONG_NOT_COMPLETE) {
                wire.pauser().pause(timeoutMS, TimeUnit.MILLISECONDS);
            } else {
                if (secondaryAddress > wire.bytes().capacity())
                    throw new IllegalStateException("sa0: " + secondaryAddress);
                wire.pauser().reset();
                break;
            }
        }
        return secondaryAddress;
    }

    /**
     * add an entry to the sequenceNumber, so stores the position of an sequenceNumber
     *
     * @param recovery       the store
     * @param wire           the wire that used to store the data
     * @param sequenceNumber the sequenceNumber that the data will be stored to
     * @param position       the position the data is at
     * @param timeoutMS      how to to try to store the data for, before throwing a timeout
     *                       exception
     * @throws EOFException
     * @throws UnrecoverableTimeoutException
     * @throws StreamCorruptedException
     */
    void setPositionForSequenceNumber(StoreRecovery recovery,
                                      Wire wire,
                                      long sequenceNumber,
                                      long position, long timeoutMS) throws EOFException, UnrecoverableTimeoutException, StreamCorruptedException {

        // only say for example index every 0,15,31st entry
        if ((sequenceNumber & (indexSpacing - 1)) != 0)
            return;

        if (position > wire.bytes().capacity())
            throw new IllegalArgumentException("pos: " + position);

        // find the index2index
        final LongArrayValues index2indexArr = getIndex2index(recovery, wire, timeoutMS);
        if (((Byteable) index2indexArr).bytesStore() == null) {
            assert false;
            return;
        }

        Bytes<?> bytes = wire.bytes();
        int index2 = (int) ((sequenceNumber) >>> (indexCountBits + indexSpacingBits));
        if (index2 >= indexCount) {
            throw new IllegalStateException("Unable to index " + sequenceNumber);
        }
        long secondaryAddress = getSecondaryAddress(recovery, wire, timeoutMS, index2indexArr, index2);
        if (secondaryAddress > bytes.capacity())
            throw new IllegalStateException("sa2: " + secondaryAddress);
        bytes.readLimit(bytes.capacity());
        LongArrayValues indexValues = arrayForAddress(wire, secondaryAddress);
        int index3 = (int) ((sequenceNumber >>> indexSpacingBits) & (indexCount - 1));

        // check the last one first.
        long posN = indexValues.getValueAt(index3);
        if (posN == 0) {
            indexValues.setValueAt(index3, position);
            indexValues.setMaxUsed(index3 + 1);
        } else {
            assert posN == position;
        }
        nextEntryToBeIndexed.setMaxValue(sequenceNumber + indexSpacing);
    }


    enum IndexingFields implements WireKey {
        indexCount, indexSpacing, index2Index, lastIndex
    }

    static class LongArrayValuesHolder {
        final LongArrayValues values;
        long address;

        LongArrayValuesHolder(LongArrayValues values) {
            this.values = values;
            address = Long.MIN_VALUE;
        }
    }
}
