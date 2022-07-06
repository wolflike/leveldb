/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.table;

import com.google.common.base.Throwables;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.util.PureJavaCrc32C;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.Slices;
import org.iq80.leveldb.util.Snappy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.VersionSet.TARGET_FILE_SIZE;

/**
 * 用来生成SSTable
 */
public class TableBuilder
{
    /**
     * TABLE_MAGIC_NUMBER was picked by running
     * echo http://code.google.com/p/leveldb/ | sha1sum
     * and taking the leading 64 bits.
     */
    public static final long TABLE_MAGIC_NUMBER = 0xdb4775248b80fb57L;

    private final int blockRestartInterval;
    private final int blockSize;
    private final CompressionType compressionType;

    private final FileChannel fileChannel;
    private final BlockBuilder dataBlockBuilder;
    private final BlockBuilder indexBlockBuilder;
    private Slice lastKey;
    private final UserComparator userComparator;

    private long entryCount;

    // Either Finish() or Abandon() has been called.
    private boolean closed;

    // We do not emit the index entry for a block until we have seen the
    // first key for the next data block.  This allows us to use shorter
    // keys in the index block.  For example, consider a block boundary
    // between the keys "the quick brown fox" and "the who".  We can use
    // "the r" as the key for the index block entry since it is >= all
    // entries in the first block and < all entries in subsequent
    // blocks.
    //在我们看到下一个数据块的第一个键之前，我们不会为一个块发出索引条目。
    //这允许我们在索引块中使用较短的键。 例如，考虑键“the quick brown fox”和“the who”之间的块边界。
    //我们可以使用“r”作为索引块条目的键，因为它 >= 第一个块中的所有条目并且 < 后续块中的所有条目。
    private boolean pendingIndexEntry;
    private BlockHandle pendingHandle;  // Handle to add to index block

    private Slice compressedOutput;

    private long position;

    public TableBuilder(Options options, FileChannel fileChannel, UserComparator userComparator)
    {
        requireNonNull(options, "options is null");
        requireNonNull(fileChannel, "fileChannel is null");
        try {
            checkState(position == fileChannel.position(), "Expected position %s to equal fileChannel.position %s", position, fileChannel.position());
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }

        this.fileChannel = fileChannel;
        this.userComparator = userComparator;

        blockRestartInterval = options.blockRestartInterval();
        blockSize = options.blockSize();
        compressionType = options.compressionType();

        //一个数据块的estimatedSize必须比默认的blockSize(4KB)大，比一个文件（2MB）小
        dataBlockBuilder = new BlockBuilder((int) Math.min(blockSize * 1.1, TARGET_FILE_SIZE), blockRestartInterval, userComparator);

        // with expected 50% compression
        int expectedNumberOfBlocks = 1024;
        indexBlockBuilder = new BlockBuilder(BlockHandle.MAX_ENCODED_LENGTH * expectedNumberOfBlocks, 1, userComparator);

        lastKey = Slices.EMPTY_SLICE;
    }

    public long getEntryCount()
    {
        return entryCount;
    }

    public long getFileSize()
            throws IOException
    {
        return position + dataBlockBuilder.currentSizeEstimate();
    }

    public void add(BlockEntry blockEntry)
            throws IOException
    {
        requireNonNull(blockEntry, "blockEntry is null");
        add(blockEntry.getKey(), blockEntry.getValue());
    }

    public void add(Slice key, Slice value)
            throws IOException
    {
        requireNonNull(key, "key is null");
        requireNonNull(value, "value is null");

        checkState(!closed, "table is finished");

        if (entryCount > 0) {
            assert (userComparator.compare(key, lastKey) > 0) : "key must be greater than last key";
        }

        // If we just wrote a block, we can now add the handle to index block
        //在构建数据区域的时候，同时构建索引区域
        if (pendingIndexEntry) {
            checkState(dataBlockBuilder.isEmpty(), "Internal error: Table has a pending index entry but data block builder is empty");

            //这里的lastKey是上一个数据块的最后一个key，而当前的key是当前数据块第一个key
            //按理说这里的索引应该是按数据块的第一个key直接进行索引就好了
            //但是为了节约空间，取了[lastKey,key]之间的最短key，这样比lastKey大，
            //索引的时候判断比当前数据块的shortestSeparator小，就找到了
            //这里的shortestSeparator求的是上一次的数据块
            //最小分隔符
            Slice shortestSeparator = userComparator.findShortestSeparator(lastKey, key);

            //块的索引
            Slice handleEncoding = BlockHandle.writeBlockHandle(pendingHandle);
            //添加index数据
            indexBlockBuilder.add(shortestSeparator, handleEncoding);
            pendingIndexEntry = false;
        }

        lastKey = key;
        entryCount++;
        //构建键值对
        //在构建的过程中，肯定需要根据间隔记录重启点
        dataBlockBuilder.add(key, value);

        //estimatedBlockSize=键值对大小+重启点数据大小
        int estimatedBlockSize = dataBlockBuilder.currentSizeEstimate();
        //评估当前数据是否超过块大小
        //blockSize默认4KB
        //dataBlockBuilder中的block不是4096而是4096*1.1
        //而且dataBlockBuilder的4096就是个逻辑块，真的要查找还得靠position+length
        if (estimatedBlockSize >= blockSize) {

            //到到达4096字节时，（构建重启点数据、压缩类型、校验数据）开始写入块
            flush();
        }
    }

