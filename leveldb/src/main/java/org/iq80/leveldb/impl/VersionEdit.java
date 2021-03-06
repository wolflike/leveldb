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
package org.iq80.leveldb.impl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.iq80.leveldb.util.DynamicSliceOutput;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.SliceInput;
import org.iq80.leveldb.util.VariableLengthQuantity;

import java.util.Map;
import java.util.TreeMap;

/**
 * version对象的变更记录
 * 每个 VersionEdit 会都落日志到 MANIFEST 文件中以备崩溃恢复，
 * 可以认为 MANIFEST 文件中的记录与 VersionEdit 总是一一对应的。
 *
 *
 * leveldb总共有三种文件：
 * manifest：Manifest-000001
 * 日志文件：000001.log
 * sstable：000001.sst
 */
public class VersionEdit
{
    private String comparatorName;


    /**
     * WAL 日志文件的编号
     * 该序号与memTable一一对应
     */
    private Long logNumber;

    /**
     * 下一个文件的编号
     */
    private Long nextFileNumber;

    /**
     * 上一个日志编号
     */
    private Long previousLogNumber;
    /**
     * 下一个写入序列号
     */
    private Long lastSequenceNumber;
    /**
     * 该变量用来指示LevelDB中每个层级下一次进行Compaction操作时需要从哪个键开始
     */
    private final Map<Integer, InternalKey> compactPointers = new TreeMap<>();
    /**
     *记录每个层级执行Compaction操作之后新增的文件
     */
    private final Multimap<Integer, FileMetaData> newFiles = ArrayListMultimap.create();
    /**
     * 记录每个层级执行Compaction操作之后删除掉的文件，注意此处只需要记录删除文件的文件序列号。
     */
    private final Multimap<Integer, Long> deletedFiles = ArrayListMultimap.create();

    //默认初始化，通过set构建属性
    public VersionEdit()
    {
    }

    //通过反序列化的方式初始化
    public VersionEdit(Slice slice)
    {
        SliceInput sliceInput = slice.input();
        while (sliceInput.isReadable()) {
            int i = VariableLengthQuantity.readVariableLengthInt(sliceInput);
            VersionEditTag tag = VersionEditTag.getValueTypeByPersistentId(i);
            tag.readValue(sliceInput, this);
        }
    }

    public String getComparatorName()
    {
        return comparatorName;
    }

    public void setComparatorName(String comparatorName)
    {
        this.comparatorName = comparatorName;
    }

    public Long getLogNumber()
    {
        return logNumber;
    }

    public void setLogNumber(long logNumber)
    {
        this.logNumber = logNumber;
    }

    public Long getNextFileNumber()
    {
        return nextFileNumber;
    }

    public void setNextFileNumber(long nextFileNumber)
    {
        this.nextFileNumber = nextFileNumber;
    }

    public Long getPreviousLogNumber()
    {
        return previousLogNumber;
    }

    public void setPreviousLogNumber(long previousLogNumber)
    {
        this.previousLogNumber = previousLogNumber;
    }

    public Long getLastSequenceNumber()
    {
        return lastSequenceNumber;
    }

    public void setLastSequenceNumber(long lastSequenceNumber)
    {
        this.lastSequenceNumber = lastSequenceNumber;
    }

    public Map<Integer, InternalKey> getCompactPointers()
    {
        return ImmutableMap.copyOf(compactPointers);
    }

    public void setCompactPointer(int level, InternalKey key)
    {
        compactPointers.put(level, key);
    }

    public void setCompactPointers(Map<Integer, InternalKey> compactPointers)
    {
        this.compactPointers.putAll(compactPointers);
    }

    public Multimap<Integer, FileMetaData> getNewFiles()
    {
        return ImmutableMultimap.copyOf(newFiles);
    }

    // Add the specified file at the specified level.
    // REQUIRES: This version has not been saved (see VersionSet::SaveTo)
    // REQUIRES: "smallest" and "largest" are smallest and largest keys in file
    public void addFile(int level, long fileNumber,
            long fileSize,
            InternalKey smallest,
            InternalKey largest)
    {
        FileMetaData fileMetaData = new FileMetaData(fileNumber, fileSize, smallest, largest);
        addFile(level, fileMetaData);
    }

    public void addFile(int level, FileMetaData fileMetaData)
    {
        newFiles.put(level, fileMetaData);
    }

    public void addFiles(Multimap<Integer, FileMetaData> files)
    {
        newFiles.putAll(files);
    }

    public Multimap<Integer, Long> getDeletedFiles()
    {
        return ImmutableMultimap.copyOf(deletedFiles);
    }

    // Delete the specified "file" from the specified "level".
    public void deleteFile(int level, long fileNumber)
    {
        deletedFiles.put(level, fileNumber);
    }

    public Slice encode()
    {
        DynamicSliceOutput dynamicSliceOutput = new DynamicSliceOutput(4096);
        for (VersionEditTag versionEditTag : VersionEditTag.values()) {
            versionEditTag.writeValue(dynamicSliceOutput, this);
        }
        return dynamicSliceOutput.slice();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("VersionEdit");
        sb.append("{comparatorName='").append(comparatorName).append('\'');
        sb.append(", logNumber=").append(logNumber);
        sb.append(", previousLogNumber=").append(previousLogNumber);
        sb.append(", lastSequenceNumber=").append(lastSequenceNumber);
        sb.append(", compactPointers=").append(compactPointers);
        sb.append(", newFiles=").append(newFiles);
        sb.append(", deletedFiles=").append(deletedFiles);
        sb.append('}');
        return sb.toString();
    }
}
