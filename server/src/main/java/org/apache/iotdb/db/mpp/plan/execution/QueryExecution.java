/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.mpp.plan.execution;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.sync.SyncDataNodeInternalServiceClient;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.mpp.common.MPPQueryContext;
import org.apache.iotdb.db.mpp.common.header.DatasetHeader;
import org.apache.iotdb.db.mpp.execution.QueryState;
import org.apache.iotdb.db.mpp.execution.QueryStateMachine;
import org.apache.iotdb.db.mpp.execution.datatransfer.DataBlockService;
import org.apache.iotdb.db.mpp.execution.datatransfer.ISourceHandle;
import org.apache.iotdb.db.mpp.plan.analyze.Analysis;
import org.apache.iotdb.db.mpp.plan.analyze.Analyzer;
import org.apache.iotdb.db.mpp.plan.analyze.IPartitionFetcher;
import org.apache.iotdb.db.mpp.plan.analyze.ISchemaFetcher;
import org.apache.iotdb.db.mpp.plan.analyze.QueryType;
import org.apache.iotdb.db.mpp.plan.execution.memory.MemorySourceHandle;
import org.apache.iotdb.db.mpp.plan.execution.memory.StatementMemorySource;
import org.apache.iotdb.db.mpp.plan.execution.memory.StatementMemorySourceContext;
import org.apache.iotdb.db.mpp.plan.execution.memory.StatementMemorySourceVisitor;
import org.apache.iotdb.db.mpp.plan.optimization.PlanOptimizer;
import org.apache.iotdb.db.mpp.plan.planner.DistributionPlanner;
import org.apache.iotdb.db.mpp.plan.planner.LogicalPlanner;
import org.apache.iotdb.db.mpp.plan.planner.plan.DistributedQueryPlan;
import org.apache.iotdb.db.mpp.plan.planner.plan.LogicalQueryPlan;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeUtil;
import org.apache.iotdb.db.mpp.plan.scheduler.ClusterScheduler;
import org.apache.iotdb.db.mpp.plan.scheduler.IScheduler;
import org.apache.iotdb.db.mpp.plan.scheduler.StandaloneScheduler;
import org.apache.iotdb.db.mpp.plan.statement.Statement;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.tsfile.read.common.block.TsBlock;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Throwables.throwIfUnchecked;

/**
 * QueryExecution stores all the status of a query which is being prepared or running inside the MPP
 * frame. It takes three main responsibilities: 1. Prepare a query. Transform a query from statement
 * to DistributedQueryPlan with fragment instances. 2. Dispatch all the fragment instances to
 * corresponding physical nodes. 3. Collect and monitor the progress/states of this query.
 */
public class QueryExecution implements IQueryExecution {
  private static final Logger logger = LoggerFactory.getLogger(QueryExecution.class);

  private static IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  private final MPPQueryContext context;
  private IScheduler scheduler;
  private final QueryStateMachine stateMachine;

  private final List<PlanOptimizer> planOptimizers;

  private final Analysis analysis;
  private LogicalQueryPlan logicalPlan;
  private DistributedQueryPlan distributedPlan;

  private final ExecutorService executor;
  private final ExecutorService writeOperationExecutor;
  private final ScheduledExecutorService scheduledExecutor;
  // TODO need to use factory to decide standalone or cluster
  private final IPartitionFetcher partitionFetcher;
  // TODO need to use factory to decide standalone or cluster,
  private final ISchemaFetcher schemaFetcher;

  // The result of QueryExecution will be written to the DataBlockManager in current Node.
  // We use this SourceHandle to fetch the TsBlock from it.
  private ISourceHandle resultHandle;

  private final IClientManager<TEndPoint, SyncDataNodeInternalServiceClient>
      internalServiceClientManager;

  public QueryExecution(
      Statement statement,
      MPPQueryContext context,
      ExecutorService executor,
      ExecutorService writeOperationExecutor,
      ScheduledExecutorService scheduledExecutor,
      IPartitionFetcher partitionFetcher,
      ISchemaFetcher schemaFetcher,
      IClientManager<TEndPoint, SyncDataNodeInternalServiceClient> internalServiceClientManager) {
    this.executor = executor;
    this.writeOperationExecutor = writeOperationExecutor;
    this.scheduledExecutor = scheduledExecutor;
    this.context = context;
    this.planOptimizers = new ArrayList<>();
    this.analysis = analyze(statement, context, partitionFetcher, schemaFetcher);
    this.stateMachine = new QueryStateMachine(context.getQueryId(), executor);
    this.partitionFetcher = partitionFetcher;
    this.schemaFetcher = schemaFetcher;
    this.internalServiceClientManager = internalServiceClientManager;

    // We add the abort logic inside the QueryExecution.
    // So that the other components can only focus on the state change.
    stateMachine.addStateChangeListener(
        state -> {
          if (!state.isDone()) {
            return;
          }
          this.stop();
          // TODO: (xingtanzjr) If the query is in abnormal state, the releaseResource() should be
          // invoked
          if (state == QueryState.FAILED
              || state == QueryState.ABORTED
              || state == QueryState.CANCELED) {
            releaseResource();
          }
        });
  }