    private void flush()
            throws IOException
    {
        checkState(!closed, "table is finished");
        if (dataBlockBuilder.isEmpty()) {
            return;
        }

        checkState(!pendingIndexEntry, "Internal error: Table already has a pending index entry to flush");


        //指定了这个块的position和length
        pendingHandle = writeBlock(dataBlockBuilder);
        //这里代表一个块完成了，每构建好一个数据区域逻辑块就需要进行索引，只有pendingIndexEntry为true时方可索引
        pendingIndexEntry = true;
    }

    private BlockHandle writeBlock(BlockBuilder blockBuilder)
            throws IOException
    {
        // close the block
        //写入重启点数据
        Slice raw = blockBuilder.finish();

        // attempt to compress the block
        Slice blockContents = raw;
        CompressionType blockCompressionType = CompressionType.NONE;
        if (compressionType == CompressionType.SNAPPY) {
            ensureCompressedOutputCapacity(maxCompressedLength(raw.length()));
            try {
                int compressedSize = Snappy.compress(raw.getRawArray(), raw.getRawOffset(), raw.length(), compressedOutput.getRawArray(), 0);

                // Don't use the compressed data if compressed less than 12.5%,
                if (compressedSize < raw.length() - (raw.length() / 8)) {
                    blockContents = compressedOutput.slice(0, compressedSize);
                    blockCompressionType = CompressionType.SNAPPY;
                }
            }
            catch (IOException ignored) {
                // compression failed, so just store uncompressed form
            }
        }

        // create block trailer
        //这是一个块的尾部部分
        //通常一个块被分为4部分：
        //1.键值对数据2.重启点数据3.压缩类型4.校验数据
        //这里的trailer就包含了3和4
        BlockTrailer blockTrailer = new BlockTrailer(blockCompressionType, crc32c(blockContents, blockCompressionType));
        Slice trailer = BlockTrailer.writeBlockTrailer(blockTrailer);

        // create a handle to this block
        //为这个块创建一个handle（指针）
        BlockHandle blockHandle = new BlockHandle(position, blockContents.length());

        // write data and trailer
        //将数据和trailer写进这个fileChannel
        //blockContents=键值对+重启点数据
        //trailer=压缩类型+校验数据
        //默认从末尾追加数据
        position += fileChannel.write(new ByteBuffer[] {blockContents.toByteBuffer(), trailer.toByteBuffer()});

        // clean up state
        blockBuilder.reset();

        return blockHandle;
    }

    private static int maxCompressedLength(int length)
    {
        // Compressed data can be defined as:
        //    compressed := item* literal*
        //    item       := literal* copy
        //
        // The trailing literal sequence has a space blowup of at most 62/60
        // since a literal of length 60 needs one tag byte + one extra byte
        // for length information.
        //
        // Item blowup is trickier to measure.  Suppose the "copy" op copies
        // 4 bytes of data.  Because of a special check in the encoding code,
        // we produce a 4-byte copy only if the offset is < 65536.  Therefore
        // the copy op takes 3 bytes to encode, and this type of item leads
        // to at most the 62/60 blowup for representing literals.
        //
        // Suppose the "copy" op copies 5 bytes of data.  If the offset is big
        // enough, it will take 5 bytes to encode the copy op.  Therefore the
        // worst case here is a one-byte literal followed by a five-byte copy.
        // I.e., 6 bytes of input turn into 7 bytes of "compressed" data.
        //
        // This last factor dominates the blowup, so the final estimate is:
        return 32 + length + (length / 6);
    }

    /**
     * 在数据区域构建好之后，就要开始拼接数据区域、元数据区域、索引区域、footer
     * @throws IOException
     */
    public void finish()
            throws IOException
    {
        checkState(!closed, "table is finished");

        // flush current data block
        //最后不满4KB的需要刷盘
        flush();

        // mark table as closed
        closed = true;

        // write (empty) meta index block
        //元数据
        BlockBuilder metaIndexBlockBuilder = new BlockBuilder(256, blockRestartInterval, new BytewiseComparator());
        // TODO(post release): Add stats and other meta blocks
        //可以看到这里的元数据为空（需要添加布隆、加快访问速度）
        BlockHandle metaindexBlockHandle = writeBlock(metaIndexBlockBuilder);

        // add last handle to index block
        //最后的一次flush，对应着最后一次索引（可能不足一个逻辑块4KB）
        if (pendingIndexEntry) {
            Slice shortSuccessor = userComparator.findShortSuccessor(lastKey);

            Slice handleEncoding = BlockHandle.writeBlockHandle(pendingHandle);
            indexBlockBuilder.add(shortSuccessor, handleEncoding);
            pendingIndexEntry = false;
        }

        // write index block
        //可以看到index block只有一个块
        BlockHandle indexBlockHandle = writeBlock(indexBlockBuilder);

        // write footer
        Footer footer = new Footer(metaindexBlockHandle, indexBlockHandle);
        Slice footerEncoding = Footer.writeFooter(footer);
        position += fileChannel.write(footerEncoding.toByteBuffer());
    }

    public void abandon()
    {
        checkState(!closed, "table is finished");
        closed = true;
    }

    public static int crc32c(Slice data, CompressionType type)
    {
        PureJavaCrc32C crc32c = new PureJavaCrc32C();
        crc32c.update(data.getRawArray(), data.getRawOffset(), data.length());
        crc32c.update(type.persistentId() & 0xFF);
        return crc32c.getMaskedValue();
    }

    public void ensureCompressedOutputCapacity(int capacity)
    {
        if (compressedOutput != null && compressedOutput.length() > capacity) {
            return;
        }
        compressedOutput = Slices.allocate(capacity);
    }
}
