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

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import org.iq80.leveldb.impl.Filename.FileInfo;
import org.iq80.leveldb.impl.Filename.FileType;
import org.iq80.leveldb.impl.MemTable.MemTableIterator;
import org.iq80.leveldb.impl.WriteBatchImpl.Handler;
import org.iq80.leveldb.table.BytewiseComparator;
import org.iq80.leveldb.table.CustomUserComparator;
import org.iq80.leveldb.table.TableBuilder;
import org.iq80.leveldb.table.UserComparator;
import org.iq80.leveldb.util.DbIterator;
import org.iq80.leveldb.util.MergingIterator;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.SliceInput;
import org.iq80.leveldb.util.SliceOutput;
import org.iq80.leveldb.util.Slices;
import org.iq80.leveldb.util.Snappy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.DbConstants.L0_SLOWDOWN_WRITES_TRIGGER;
import static org.iq80.leveldb.impl.DbConstants.L0_STOP_WRITES_TRIGGER;
import static org.iq80.leveldb.impl.DbConstants.NUM_LEVELS;
import static org.iq80.leveldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static org.iq80.leveldb.impl.ValueType.DELETION;
import static org.iq80.leveldb.impl.ValueType.VALUE;
import static org.iq80.leveldb.util.SizeOf.SIZE_OF_INT;
import static org.iq80.leveldb.util.SizeOf.SIZE_OF_LONG;
import static org.iq80.leveldb.util.Slices.readLengthPrefixedBytes;
import static org.iq80.leveldb.util.Slices.writeLengthPrefixedBytes;