  public void start() {
    if (skipExecute()) {
      logger.info(
          "{} execution of query will be skipped. Transit to RUNNING immediately.", getLogHeader());
      constructResultForMemorySource();
      stateMachine.transitionToRunning();
      return;
    }
    doLogicalPlan();
    doDistributedPlan();
    if (context.getQueryType() == QueryType.READ) {
      initResultHandle();
    }
    schedule();
  }

  private boolean skipExecute() {
    return analysis.isFinishQueryAfterAnalyze()
        || (context.getQueryType() == QueryType.READ && !analysis.hasDataSource());
  }

  private void constructResultForMemorySource() {
    StatementMemorySource memorySource =
        new StatementMemorySourceVisitor()
            .process(analysis.getStatement(), new StatementMemorySourceContext(context, analysis));
    this.resultHandle = new MemorySourceHandle(memorySource.getTsBlock());
    this.analysis.setRespDatasetHeader(memorySource.getDatasetHeader());
  }

  // Analyze the statement in QueryContext. Generate the analysis this query need
  private Analysis analyze(
      Statement statement,
      MPPQueryContext context,
      IPartitionFetcher partitionFetcher,
      ISchemaFetcher schemaFetcher) {
    // initialize the variable `analysis`
    logger.info("{} start to analyze query", getLogHeader());
    return new Analyzer(context, partitionFetcher, schemaFetcher).analyze(statement);
  }

  private void schedule() {
    // TODO: (xingtanzjr) initialize the query scheduler according to configuration
    this.scheduler =
        config.isClusterMode()
            ? new ClusterScheduler(
                context,
                stateMachine,
                distributedPlan.getInstances(),
                context.getQueryType(),
                executor,
                writeOperationExecutor,
                scheduledExecutor,
                internalServiceClientManager)
            : new StandaloneScheduler(
                context,
                stateMachine,
                distributedPlan.getInstances(),
                context.getQueryType(),
                executor,
                scheduledExecutor,
                internalServiceClientManager);
    this.scheduler.start();
  }

  // Use LogicalPlanner to do the logical query plan and logical optimization
  public void doLogicalPlan() {
    logger.info("{} do logical plan...", getLogHeader());
    LogicalPlanner planner = new LogicalPlanner(this.context, this.planOptimizers);
    this.logicalPlan = planner.plan(this.analysis);
    logger.info(
        "{} logical plan is: \n {}",
        getLogHeader(),
        PlanNodeUtil.nodeToString(this.logicalPlan.getRootNode()));
  }

  // Generate the distributed plan and split it into fragments
  public void doDistributedPlan() {
    logger.info("{} do distribution plan...", getLogHeader());
    DistributionPlanner planner = new DistributionPlanner(this.analysis, this.logicalPlan);
    this.distributedPlan = planner.planFragments();
    logger.info(
        "{} distribution plan done. Fragment instance count is {}, details is: \n {}",
        getLogHeader(),
        distributedPlan.getInstances().size(),
        distributedPlan.getInstances());
  }

  // Stop the workers for this query
  public void stop() {
    if (this.scheduler != null) {
      this.scheduler.stop();
    }
  }

  // Stop the query and clean up all the resources this query occupied
  public void stopAndCleanup() {
    stop();
    releaseResource();
  }

  /** Release the resources that current QueryExecution hold. */
  private void releaseResource() {
    // close ResultHandle to unblock client's getResult request
    // Actually, we should not close the ResultHandle when the QueryExecution is Finished.
    // There are only two scenarios where the ResultHandle should be closed:
    //   1. The client fetch all the result and the ResultHandle is finished.
    //   2. The client's connection is closed that all owned QueryExecution should be cleaned up
    // If the QueryExecution's state is abnormal, we should also abort the resultHandle without
    // waiting it to be finished.
    if (resultHandle != null) {
      resultHandle.abort();
    }
  }

