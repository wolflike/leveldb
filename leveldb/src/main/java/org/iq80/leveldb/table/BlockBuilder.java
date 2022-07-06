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

import com.google.common.primitives.Ints;
import org.iq80.leveldb.util.DynamicSliceOutput;
import org.iq80.leveldb.util.IntVector;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.VariableLengthQuantity;

import java.util.Comparator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.util.SizeOf.SIZE_OF_INT;

/**
 * 该类用来生成一个block
 */
public class BlockBuilder
{
    //两个重启点间隔的数据个数
    private final int blockRestartInterval;
    private final IntVector restartPositions;
    private final Comparator<Slice> comparator;

    //开启新的重启点之后加入的键-值对，默认保存16个键-值对，之后会开启一个新的重启点。
    private int entryCount;
    //每次开启新的重启点后，会将当前buffer_的数据长度保存到restarts_中，
    //当前buffer_中的数据长度即为每个重启点的偏移量。
    private int restartBlockEntryCount;

    private boolean finished;
    //最终的数据保存在这里
    private final DynamicSliceOutput block;
    //上一个保存的键，当加入新键时，用来计算和上一个键的共同前缀部分。
    private Slice lastKey;

    public BlockBuilder(int estimatedSize, int blockRestartInterval, Comparator<Slice> comparator)
    {
        checkArgument(estimatedSize >= 0, "estimatedSize is negative");
        checkArgument(blockRestartInterval >= 0, "blockRestartInterval is negative");
        requireNonNull(comparator, "comparator is null");

        this.block = new DynamicSliceOutput(estimatedSize);
        this.blockRestartInterval = blockRestartInterval;
        this.comparator = comparator;

        restartPositions = new IntVector(32);
        restartPositions.add(0);  // first restart point must be 0
    }

    public void reset()
    {
        block.reset();
        entryCount = 0;
        restartPositions.clear();
        restartPositions.add(0); // first restart point must be 0
        restartBlockEntryCount = 0;
        lastKey = null;
        finished = false;
    }

    public int getEntryCount()
    {
        return entryCount;
    }

    public boolean isEmpty()
    {
        return entryCount == 0;
    }

    public int currentSizeEstimate()
    {
        // no need to estimate if closed
        if (finished) {
            return block.size();
        }

        // no records is just a single int
        if (block.size() == 0) {
            return SIZE_OF_INT;
        }

        return block.size() +                              // raw data buffer
                restartPositions.size() * SIZE_OF_INT +    // restart positions
                SIZE_OF_INT;                               // restart position size
    }

    public void add(BlockEntry blockEntry)
    {
        requireNonNull(blockEntry, "blockEntry is null");
        add(blockEntry.getKey(), blockEntry.getValue());
    }

    public void add(Slice key, Slice value)
    {
        requireNonNull(key, "key is null");
        requireNonNull(value, "value is null");
        checkState(!finished, "block is finished");
        checkPositionIndex(restartBlockEntryCount, blockRestartInterval);

        checkArgument(lastKey == null || comparator.compare(key, lastKey) > 0, "key must be greater than last key");

        int sharedKeyBytes = 0;
        //间隔数小于固定的间隔（默认16）
        if (restartBlockEntryCount < blockRestartInterval) {
            sharedKeyBytes = calculateSharedBytes(key, lastKey);
        }
        else {
            // restart prefix compression
            //添加重启点
            restartPositions.add(block.size());
            restartBlockEntryCount = 0;
        }

        int nonSharedKeyBytes = key.length() - sharedKeyBytes;

        // write "<shared><non_shared><value_size>"
        VariableLengthQuantity.writeVariableLengthInt(sharedKeyBytes, block);
        VariableLengthQuantity.writeVariableLengthInt(nonSharedKeyBytes, block);
        VariableLengthQuantity.writeVariableLengthInt(value.length(), block);

        // write non-shared key bytes
        block.writeBytes(key, sharedKeyBytes, nonSharedKeyBytes);

        // write value bytes
        block.writeBytes(value, 0, value.length());

        // update last key
        lastKey = key;

        // update state
        entryCount++;
        //重启点之间的间隔数
        restartBlockEntryCount++;
    }

    public static int calculateSharedBytes(Slice leftKey, Slice rightKey)
    {
        int sharedKeyBytes = 0;

        if (leftKey != null && rightKey != null) {
            int minSharedKeyBytes = Ints.min(leftKey.length(), rightKey.length());
            while (sharedKeyBytes < minSharedKeyBytes && leftKey.getByte(sharedKeyBytes) == rightKey.getByte(sharedKeyBytes)) {
                sharedKeyBytes++;
            }
        }

        return sharedKeyBytes;
    }

    public Slice finish()
    {
        if (!finished) {
            finished = true;

            if (entryCount > 0) {
                restartPositions.write(block);
                block.writeInt(restartPositions.size());
            }
            else {
                block.writeInt(0);
            }
        }
        return block.slice();
    }
}
