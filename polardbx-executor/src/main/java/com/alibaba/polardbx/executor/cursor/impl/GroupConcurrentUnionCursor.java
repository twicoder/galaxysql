/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.cursor.impl;

import com.alibaba.polardbx.common.ddl.newengine.DdlConstants;
import com.alibaba.polardbx.common.exception.TddlException;
import com.alibaba.polardbx.common.properties.ConnectionParams;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.executor.common.ExecutorContext;
import com.alibaba.polardbx.executor.cursor.AbstractCursor;
import com.alibaba.polardbx.executor.cursor.Cursor;
import com.alibaba.polardbx.executor.ddl.newengine.cross.CrossEngineValidator;
import com.alibaba.polardbx.executor.ddl.newengine.cross.GenericPhyObjectRecorder;
import com.alibaba.polardbx.executor.utils.ExecUtils;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.BaseQueryOperation;
import com.alibaba.polardbx.optimizer.core.rel.PhyDdlTableOperation;
import com.alibaba.polardbx.optimizer.core.row.Row;
import com.alibaba.polardbx.statistics.RuntimeStatHelper;
import org.apache.calcite.rel.RelNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by chuanqin on 18/6/21.
 */
public class GroupConcurrentUnionCursor extends AbstractCursor {

    private Cursor currentCursor;
    private int currentIndex = 0;
    private List<RelNode> subNodes;
    private List<Cursor> cursors = new ArrayList<>();
    private final ExecutionContext executionContext;
    private BlockingQueue<Future<Cursor>> completedCursorQueue;
    private List<Throwable> exceptionsWhenCloseSubCursor = new ArrayList<>();
    private String schemaName;

    private final int totalSize;
    private AtomicInteger numObjectsDone = new AtomicInteger(0);
    private AtomicInteger numObjectsSkipped = new AtomicInteger(0);

    private final boolean isDDL;
    private int prefetch;
    private int fetchIndex = 0;

    public GroupConcurrentUnionCursor(String schemaName, List<RelNode> subNodes, ExecutionContext executionContext) {
        super(false);
        this.subNodes = subNodes;
        this.totalSize = subNodes.size();
        this.executionContext = executionContext;
        this.returnColumns = ((BaseQueryOperation) subNodes.get(0)).getCursorMeta().getColumns();
        this.schemaName = schemaName;
        this.isDDL = subNodes.get(0) instanceof PhyDdlTableOperation;

        if (isDDL) {
            //DDL还是去全量下发，不改变原有逻辑
            prefetch = totalSize;
        } else {
            prefetch = executionContext.getParamManager().getInt(ConnectionParams.PREFETCH_SHARDS);
            if (prefetch < 0) {
                prefetch = ExecUtils.getPrefetchNumForLogicalView(totalSize);
            }
        }
    }