  /**
   * This method will be called by the request thread from client connection. This method will block
   * until one of these conditions occurs: 1. There is a batch of result 2. There is no more result
   * 3. The query has been cancelled 4. The query is timeout This method will fetch the result from
   * DataStreamManager use the virtual ResultOperator's ID (This part will be designed and
   * implemented with DataStreamManager)
   */
  @Override
  public Optional<TsBlock> getBatchResult() {
    try {
      if (resultHandle == null || resultHandle.isAborted() || resultHandle.isFinished()) {
        // Once the resultHandle is finished, we should transit the state of this query to FINISHED.
        // So that the corresponding cleanup work could be triggered.
        logger.info("{} resultHandle for client is finished", getLogHeader());
        stateMachine.transitionToFinished();
        return Optional.empty();
      }
      ListenableFuture<Void> blocked = resultHandle.isBlocked();
      blocked.get();
      return Optional.of(resultHandle.receive());
    } catch (ExecutionException | CancellationException e) {
      stateMachine.transitionToFailed(e);
      throwIfUnchecked(e.getCause());
      throw new RuntimeException(e.getCause());
    } catch (InterruptedException e) {
      stateMachine.transitionToFailed(e);
      Thread.currentThread().interrupt();
      throw new RuntimeException(new SQLException("ResultSet thread was interrupted", e));
    }
  }

  /** @return true if there is more tsblocks, otherwise false */
  @Override
  public boolean hasNextResult() {
    return resultHandle != null && !resultHandle.isFinished();
  }

  /** return the result column count without the time column */
  @Override
  public int getOutputValueColumnCount() {
    return analysis.getRespDatasetHeader().getOutputValueColumnCount();
  }

  @Override
  public DatasetHeader getDatasetHeader() {
    return analysis.getRespDatasetHeader();
  }

  /**
   * This method is a synchronized method. For READ, it will block until all the FragmentInstances
   * have been submitted. For WRITE, it will block until all the FragmentInstances have finished.
   *
   * @return ExecutionStatus. Contains the QueryId and the TSStatus.
   */
  public ExecutionResult getStatus() {
    // Although we monitor the state to transition to RUNNING, the future will return if any
    // Terminated state is triggered
    SettableFuture<QueryState> future = SettableFuture.create();
    stateMachine.addStateChangeListener(
        state -> {
          if (state == QueryState.RUNNING || state.isDone()) {
            future.set(state);
          }
        });

    try {
      QueryState state = future.get();
      // TODO: (xingtanzjr) use more TSStatusCode if the QueryState isn't FINISHED
      TSStatusCode statusCode =
          // For WRITE, the state should be FINISHED; For READ, the state could be RUNNING
          state == QueryState.FINISHED || state == QueryState.RUNNING
              ? TSStatusCode.SUCCESS_STATUS
              : TSStatusCode.QUERY_PROCESS_ERROR;
      return new ExecutionResult(
          context.getQueryId(), RpcUtils.getStatus(statusCode, stateMachine.getFailureMessage()));
    } catch (InterruptedException | ExecutionException e) {
      // TODO: (xingtanzjr) use more accurate error handling
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new ExecutionResult(
          context.getQueryId(),
          RpcUtils.getStatus(TSStatusCode.INTERNAL_SERVER_ERROR, stateMachine.getFailureMessage()));
    }
  }

  private void initResultHandle() {
    if (this.resultHandle == null) {
      this.resultHandle =
          DataBlockService.getInstance()
              .getDataBlockManager()
              .createSourceHandle(
                  context.getResultNodeContext().getVirtualFragmentInstanceId().toThrift(),
                  context.getResultNodeContext().getVirtualResultNodeId().getId(),
                  context.getResultNodeContext().getUpStreamEndpoint(),
                  context.getResultNodeContext().getVirtualFragmentInstanceId().toThrift(),
                  stateMachine::transitionToFailed);
    }
  }

  public DistributedQueryPlan getDistributedPlan() {
    return distributedPlan;
  }

  public LogicalQueryPlan getLogicalPlan() {
    return logicalPlan;
  }

  @Override
  public boolean isQuery() {
    return context.getQueryType() == QueryType.READ;
  }

  public String toString() {
    return String.format("QueryExecution[%s]", context.getQueryId());
  }

  private String getLogHeader() {
    return String.format("Query[%s]:", context.getQueryId());
  }
}