// todo make thread safe and concurrent
@SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
public class DbImpl
        implements DB
{
    //参数选项
    private final Options options;
    //数据库目录
    private final File databaseDir;
    //SSTable
    private final TableCache tableCache;
    //文件锁
    private final DbLock dbLock;
    //版本设置器
    private final VersionSet versions;

    //是否关闭
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    //控制并发锁
    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition backgroundCondition = mutex.newCondition();

    private final List<Long> pendingOutputs = new ArrayList<>(); // todo

    //日志输出器（fileChannel、mMap）
    private LogWriter log;

    //内存mapTable
    private MemTable memTable;
    private MemTable immutableMemTable;

    //比较器
    private final InternalKeyComparator internalKeyComparator;

    private volatile Throwable backgroundException;
    //合并线程
    private final ExecutorService compactionExecutor;


    //异步合并的结果
    private Future<?> backgroundCompaction;

    //人工合并
    private ManualCompaction manualCompaction;

    /**
     * 数据库初始化
     * @param options 数据库初始化的参数（比如块大小、块的重启点间隔、最大能打开的文件数）
     * @param databaseDir 数据库初始化的目录
     * @throws IOException
     */
    public DbImpl(Options options, File databaseDir)
            throws IOException
    {
        requireNonNull(options, "options is null");
        requireNonNull(databaseDir, "databaseDir is null");
        this.options = options;

        //如果snappy压缩方式不可用，那就不用
        if (this.options.compressionType() == CompressionType.SNAPPY && !Snappy.available()) {
            // Disable snappy if it's not available.
            this.options.compressionType(CompressionType.NONE);
        }

        this.databaseDir = databaseDir;

        //use custom comparator if set
        //优先使用用户自定义比较器
        //获取options中默认的数据库比较器，其比较对象为byte数组
        DBComparator comparator = options.comparator();
        //用户比较器，其比较对象是Slice
        UserComparator userComparator;
        if (comparator != null) {
            //自定义用户比较器委托db比较器处理
            //按用户定义方式比较
            userComparator = new CustomUserComparator(comparator);
        }
        else {
            //按Slice格式比较
            userComparator = new BytewiseComparator();
        }
        //internalKey是面向用户的比较器
        //其比较对象是InternalKey,先委托给userComparator进行key的slice格式比较
        //然后比较InternalKey中的sequenceNumber
        //按InternalKey比较
        internalKeyComparator = new InternalKeyComparator(userComparator);
        //创建memTable
        //基于internalKeyComparator创建memTable
        memTable = new MemTable(internalKeyComparator);
        //初始置为null
        immutableMemTable = null;

        //为线程池创建线程的线程工厂，主要设置了名称格式、添加了异常处理
        //此为构建模式
        ThreadFactory compactionThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("leveldb-compaction-%s")
                .setUncaughtExceptionHandler(new UncaughtExceptionHandler()
                {
                    @Override
                    public void uncaughtException(Thread t, Throwable e)
                    {
                        // todo need a real UncaughtExceptionHandler
                        System.out.printf("%s%n", t);
                        e.printStackTrace();
                    }
                })
                .build();
        //创建单线程的线程池，专门用来合并处理
        compactionExecutor = Executors.newSingleThreadExecutor(compactionThreadFactory);

        // Reserve ten files or so for other uses and give the rest to TableCache.
        //也就是说规定缓存（主要保留FileChannel和MMap的引用）只能缓存990个sstTable
        int tableCacheSize = options.maxOpenFiles() - 10;
        //tableCacheSize是最多能缓存的个数
        //InternalUserComparator委托internalKeyComparator实现
        //创建一个缓存，缓存的意思是：当要读数据时，直接在缓存读，读不到从磁盘加载进来
        tableCache = new TableCache(databaseDir, tableCacheSize, new InternalUserComparator(internalKeyComparator), options.verifyChecksums());

        // create the version set

        // create the database dir if it does not already exist
        //创建数据库目录
        databaseDir.mkdirs();
        checkArgument(databaseDir.exists(), "Database directory '%s' does not exist and could not be created", databaseDir);
        checkArgument(databaseDir.isDirectory(), "Database directory '%s' is not a directory", databaseDir);

        //没理解为啥这里需要锁住，难道还有多个线程对数据库初始化不成
        mutex.lock();
        try {
            // lock the database dir
            //该dbLock的作用就是锁住这个LOCK这个文件，当执行close函数时便可以释放
            //（初始化时就直接tryLock）
            //也就是说这个LOCK文件其他进程不能动
            dbLock = new DbLock(new File(databaseDir, Filename.lockFileName()));

            // verify the "current" file
            //确认CURRENT文件是否可读（确保读出MANIFEST）
            File currentFile = new File(databaseDir, Filename.currentFileName());
            if (!currentFile.canRead()) {
                checkArgument(options.createIfMissing(), "Database '%s' does not exist and the create if missing option is disabled", databaseDir);
            }
            else {
                checkArgument(!options.errorIfExists(), "Database '%s' exists and the error if exists option is enabled", databaseDir);
            }

            //对于VersionSet来说，肯定需要知道数据库目录，进行文件恢复工作
            //需要tableCache用于sstTable文件缓存
            //需要internalKeyComparator用户键比较
            //初始化时，主要将初始化好的Version（没有内容）插进去
            versions = new VersionSet(databaseDir, tableCache, internalKeyComparator);

            // load  (and recover) current version
            //恢复，读取manifest，创建builder，将builder保存到v，然后将current指向v
            //主要恢复manifest文件，将versionEdit apply到version中，然后创建一个新的manifest文件
            versions.recover();

            // Recover from all newer log files than the ones named in the
            // descriptor (new log files may have been added by the previous
            // incarnation without registering them in the descriptor).
            //
            // Note that PrevLogNumber() is no longer used, but we pay
            // attention to it in case we are recovering a database
            // produced by an older version of leveldb.
            //从所有比描述符中命名的日志文件更新的日志文件中恢复（新日志文件可能是由前一个版本添加的，而没有在描述符中注册它们）。
            //请注意，PrevLogNumber（）已不再使用，但在恢复由旧版本的leveldb生成的数据库时，我们会注意它。
            long minLogNumber = versions.getLogNumber();
            long previousLogNumber = versions.getPrevLogNumber();
            List<File> filenames = Filename.listFiles(databaseDir);

            List<Long> logs = new ArrayList<>();
            //找出所有满足大于最后一次的edit的logNumber或和previousLogNumber相等的日志文件
            for (File filename : filenames) {
                FileInfo fileInfo = Filename.parseFileName(filename);

                if (fileInfo != null &&
                        fileInfo.getFileType() == FileType.LOG &&
                        ((fileInfo.getFileNumber() >= minLogNumber) || (fileInfo.getFileNumber() == previousLogNumber))) {
                    logs.add(fileInfo.getFileNumber());
                }
            }

            // Recover in the order in which the logs were generated
            VersionEdit edit = new VersionEdit();
            //从日志中恢复数据道memTable，memTable可能会dump到ssTable，因此edit可能变更
            Collections.sort(logs);
            //将所有的日志进行恢复
            for (Long fileNumber : logs) {
                //恢复日志，将日志内容apply到version中，
                //因为可能涉及到memTable会满，所以需要记录edit
                long maxSequence = recoverLogFile(fileNumber, edit);
                //日志中记录的sequence肯定是最大的，所以可以更新上
                if (versions.getLastSequence() < maxSequence) {
                    versions.setLastSequence(maxSequence);
                }
            }

            // open transaction log
            //全局自增，在恢复后，会自增id，赋值给新的log
            //通过fileNumber自增新建了一个log文件（说明之前的文件要删除了？）
            long logFileNumber = versions.getNextFileNumber();
            //创建log
            this.log = Logs.createLogWriter(new File(databaseDir, Filename.logFileName(logFileNumber)), logFileNumber);
            //edit设置了新的logNumber
            edit.setLogNumber(log.getFileNumber());

            // apply recovered edits
            //生成新的版本(current+edit)
            versions.logAndApply(edit);

            // cleanup unused files
            //清理无用文件
            //根据logNumber、previousLogNumber、fileNumber等信息进行删除过期文件
            deleteObsoleteFiles();

            // schedule compactions
            //尝试进行一次Compaction操作
            maybeScheduleCompaction();
        }
        finally {
            mutex.unlock();
        }
    }

    /**
     * 关闭系统的各种资源
     */
    @Override
    public void close()
    {
        if (shuttingDown.getAndSet(true)) {
            return;
        }

        mutex.lock();
        try {
            while (backgroundCompaction != null) {
                backgroundCondition.awaitUninterruptibly();
            }
        }
        finally {
            mutex.unlock();
        }

        compactionExecutor.shutdown();
        try {
            compactionExecutor.awaitTermination(1, TimeUnit.DAYS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            versions.destroy();
        }
        catch (IOException ignored) {
        }
        try {
            log.close();
        }
        catch (IOException ignored) {
        }
        tableCache.close();
        dbLock.release();
    }

    @Override
    public String getProperty(String name)
    {
        checkBackgroundException();
        return null;
    }

    private void deleteObsoleteFiles()
    {
        checkState(mutex.isHeldByCurrentThread());

        // Make a set of all of the live files
        List<Long> live = new ArrayList<>(this.pendingOutputs);
        for (FileMetaData fileMetaData : versions.getLiveFiles()) {
            live.add(fileMetaData.getNumber());
        }

        for (File file : Filename.listFiles(databaseDir)) {
            FileInfo fileInfo = Filename.parseFileName(file);
            if (fileInfo == null) {
                continue;
            }
            long number = fileInfo.getFileNumber();
            boolean keep = true;
            switch (fileInfo.getFileType()) {
                case LOG:
                    keep = ((number >= versions.getLogNumber()) ||
                            (number == versions.getPrevLogNumber()));
                    break;
                case DESCRIPTOR:
                    // Keep my manifest file, and any newer incarnations'
                    // (in case there is a race that allows other incarnations)
                    keep = (number >= versions.getManifestFileNumber());
                    break;
                case TABLE:
                    keep = live.contains(number);
                    break;
                case TEMP:
                    // Any temp files that are currently being written to must
                    // be recorded in pending_outputs_, which is inserted into "live"
                    keep = live.contains(number);
                    break;
                case CURRENT:
                case DB_LOCK:
                case INFO_LOG:
                    keep = true;
                    break;
            }

            if (!keep) {
                if (fileInfo.getFileType() == FileType.TABLE) {
                    tableCache.evict(number);
                }
                // todo info logging system needed
//                Log(options_.info_log, "Delete type=%d #%lld\n",
//                int(type),
//                        static_cast < unsigned long long>(number));
                file.delete();
            }
        }
    }

    /**
     * 刷新内存表（dump）
     */
    public void flushMemTable()
    {
        mutex.lock();
        try {
            // force compaction
            makeRoomForWrite(true);

            // todo bg_error code
            while (immutableMemTable != null) {
                backgroundCondition.awaitUninterruptibly();
            }

        }
        finally {
            mutex.unlock();
        }
    }

    public void compactRange(int level, Slice start, Slice end)
    {
        checkArgument(level >= 0, "level is negative");
        checkArgument(level + 1 < NUM_LEVELS, "level is greater than or equal to %s", NUM_LEVELS);
        requireNonNull(start, "start is null");
        requireNonNull(end, "end is null");

        mutex.lock();
        try {
            while (this.manualCompaction != null) {
                backgroundCondition.awaitUninterruptibly();
            }
            ManualCompaction manualCompaction = new ManualCompaction(level, start, end);
            this.manualCompaction = manualCompaction;

            maybeScheduleCompaction();

            while (this.manualCompaction == manualCompaction) {
                backgroundCondition.awaitUninterruptibly();
            }
        }
        finally {
            mutex.unlock();
        }

    }

    private void maybeScheduleCompaction()
    {
        //保证线程持有锁
        checkState(mutex.isHeldByCurrentThread());

        if (backgroundCompaction != null) {
            // Already scheduled
        }
        else if (shuttingDown.get()) {
            // DB is being shutdown; no more background compactions
        }
        else if (immutableMemTable == null &&
                manualCompaction == null &&
                !versions.needsCompaction()) {
            // No work to be done
        }
        else {
            //提交合并任务，返回异步结果
            backgroundCompaction = compactionExecutor.submit(new Callable<Void>()
            {
                @Override
                public Void call()
                        throws Exception
                {
                    try {
                        //do work
                        backgroundCall();
                    }
                    catch (DatabaseShutdownException ignored) {
                    }
                    catch (Throwable e) {
                        backgroundException = e;
                    }
                    return null;
                }
            });
        }
    }

    public void checkBackgroundException()
    {
        Throwable e = backgroundException;
        if (e != null) {
            throw new BackgroundProcessingException(e);
        }
    }

    private void backgroundCall()
            throws IOException
    {
        mutex.lock();
        try {
            if (backgroundCompaction == null) {
                return;
            }

            try {
                if (!shuttingDown.get()) {
                    //actual work
                    backgroundCompaction();
                }
            }
            finally {
                backgroundCompaction = null;
            }
        }
        finally {
            try {
                // Previous compaction may have produced too many files in a level,
                // so reschedule another compaction if needed.
                maybeScheduleCompaction();
            }
            finally {
                try {
                    backgroundCondition.signalAll();
                }
                finally {
                    mutex.unlock();
                }
            }
        }
    }

    /**
     * compaction入口
     * @throws IOException
     */
    private void backgroundCompaction()
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());

        //immutableMemTable不为null
        //minorCompaction
        compactMemTableInternal();

        Compaction compaction;
        if (manualCompaction != null) {
            //得到了要处理那些文件的信息
            compaction = versions.compactRange(manualCompaction.level,
                    new InternalKey(manualCompaction.begin, MAX_SEQUENCE_NUMBER, VALUE),
                    new InternalKey(manualCompaction.end, 0, DELETION));
        }
        else {
            compaction = versions.pickCompaction();
        }

        if (compaction == null) {
            // no compaction
        }
        else if (manualCompaction == null && compaction.isTrivialMove()) {
            // Move file to next level
            checkState(compaction.getLevelInputs().size() == 1);
            FileMetaData fileMetaData = compaction.getLevelInputs().get(0);
            compaction.getEdit().deleteFile(compaction.getLevel(), fileMetaData.getNumber());
            compaction.getEdit().addFile(compaction.getLevel() + 1, fileMetaData);
            versions.logAndApply(compaction.getEdit());
            // log
        }
        else {
            CompactionState compactionState = new CompactionState(compaction);
            //开始合并
            doCompactionWork(compactionState);
            cleanupCompaction(compactionState);
        }

        // manual compaction complete
        if (manualCompaction != null) {
            manualCompaction = null;
        }
    }

    private void cleanupCompaction(CompactionState compactionState)
    {
        checkState(mutex.isHeldByCurrentThread());

        if (compactionState.builder != null) {
            compactionState.builder.abandon();
        }
        else {
            checkArgument(compactionState.outfile == null);
        }

        for (FileMetaData output : compactionState.outputs) {
            pendingOutputs.remove(output.getNumber());
        }
    }

    /**
     * 从日志中恢复日志文件，重现memTable
     * @param fileNumber
     * @param edit
     * @return
     * @throws IOException
     */
    private long recoverLogFile(long fileNumber, VersionEdit edit)
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());
        File file = new File(databaseDir, Filename.logFileName(fileNumber));
        try (FileInputStream fis = new FileInputStream(file);
                FileChannel channel = fis.getChannel()) {
            LogMonitor logMonitor = LogMonitors.logMonitor();
            LogReader logReader = new LogReader(channel, logMonitor, true, 0);

            // Log(options_.info_log, "Recovering log #%llu", (unsigned long long) log_number);

            // Read all the records and add to a memtable
            long maxSequence = 0;
            MemTable memTable = null;
            //从日志中获取日志数据
            for (Slice record = logReader.readRecord(); record != null; record = logReader.readRecord()) {
                SliceInput sliceInput = record.input();
                // read header
                if (sliceInput.available() < 12) {
                    logMonitor.corruption(sliceInput.available(), "log record too small");
                    continue;
                }
                long sequenceBegin = sliceInput.readLong();
                int updateSize = sliceInput.readInt();

                // read entries
                WriteBatchImpl writeBatch = readWriteBatch(sliceInput, updateSize);

                // apply entries to memTable
                if (memTable == null) {
                    memTable = new MemTable(internalKeyComparator);
                }
                writeBatch.forEach(new InsertIntoHandler(memTable, sequenceBegin));

                // update the maxSequence
                long lastSequence = sequenceBegin + updateSize - 1;
                if (lastSequence > maxSequence) {
                    maxSequence = lastSequence;
                }

                // flush mem table if necessary
                //如果memTable(默认4MB)满了可以生成SSTable
                if (memTable.approximateMemoryUsage() > options.writeBufferSize()) {
                    writeLevel0Table(memTable, edit, null);
                    memTable = null;
                }
            }

            // flush mem table
            //也就是说，不管memTable满没满，都需要把这个进行dump到ssTable中
            if (memTable != null && !memTable.isEmpty()) {
                writeLevel0Table(memTable, edit, null);
                //这里为啥没有把memTable置为null
            }

            return maxSequence;
        }
    }

    @Override
    public byte[] get(byte[] key)
            throws DBException
    {
        return get(key, new ReadOptions());
    }

    @Override
    public byte[] get(byte[] key, ReadOptions options)
            throws DBException
    {
        checkBackgroundException();
        LookupKey lookupKey;
        mutex.lock();
        try {
            //默认获取当前快照（最新的sequence）
            SnapshotImpl snapshot = getSnapshot(options);
            //根据key和sequenceNumber构造LookupKey
            lookupKey = new LookupKey(Slices.wrappedBuffer(key), snapshot.getLastSequence());

            // First look in the memtable, then in the immutable memtable (if any).
            //根据lookupKey从获取memTable获取结果
            LookupResult lookupResult = memTable.get(lookupKey);
            if (lookupResult != null) {
                Slice value = lookupResult.getValue();
                if (value == null) {
                    return null;
                }
                return value.getBytes();
            }
            //如果memTable没有，就从immutableMemTable拿
            if (immutableMemTable != null) {
                lookupResult = immutableMemTable.get(lookupKey);
                if (lookupResult != null) {
                    Slice value = lookupResult.getValue();
                    if (value == null) {
                        return null;
                    }
                    return value.getBytes();
                }
            }
        }
        finally {
            mutex.unlock();
        }
        //这里体现的就是MVCC的思想，对于内存的数据还是通过mutex来实现
        //而对于文件级别的数据可以使用MVCC，因为version是个静态视角

        // Not in memTables; try live files in level order
        //如果上面都没有就可以从SSTable中拿
        //这是在io中拿的，所以不需要锁
        LookupResult lookupResult = versions.get(lookupKey);

        // schedule compaction if necessary
        mutex.lock();
        try {
            if (versions.needsCompaction()) {
                maybeScheduleCompaction();
            }
        }
        finally {
            mutex.unlock();
        }

        if (lookupResult != null) {
            Slice value = lookupResult.getValue();
            if (value != null) {
                return value.getBytes();
            }
        }
        return null;
    }

    @Override
    public void put(byte[] key, byte[] value)
            throws DBException
    {
        put(key, value, new WriteOptions());
    }

    @Override
    public Snapshot put(byte[] key, byte[] value, WriteOptions options)
            throws DBException
    {
        return writeInternal(new WriteBatchImpl().put(key, value), options);
    }

    @Override
    public void delete(byte[] key)
            throws DBException
    {
        writeInternal(new WriteBatchImpl().delete(key), new WriteOptions());
    }

    @Override
    public Snapshot delete(byte[] key, WriteOptions options)
            throws DBException
    {
        return writeInternal(new WriteBatchImpl().delete(key), options);
    }

    @Override
    public void write(WriteBatch updates)
            throws DBException
    {
        writeInternal((WriteBatchImpl) updates, new WriteOptions());
    }

    @Override
    public Snapshot write(WriteBatch updates, WriteOptions options)
            throws DBException
    {
        return writeInternal((WriteBatchImpl) updates, options);
    }

    public Snapshot writeInternal(WriteBatchImpl updates, WriteOptions options)
            throws DBException
    {
        checkBackgroundException();
        mutex.lock();
        try {
            long sequenceEnd;
            if (updates.size() != 0) {
                makeRoomForWrite(false);

                // Get sequence numbers for this change set
                long sequenceBegin = versions.getLastSequence() + 1;
                sequenceEnd = sequenceBegin + updates.size() - 1;

                // Reserve this sequence in the version set
                versions.setLastSequence(sequenceEnd);

                // Log write
                Slice record = writeWriteBatch(updates, sequenceBegin);
                try {
                    //将更新记录到日志文件并且将日志文件刷新到磁盘
                    log.addRecord(record, options.sync());
                }
                catch (IOException e) {
                    throw Throwables.propagate(e);
                }

                // Update memtable
                //更新memTable
                updates.forEach(new InsertIntoHandler(memTable, sequenceBegin));
            }
            else {
                sequenceEnd = versions.getLastSequence();
            }

            if (options.snapshot()) {
                return new SnapshotImpl(versions.getCurrent(), sequenceEnd);
            }
            else {
                return null;
            }
        }
        finally {
            mutex.unlock();
        }
    }

    @Override
    public WriteBatch createWriteBatch()
    {
        checkBackgroundException();
        return new WriteBatchImpl();
    }

    @Override
    public SeekingIteratorAdapter iterator()
    {
        return iterator(new ReadOptions());
    }

    @Override
    public SeekingIteratorAdapter iterator(ReadOptions options)
    {
        checkBackgroundException();
        mutex.lock();
        try {
            //构建DbIterator类（包含 memTable、immmemTable、Level0、levels）
            DbIterator rawIterator = internalIterator();

            // filter any entries not visible in our snapshot
            SnapshotImpl snapshot = getSnapshot(options);
            //包装dbIterator
            SnapshotSeekingIterator snapshotIterator = new SnapshotSeekingIterator(rawIterator, snapshot, internalKeyComparator.getUserComparator());
            return new SeekingIteratorAdapter(snapshotIterator);
        }
        finally {
            mutex.unlock();
        }
    }

    SeekingIterable<InternalKey, Slice> internalIterable()
    {
        return new SeekingIterable<InternalKey, Slice>()
        {
            @Override
            public DbIterator iterator()
            {
                return internalIterator();
            }
        };
    }

    DbIterator internalIterator()
    {
        mutex.lock();
        //这里是有io的为啥需要锁
        try {
            // merge together the memTable, immutableMemTable, and tables in version set
            MemTableIterator iterator = null;
            if (immutableMemTable != null) {
                iterator = immutableMemTable.iterator();
            }
            Version current = versions.getCurrent();
            return new DbIterator(memTable.iterator(), iterator, current.getLevel0Files(), current.getLevelIterators(), internalKeyComparator);
        }
        finally {
            mutex.unlock();
        }
    }

    @Override
    public Snapshot getSnapshot()
    {
        checkBackgroundException();
        mutex.lock();
        try {
            //current和lastSequance锁住
            return new SnapshotImpl(versions.getCurrent(), versions.getLastSequence());
        }
        finally {
            mutex.unlock();
        }
    }

    private SnapshotImpl getSnapshot(ReadOptions options)
    {
        SnapshotImpl snapshot;
        if (options.snapshot() != null) {
            snapshot = (SnapshotImpl) options.snapshot();
        }
        else {
            snapshot = new SnapshotImpl(versions.getCurrent(), versions.getLastSequence());
            snapshot.close(); // To avoid holding the snapshot active..
        }
        return snapshot;
    }

    private void makeRoomForWrite(boolean force)
    {
        checkState(mutex.isHeldByCurrentThread());

        boolean allowDelay = !force;

        while (true) {
            // todo background processing system need work
//            if (!bg_error_.ok()) {
//              // Yield previous error
//              s = bg_error_;
//              break;
//            } else
            if (allowDelay && versions.numberOfFilesInLevel(0) > L0_SLOWDOWN_WRITES_TRIGGER) {
                // We are getting close to hitting a hard limit on the number of
                // L0 files.  Rather than delaying a single write by several
                // seconds when we hit the hard limit, start delaying each
                // individual write by 1ms to reduce latency variance.  Also,
                // this delay hands over some CPU to the compaction thread in
                // case it is sharing the same core as the writer.
                try {
                    mutex.unlock();
                    //占用锁线程在沉睡之前，最好释放锁
                    Thread.sleep(1);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                finally {
                    mutex.lock();
                }

                // Do not delay a single write more than once
                allowDelay = false;
            }
            else if (!force && memTable.approximateMemoryUsage() <= options.writeBufferSize()) {
                // There is room in current memtable
                break;
            }
            else if (immutableMemTable != null) {
                // We have filled up the current memtable, but the previous
                // one is still being compacted, so we wait.
                backgroundCondition.awaitUninterruptibly();
            }
            else if (versions.numberOfFilesInLevel(0) >= L0_STOP_WRITES_TRIGGER) {
                // There are too many level-0 files.
//                Log(options_.info_log, "waiting...\n");
                backgroundCondition.awaitUninterruptibly();
            }
            else {
                // Attempt to switch to a new memtable and trigger compaction of old
                checkState(versions.getPrevLogNumber() == 0);

                // close the existing log
                try {
                    log.close();
                }
                catch (IOException e) {
                    throw new RuntimeException("Unable to close log file " + log.getFile(), e);
                }

                // open a new log
                long logNumber = versions.getNextFileNumber();
                try {
                    this.log = Logs.createLogWriter(new File(databaseDir, Filename.logFileName(logNumber)), logNumber);
                }
                catch (IOException e) {
                    throw new RuntimeException("Unable to open new log file " +
                            new File(databaseDir, Filename.logFileName(logNumber)).getAbsoluteFile(), e);
                }

                // create a new mem table
                immutableMemTable = memTable;
                memTable = new MemTable(internalKeyComparator);

                // Do not force another compaction there is space available
                force = false;

                maybeScheduleCompaction();
            }
        }
    }

    public void compactMemTable()
            throws IOException
    {
        mutex.lock();
        try {
            //合并memTable需要加锁
            compactMemTableInternal();
        }
        finally {
            mutex.unlock();
        }
    }

    private void compactMemTableInternal()
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());
        if (immutableMemTable == null) {
            return;
        }

        try {
            // Save the contents of the memtable as a new Table
            VersionEdit edit = new VersionEdit();
            Version base = versions.getCurrent();
            writeLevel0Table(immutableMemTable, edit, base);

            if (shuttingDown.get()) {
                throw new DatabaseShutdownException("Database shutdown during memtable compaction");
            }

            // Replace immutable memtable with the generated Table
            edit.setPreviousLogNumber(0);
            edit.setLogNumber(log.getFileNumber());  // Earlier logs no longer needed
            versions.logAndApply(edit);

            immutableMemTable = null;

            deleteObsoleteFiles();
        }
        finally {
            backgroundCondition.signalAll();
        }
    }

    private void writeLevel0Table(MemTable mem, VersionEdit edit, Version base)
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());

        // skip empty mem table
        if (mem.isEmpty()) {
            return;
        }

        // write the memtable to a new sstable
        long fileNumber = versions.getNextFileNumber();
        pendingOutputs.add(fileNumber);
        //io过程解锁，不要占用内存资源
        mutex.unlock();
        FileMetaData meta;
        try {
            //生成SSTable，这里mem中的数据大小自己掌控，mem默认写入一个SSTable

            meta = buildTable(mem, fileNumber);
        }
        finally {
            mutex.lock();
        }
        //上面需要获取锁之后就可以进行pendingOutputs删除
        //因为这个数据结构不是线程安全的
        pendingOutputs.remove(fileNumber);

        // Note that if file size is zero, the file has been deleted and
        // should not be added to the manifest.
        int level = 0;
        if (meta != null && meta.getFileSize() > 0) {
            Slice minUserKey = meta.getSmallest().getUserKey();
            Slice maxUserKey = meta.getLargest().getUserKey();
            if (base != null) {
                level = base.pickLevelForMemTableOutput(minUserKey, maxUserKey);
            }
            edit.addFile(level, meta);
        }
    }

    private FileMetaData buildTable(SeekingIterable<InternalKey, Slice> data, long fileNumber)
            throws IOException
    {
        //ssTable的文件名
        File file = new File(databaseDir, Filename.tableFileName(fileNumber));
        try {
            InternalKey smallest = null;
            InternalKey largest = null;
            //该文件输出流管道
            FileChannel channel = new FileOutputStream(file).getChannel();
            try {
                //SSTable构建器
                TableBuilder tableBuilder = new TableBuilder(options, channel, new InternalUserComparator(internalKeyComparator));

                for (Entry<InternalKey, Slice> entry : data) {
                    // update keys
                    InternalKey key = entry.getKey();
                    if (smallest == null) {
                        smallest = key;
                    }
                    largest = key;

                    //添加自身原始数据
                    tableBuilder.add(key.encode(), entry.getValue());
                }

                //添加元数据、索引、尾部
                //索引记录着块的position+length
                tableBuilder.finish();
            }
            finally {
                try {
                    channel.force(true);
                }
                finally {
                    channel.close();
                }
            }

            if (smallest == null) {
                return null;
            }
            FileMetaData fileMetaData = new FileMetaData(fileNumber, file.length(), smallest, largest);

            // verify table can be opened
            tableCache.newIterator(fileMetaData);

            pendingOutputs.remove(fileNumber);

            return fileMetaData;

        }
        catch (IOException e) {
            file.delete();
            throw e;
        }
    }

    private void doCompactionWork(CompactionState compactionState)
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());
        checkArgument(versions.numberOfBytesInLevel(compactionState.getCompaction().getLevel()) > 0);
        checkArgument(compactionState.builder == null);
        checkArgument(compactionState.outfile == null);

        // todo track snapshots
        compactionState.smallestSnapshot = versions.getLastSequence();

        // Release mutex while we're actually doing the compaction work
        mutex.unlock();
        try {
            //这个过程可能比较久
            //因为在构建MergingIterator时，最终还是要构建TableIterator的，而构建TableIterator是需要构建Table的，
            // 当然如果缓存中有可能不需要构建Table
            //而构建Table是有io的
            //所以这里先解锁
            MergingIterator iterator = versions.makeInputIterator(compactionState.compaction);

            Slice currentUserKey = null;
            boolean hasCurrentUserKey = false;

            long lastSequenceForKey = MAX_SEQUENCE_NUMBER;
            while (iterator.hasNext() && !shuttingDown.get()) {
                // always give priority to compacting the current mem table
                //这里老是获取锁释放锁效率是不是有点低？
                mutex.lock();
                try {
                    compactMemTableInternal();
                }
                finally {
                    mutex.unlock();
                }

                //这里也有io，所以解锁了
                InternalKey key = iterator.peek().getKey();
                if (compactionState.compaction.shouldStopBefore(key) && compactionState.builder != null) {
                    finishCompactionOutputFile(compactionState);
                }

                // Handle key/value, add to state, etc.
                boolean drop = false;
                // todo if key doesn't parse (it is corrupted),
                if (false /*!ParseInternalKey(key, &ikey)*/) {
                    // do not hide error keys
                    currentUserKey = null;
                    hasCurrentUserKey = false;
                    lastSequenceForKey = MAX_SEQUENCE_NUMBER;
                }
                else {
                    if (!hasCurrentUserKey || internalKeyComparator.getUserComparator().compare(key.getUserKey(), currentUserKey) != 0) {
                        // First occurrence of this user key
                        currentUserKey = key.getUserKey();
                        hasCurrentUserKey = true;
                        lastSequenceForKey = MAX_SEQUENCE_NUMBER;
                    }

                    if (lastSequenceForKey <= compactionState.smallestSnapshot) {
                        // Hidden by an newer entry for same user key
                        drop = true; // (A)
                    }
                    else if (key.getValueType() == DELETION &&
                            key.getSequenceNumber() <= compactionState.smallestSnapshot &&
                            compactionState.compaction.isBaseLevelForKey(key.getUserKey())) {
                        // For this user key:
                        // (1) there is no data in higher levels
                        // (2) data in lower levels will have larger sequence numbers
                        // (3) data in layers that are being compacted here and have
                        //     smaller sequence numbers will be dropped in the next
                        //     few iterations of this loop (by rule (A) above).
                        // Therefore this deletion marker is obsolete and can be dropped.
                        drop = true;
                    }

                    lastSequenceForKey = key.getSequenceNumber();
                }

                if (!drop) {
                    // Open output file if necessary
                    if (compactionState.builder == null) {
                        openCompactionOutputFile(compactionState);
                    }
                    if (compactionState.builder.getEntryCount() == 0) {
                        compactionState.currentSmallest = key;
                    }
                    compactionState.currentLargest = key;
                    compactionState.builder.add(key.encode(), iterator.peek().getValue());

                    // Close output file if it is big enough
                    if (compactionState.builder.getFileSize() >=
                            compactionState.compaction.getMaxOutputFileSize()) {
                        finishCompactionOutputFile(compactionState);
                    }
                }
                iterator.next();
            }

            if (shuttingDown.get()) {
                throw new DatabaseShutdownException("DB shutdown during compaction");
            }
            if (compactionState.builder != null) {
                finishCompactionOutputFile(compactionState);
            }
        }
        finally {
            mutex.lock();
        }

        // todo port CompactionStats code

        //标记好versionEdit，然后apply到versionSet中
        installCompactionResults(compactionState);
    }

    private void openCompactionOutputFile(CompactionState compactionState)
            throws FileNotFoundException
    {
        requireNonNull(compactionState, "compactionState is null");
        checkArgument(compactionState.builder == null, "compactionState builder is not null");

        mutex.lock();
        try {
            long fileNumber = versions.getNextFileNumber();
            pendingOutputs.add(fileNumber);
            compactionState.currentFileNumber = fileNumber;
            compactionState.currentFileSize = 0;
            compactionState.currentSmallest = null;
            compactionState.currentLargest = null;

            File file = new File(databaseDir, Filename.tableFileName(fileNumber));
            compactionState.outfile = new FileOutputStream(file).getChannel();
            compactionState.builder = new TableBuilder(options, compactionState.outfile, new InternalUserComparator(internalKeyComparator));
        }
        finally {
            mutex.unlock();
        }
    }

    private void finishCompactionOutputFile(CompactionState compactionState)
            throws IOException
    {
        requireNonNull(compactionState, "compactionState is null");
        checkArgument(compactionState.outfile != null);
        checkArgument(compactionState.builder != null);

        long outputNumber = compactionState.currentFileNumber;
        checkArgument(outputNumber != 0);

        long currentEntries = compactionState.builder.getEntryCount();
        compactionState.builder.finish();

        long currentBytes = compactionState.builder.getFileSize();
        compactionState.currentFileSize = currentBytes;
        compactionState.totalBytes += currentBytes;

        FileMetaData currentFileMetaData = new FileMetaData(compactionState.currentFileNumber,
                compactionState.currentFileSize,
                compactionState.currentSmallest,
                compactionState.currentLargest);
        compactionState.outputs.add(currentFileMetaData);

        compactionState.builder = null;

        compactionState.outfile.force(true);
        compactionState.outfile.close();
        compactionState.outfile = null;

        if (currentEntries > 0) {
            // Verify that the table is usable
            tableCache.newIterator(outputNumber);
        }
    }

    private void installCompactionResults(CompactionState compact)
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());

        // Add compaction outputs
        compact.compaction.addInputDeletions(compact.compaction.getEdit());
        int level = compact.compaction.getLevel();
        for (FileMetaData output : compact.outputs) {
            compact.compaction.getEdit().addFile(level + 1, output);
            pendingOutputs.remove(output.getNumber());
        }

        try {
            versions.logAndApply(compact.compaction.getEdit());
            deleteObsoleteFiles();
        }
        catch (IOException e) {
            // Compaction failed for some reason.  Simply discard the work and try again later.

            // Discard any files we may have created during this failed compaction
            for (FileMetaData output : compact.outputs) {
                File file = new File(databaseDir, Filename.tableFileName(output.getNumber()));
                file.delete();
            }
            compact.outputs.clear();
        }
    }

    int numberOfFilesInLevel(int level)
    {
        return versions.getCurrent().numberOfFilesInLevel(level);
    }

    @Override
    public long[] getApproximateSizes(Range... ranges)
    {
        requireNonNull(ranges, "ranges is null");
        long[] sizes = new long[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            Range range = ranges[i];
            sizes[i] = getApproximateSizes(range);
        }
        return sizes;
    }

    public long getApproximateSizes(Range range)
    {
        Version v = versions.getCurrent();

        InternalKey startKey = new InternalKey(Slices.wrappedBuffer(range.start()), MAX_SEQUENCE_NUMBER, VALUE);
        InternalKey limitKey = new InternalKey(Slices.wrappedBuffer(range.limit()), MAX_SEQUENCE_NUMBER, VALUE);
        long startOffset = v.getApproximateOffsetOf(startKey);
        long limitOffset = v.getApproximateOffsetOf(limitKey);

        return (limitOffset >= startOffset ? limitOffset - startOffset : 0);
    }

    public long getMaxNextLevelOverlappingBytes()
    {
        return versions.getMaxNextLevelOverlappingBytes();
    }

    private static class CompactionState
    {
        private final Compaction compaction;

        private final List<FileMetaData> outputs = new ArrayList<>();

        private long smallestSnapshot;

        // State kept for output being generated
        private FileChannel outfile;
        private TableBuilder builder;

        // Current file being generated
        private long currentFileNumber;
        private long currentFileSize;
        private InternalKey currentSmallest;
        private InternalKey currentLargest;

        private long totalBytes;

        private CompactionState(Compaction compaction)
        {
            this.compaction = compaction;
        }

        public Compaction getCompaction()
        {
            return compaction;
        }
    }

    private static class ManualCompaction
    {
        private final int level;
        private final Slice begin;
        private final Slice end;

        private ManualCompaction(int level, Slice begin, Slice end)
        {
            this.level = level;
            this.begin = begin;
            this.end = end;
        }
    }

    private WriteBatchImpl readWriteBatch(SliceInput record, int updateSize)
            throws IOException
    {
        WriteBatchImpl writeBatch = new WriteBatchImpl();
        int entries = 0;
        while (record.isReadable()) {
            entries++;
            ValueType valueType = ValueType.getValueTypeByPersistentId(record.readByte());
            if (valueType == VALUE) {
                Slice key = readLengthPrefixedBytes(record);
                Slice value = readLengthPrefixedBytes(record);
                writeBatch.put(key, value);
            }
            else if (valueType == DELETION) {
                Slice key = readLengthPrefixedBytes(record);
                writeBatch.delete(key);
            }
            else {
                throw new IllegalStateException("Unexpected value type " + valueType);
            }
        }

        if (entries != updateSize) {
            throw new IOException(String.format("Expected %d entries in log record but found %s entries", updateSize, entries));
        }

        return writeBatch;
    }

    private Slice writeWriteBatch(WriteBatchImpl updates, long sequenceBegin)
    {
        Slice record = Slices.allocate(SIZE_OF_LONG + SIZE_OF_INT + updates.getApproximateSize());
        final SliceOutput sliceOutput = record.output();
        sliceOutput.writeLong(sequenceBegin);
        sliceOutput.writeInt(updates.size());
        updates.forEach(new Handler()
        {
            @Override
            public void put(Slice key, Slice value)
            {
                sliceOutput.writeByte(VALUE.getPersistentId());
                writeLengthPrefixedBytes(sliceOutput, key);
                writeLengthPrefixedBytes(sliceOutput, value);
            }

            @Override
            public void delete(Slice key)
            {
                sliceOutput.writeByte(DELETION.getPersistentId());
                writeLengthPrefixedBytes(sliceOutput, key);
            }
        });
        return record.slice(0, sliceOutput.size());
    }

    private static class InsertIntoHandler
            implements Handler
    {
        private long sequence;
        private final MemTable memTable;

        public InsertIntoHandler(MemTable memTable, long sequenceBegin)
        {
            this.memTable = memTable;
            this.sequence = sequenceBegin;
        }

        @Override
        public void put(Slice key, Slice value)
        {
            memTable.add(sequence++, VALUE, key, value);
        }

        @Override
        public void delete(Slice key)
        {
            memTable.add(sequence++, DELETION, key, Slices.EMPTY_SLICE);
        }
    }

    public static class DatabaseShutdownException
            extends DBException
    {
        public DatabaseShutdownException()
        {
        }

        public DatabaseShutdownException(String message)
        {
            super(message);
        }
    }

    public static class BackgroundProcessingException
            extends DBException
    {
        public BackgroundProcessingException(Throwable cause)
        {
            super(cause);
        }
    }

    private final Object suspensionMutex = new Object();
    private int suspensionCounter;

    @Override
    public void suspendCompactions()
            throws InterruptedException
    {
        compactionExecutor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    synchronized (suspensionMutex) {
                        suspensionCounter++;
                        suspensionMutex.notifyAll();
                        while (suspensionCounter > 0 && !compactionExecutor.isShutdown()) {
                            suspensionMutex.wait(500);
                        }
                    }
                }
                catch (InterruptedException e) {
                }
            }
        });
        synchronized (suspensionMutex) {
            while (suspensionCounter < 1) {
                suspensionMutex.wait();
            }
        }
    }

    @Override
    public void resumeCompactions()
    {
        synchronized (suspensionMutex) {
            suspensionCounter--;
            suspensionMutex.notifyAll();
        }
    }

    @Override
    public void compactRange(byte[] begin, byte[] end)
            throws DBException
    {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