    @Override
    public void doInit() {
        if (this.inited) {
            return;
        }
        currentIndex = 0;
        // 如果下推节点只有一个, 并行则无意义
        if (totalSize == 1) {
            RelNode singleNode = subNodes.get(0);

            GenericPhyObjectRecorder phyObjectRecorder =
                CrossEngineValidator.getPhyObjectRecorder(singleNode, executionContext);

            if (!phyObjectRecorder.checkIfDone()) {
                try {
                    Cursor cursor = ExecutorContext.getContext(schemaName)
                        .getTopologyExecutor()
                        .execByExecPlanNode(singleNode, executionContext);
                    cursors.add(cursor);
                    if (CrossEngineValidator.isDDLSupported(executionContext)) {
                        numObjectsDone.incrementAndGet();
                    }
                    phyObjectRecorder.recordDone();
                } catch (Throwable t) {
                    if (!phyObjectRecorder.checkIfIgnoreException(t)) {
                        throw GeneralUtil.nestedException(t);
                    }
                }
            } else {
                numObjectsSkipped.incrementAndGet();
            }

            if (numObjectsSkipped.get() == totalSize) {
                super.init();
                return;
            }

            if (cursors.size() > 0) {
                currentCursor = cursors.get(currentIndex);
            }
        } else if (executionContext.getParamManager().getBoolean(ConnectionParams.BLOCK_CONCURRENT)) {
            List<Future<Cursor>> futures = new ArrayList<>(subNodes.size());
            for (RelNode subNode : subNodes) {
                if (!CrossEngineValidator.getPhyObjectRecorder(subNode, executionContext).checkIfDone()) {
                    Future<Cursor> rcfuture = ExecutorContext.getContext(schemaName)
                        .getTopologyExecutor()
                        .execByExecPlanNodeFuture(subNode, executionContext, null);
                    futures.add(rcfuture);
                } else {
                    numObjectsSkipped.incrementAndGet();
                }
            }

            if (numObjectsSkipped.get() == totalSize) {
                super.init();
                return;
            }

            List<Throwable> exs = new ArrayList<>();
            Throwable ex = null;
            for (int i = 0; i < futures.size(); i++) {
                GenericPhyObjectRecorder phyObjectRecorder =
                    CrossEngineValidator.getPhyObjectRecorder(subNodes.get(i), executionContext);
                try {
                    cursors.add(futures.get(i).get());
                    if (CrossEngineValidator.isDDLSupported(executionContext)) {
                        numObjectsDone.incrementAndGet();
                    }
                    phyObjectRecorder.recordDone();
                } catch (Exception e) {
                    ex = e;
                    if (!phyObjectRecorder.checkIfIgnoreException(e)) {
                        exs.add(new TddlException(e));
                    }
                }
            }

            if (!GeneralUtil.isEmpty(exs)) {
                throw GeneralUtil.mergeException(exs);
            }

            if (numObjectsSkipped.get() == 0 && cursors.isEmpty()) {
                throw GeneralUtil.nestedException(ex);
            }

            if (cursors.size() > 0) {
                currentCursor = cursors.get(currentIndex);
            }
        } else {
            completedCursorQueue = new LinkedBlockingQueue<>(subNodes.size());

            if (isDDL) {
                for (RelNode subNode : subNodes) {
                    if (!CrossEngineValidator.getPhyObjectRecorder(subNode, executionContext).checkIfDone()) {
                        FutureCursor cursor = new FutureCursor(ExecutorContext.getContext(schemaName)
                            .getTopologyExecutor()
                            .execByExecPlanNodeFuture(subNode, executionContext, completedCursorQueue)
                        );
                        cursors.add(cursor);
                    } else {
                        numObjectsSkipped.incrementAndGet();
                    }
                }

                if (numObjectsSkipped.get() == totalSize) {
                    super.init();
                    return;
                }

            } else {
                //非DDL语句，走prefetch的逻辑
                prefetchCursor();
            }

            RelNode relNode = null;
            try {
                long startWaitNano = System.nanoTime();
                Future<Cursor> future = completedCursorQueue.take();
                currentCursor = future.get();

                RuntimeStatHelper.statWaitLockTimecost(targetPlanStatGroup, startWaitNano);
                if (CrossEngineValidator.isDDLSupported(executionContext)) {
                    numObjectsDone.incrementAndGet();

                    if (currentCursor instanceof PhyDdlTableCursor) {
                        PhyDdlTableCursor phyDdlTableCursor = (PhyDdlTableCursor) currentCursor;
                        if (phyDdlTableCursor.getRelNode() instanceof PhyDdlTableOperation) {
                            relNode = phyDdlTableCursor.getRelNode();
                            CrossEngineValidator.getPhyObjectRecorder(relNode, executionContext).recordDone();
                        }
                    }
                }
            } catch (Throwable e) {
                if (!CrossEngineValidator.getPhyObjectRecorder(relNode, executionContext).checkIfIgnoreException(e)) {
                    throw GeneralUtil.nestedException(e);
                }
            }
        }
        super.doInit();

        // save the returnColumns
        if (this.cursors != null && !cursors.isEmpty()) {
            this.returnColumns = cursors.get(0).getReturnColumns();
        }

        RuntimeStatHelper.registerCursorStatByParentCursor(executionContext, this, currentCursor);
    }

    @Override
    public Row doNext() {
        init();

        Row ret;

        while (true) {
            if (CrossEngineValidator.isDDLSupported(executionContext)) {
                if ((numObjectsSkipped.get() + numObjectsDone.get() == totalSize)
                    && currentIndex >= numObjectsDone.get()) {
                    return null;
                }
            } else if (currentIndex >= (isDDL ? cursors.size() : totalSize)) {
                // 取尽所有cursor
                return null;
            }

            if (currentCursor == null && completedCursorQueue != null) {
                RelNode relNode = null;
                try {
                    long startWaitLockNano = System.nanoTime();
                    Future<Cursor> future;
                    if (CrossEngineValidator.isDDLSupported(executionContext)) {
                        future = completedCursorQueue.poll(DdlConstants.MEDIAN_WAITING_TIME, TimeUnit.MILLISECONDS);
                        if (future == null) {
                            // Try again
                            continue;
                        }
                    } else {
                        future = completedCursorQueue.take();
                    }

                    currentCursor = future.get();

                    RuntimeStatHelper.statWaitLockTimecost(targetPlanStatGroup, startWaitLockNano);
                    RuntimeStatHelper.registerCursorStatByParentCursor(executionContext, this, currentCursor);

                    if (CrossEngineValidator.isDDLSupported(executionContext)) {
                        numObjectsDone.incrementAndGet();

                        if (currentCursor instanceof PhyDdlTableCursor) {
                            PhyDdlTableCursor phyDdlTableCursor = (PhyDdlTableCursor) currentCursor;
                            if (phyDdlTableCursor.getRelNode() instanceof PhyDdlTableOperation) {
                                relNode = phyDdlTableCursor.getRelNode();
                                CrossEngineValidator.getPhyObjectRecorder(relNode, executionContext).recordDone();
                            }
                        }
                    }
                } catch (ExecutionException e) {
                    throw GeneralUtil.nestedException(e.getCause());
                } catch (Throwable e) {
                    if (!CrossEngineValidator.getPhyObjectRecorder(relNode, executionContext)
                        .checkIfIgnoreException(e)) {
                        throw GeneralUtil.nestedException(e);
                    }
                }
            } else if (currentCursor == null) {
                currentCursor = cursors.get(currentIndex);
                RuntimeStatHelper.registerCursorStatByParentCursor(executionContext, this, currentCursor);
            }

            ret = currentCursor.next();
            if (ret != null) {
                return ret;
            }

            switchCursor();
        }
    }

    private void switchCursor() {
        currentCursor.close(exceptionsWhenCloseSubCursor);
        currentIndex++;
        currentCursor = null;
        if (completedCursorQueue != null && !isDDL) {
            prefetchCursor();
        }
    }

    private synchronized void prefetchCursor() {
        //控制最大持有的连接数为prefetch
        //unfinishedCursorNum是已经下发，但是还没有关闭的cursor连接数
        int unfinishedCursorNum = cursors.size() - currentIndex;
        int needFetchSize = prefetch - unfinishedCursorNum;
        while (needFetchSize > 0 && fetchIndex < totalSize) {
            FutureCursor cursor = new FutureCursor(ExecutorContext.getContext(schemaName)
                .getTopologyExecutor()
                .execByExecPlanNodeFuture(subNodes.get(fetchIndex), executionContext, completedCursorQueue)
            );
            cursors.add(cursor);
            needFetchSize--;
            fetchIndex++;
        }
    }

    @Override
    public List<Throwable> doClose(List<Throwable> exs) {
        exs.addAll(exceptionsWhenCloseSubCursor);
        for (Cursor cursor : cursors) {
            exs = cursor.close(exs);
        }

        cursors.clear();
        return exs;
    }

    public Cursor getCurrentCursor() {
        return currentCursor;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }
}
