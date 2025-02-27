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

package org.apache.iotdb.db.mpp.plan.planner;

import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.confignode.rpc.thrift.NodeManagementType;
import org.apache.iotdb.db.metadata.path.AlignedPath;
import org.apache.iotdb.db.metadata.path.MeasurementPath;
import org.apache.iotdb.db.metadata.utils.MetaUtils;
import org.apache.iotdb.db.mpp.common.MPPQueryContext;
import org.apache.iotdb.db.mpp.common.schematree.PathPatternTree;
import org.apache.iotdb.db.mpp.plan.analyze.ExpressionAnalyzer;
import org.apache.iotdb.db.mpp.plan.analyze.TypeProvider;
import org.apache.iotdb.db.mpp.plan.expression.Expression;
import org.apache.iotdb.db.mpp.plan.expression.leaf.TimeSeriesOperand;
import org.apache.iotdb.db.mpp.plan.expression.multi.FunctionExpression;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.ChildNodesSchemaScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.ChildPathsSchemaScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.CountSchemaMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.DevicesCountNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.DevicesSchemaScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.LevelTimeSeriesCountNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.NodeManagementMemoryMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaFetchMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaFetchScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.SchemaQueryMergeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.TimeSeriesCountNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.metedata.read.TimeSeriesSchemaScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.AggregationNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.DeviceViewNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.FillNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.FilterNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.FilterNullNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.GroupByLevelNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.GroupByTimeNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.LimitNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.OffsetNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.TimeJoinNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.TransformNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.AlignedSeriesAggregationScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.AlignedSeriesScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesAggregationScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesScanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.AggregationDescriptor;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.AggregationStep;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.FillDescriptor;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.FilterNullParameter;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.GroupByLevelDescriptor;
import org.apache.iotdb.db.mpp.plan.planner.plan.parameter.GroupByTimeParameter;
import org.apache.iotdb.db.mpp.plan.statement.component.OrderBy;
import org.apache.iotdb.db.query.aggregation.AggregationType;
import org.apache.iotdb.db.utils.SchemaUtils;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;

import org.apache.commons.lang.Validate;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.iotdb.commons.conf.IoTDBConstant.MULTI_LEVEL_PATH_WILDCARD;

public class LogicalPlanBuilder {

  private PlanNode root;

  private final MPPQueryContext context;

  public LogicalPlanBuilder(MPPQueryContext context) {
    this.context = context;
  }

  public PlanNode getRoot() {
    return root;
  }

  public LogicalPlanBuilder withNewRoot(PlanNode newRoot) {
    this.root = newRoot;
    return this;
  }

  public LogicalPlanBuilder planRawDataSource(
      Set<Expression> sourceExpressions, OrderBy scanOrder, Filter timeFilter) {
    List<PlanNode> sourceNodeList = new ArrayList<>();
    List<PartialPath> selectedPaths =
        sourceExpressions.stream()
            .map(expression -> ((TimeSeriesOperand) expression).getPath())
            .collect(Collectors.toList());
    List<PartialPath> groupedPaths = MetaUtils.groupAlignedPaths(selectedPaths);
    for (PartialPath path : groupedPaths) {
      if (path instanceof MeasurementPath) { // non-aligned series
        SeriesScanNode seriesScanNode =
            new SeriesScanNode(
                context.getQueryId().genPlanNodeId(), (MeasurementPath) path, scanOrder);
        seriesScanNode.setTimeFilter(timeFilter);
        sourceNodeList.add(seriesScanNode);
      } else if (path instanceof AlignedPath) { // aligned series
        AlignedSeriesScanNode alignedSeriesScanNode =
            new AlignedSeriesScanNode(
                context.getQueryId().genPlanNodeId(), (AlignedPath) path, scanOrder);
        alignedSeriesScanNode.setTimeFilter(timeFilter);
        sourceNodeList.add(alignedSeriesScanNode);
      } else {
        throw new IllegalArgumentException("unexpected path type");
      }
    }

    this.root = convergeWithTimeJoin(sourceNodeList, scanOrder);
    return this;
  }

  public LogicalPlanBuilder planAggregationSource(
      Set<Expression> sourceExpressions,
      OrderBy scanOrder,
      Filter timeFilter,
      GroupByTimeParameter groupByTimeParameter,
      Set<Expression> aggregationExpressions,
      Map<Expression, Set<Expression>> groupByLevelExpressions,
      TypeProvider typeProvider) {
    AggregationStep curStep =
        (groupByLevelExpressions != null
                || (groupByTimeParameter != null && groupByTimeParameter.hasOverlap()))
            ? AggregationStep.PARTIAL
            : AggregationStep.SINGLE;

    List<PlanNode> sourceNodeList = new ArrayList<>();
    Map<PartialPath, List<AggregationDescriptor>> ascendingAggregations = new HashMap<>();
    Map<PartialPath, List<AggregationDescriptor>> descendingAggregations = new HashMap<>();
    for (Expression sourceExpression : sourceExpressions) {
      AggregationType aggregationFunction =
          AggregationType.valueOf(
              ((FunctionExpression) sourceExpression).getFunctionName().toUpperCase());
      AggregationDescriptor aggregationDescriptor =
          new AggregationDescriptor(
              aggregationFunction, curStep, sourceExpression.getExpressions());
      if (curStep.isOutputPartial()) {
        updateTypeProviderByPartialAggregation(aggregationDescriptor, typeProvider);
      }
      PartialPath selectPath =
          ((TimeSeriesOperand) sourceExpression.getExpressions().get(0)).getPath();
      if (SchemaUtils.isConsistentWithScanOrder(aggregationFunction, scanOrder)) {
        ascendingAggregations
            .computeIfAbsent(selectPath, key -> new ArrayList<>())
            .add(aggregationDescriptor);
      } else {
        descendingAggregations
            .computeIfAbsent(selectPath, key -> new ArrayList<>())
            .add(aggregationDescriptor);
      }
    }

    Map<PartialPath, List<AggregationDescriptor>> groupedAscendingAggregations =
        MetaUtils.groupAlignedAggregations(ascendingAggregations);
    Map<PartialPath, List<AggregationDescriptor>> groupedDescendingAggregations =
        MetaUtils.groupAlignedAggregations(descendingAggregations);
    for (Map.Entry<PartialPath, List<AggregationDescriptor>> pathAggregationsEntry :
        groupedAscendingAggregations.entrySet()) {
      sourceNodeList.add(
          createAggregationScanNode(
              pathAggregationsEntry.getKey(),
              pathAggregationsEntry.getValue(),
              scanOrder,
              groupByTimeParameter,
              timeFilter));
    }
    for (Map.Entry<PartialPath, List<AggregationDescriptor>> pathAggregationsEntry :
        groupedDescendingAggregations.entrySet()) {
      sourceNodeList.add(
          createAggregationScanNode(
              pathAggregationsEntry.getKey(),
              pathAggregationsEntry.getValue(),
              scanOrder,
              groupByTimeParameter,
              timeFilter));
    }

    if (curStep.isOutputPartial()) {
      if (groupByTimeParameter != null && groupByTimeParameter.hasOverlap()) {
        curStep =
            groupByLevelExpressions != null ? AggregationStep.INTERMEDIATE : AggregationStep.FINAL;
        this.root =
            createGroupByTimeNode(
                sourceNodeList, aggregationExpressions, groupByTimeParameter, curStep);

        if (groupByLevelExpressions != null) {
          curStep = AggregationStep.FINAL;
          this.root =
              createGroupByTLevelNode(this.root.getChildren(), groupByLevelExpressions, curStep);
        }
      } else {
        if (groupByLevelExpressions != null) {
          curStep = AggregationStep.FINAL;
          this.root = createGroupByTLevelNode(sourceNodeList, groupByLevelExpressions, curStep);
        }
      }
    } else {
      this.root = convergeWithTimeJoin(sourceNodeList, scanOrder);
    }
    return this;
  }

  private void updateTypeProviderByPartialAggregation(
      AggregationDescriptor aggregationDescriptor, TypeProvider typeProvider) {
    List<AggregationType> splitAggregations =
        SchemaUtils.splitPartialAggregation(aggregationDescriptor.getAggregationType());
    PartialPath path =
        ((TimeSeriesOperand) aggregationDescriptor.getInputExpressions().get(0)).getPath();
    for (AggregationType aggregationType : splitAggregations) {
      String functionName = aggregationType.toString().toLowerCase();
      typeProvider.setType(
          String.format("%s(%s)", functionName, path.getFullPath()),
          SchemaUtils.getSeriesTypeByPath(path, functionName));
    }
  }

  private PlanNode convergeWithTimeJoin(List<PlanNode> sourceNodes, OrderBy mergeOrder) {
    PlanNode tmpNode;
    if (sourceNodes.size() == 1) {
      tmpNode = sourceNodes.get(0);
    } else {
      tmpNode = new TimeJoinNode(context.getQueryId().genPlanNodeId(), mergeOrder, sourceNodes);
    }
    return tmpNode;
  }

  public LogicalPlanBuilder planDeviceView(
      Map<String, PlanNode> deviceNameToSourceNodesMap,
      List<String> outputColumnNames,
      Map<String, List<Integer>> deviceToMeasurementIndexesMap,
      OrderBy mergeOrder) {
    DeviceViewNode deviceViewNode =
        new DeviceViewNode(
            context.getQueryId().genPlanNodeId(),
            Arrays.asList(OrderBy.DEVICE_ASC, mergeOrder),
            outputColumnNames,
            deviceToMeasurementIndexesMap);
    for (Map.Entry<String, PlanNode> entry : deviceNameToSourceNodesMap.entrySet()) {
      String deviceName = entry.getKey();
      PlanNode subPlan = entry.getValue();
      deviceViewNode.addChildDeviceNode(deviceName, subPlan);
    }

    this.root = deviceViewNode;
    return this;
  }

  public LogicalPlanBuilder planGroupByLevel(
      Map<Expression, Set<Expression>> groupByLevelExpressions, AggregationStep curStep) {
    if (groupByLevelExpressions == null) {
      return this;
    }

    this.root =
        createGroupByTLevelNode(
            Collections.singletonList(this.getRoot()), groupByLevelExpressions, curStep);
    return this;
  }

  public LogicalPlanBuilder planAggregation(
      Set<Expression> aggregationExpressions,
      GroupByTimeParameter groupByTimeParameter,
      AggregationStep curStep,
      TypeProvider typeProvider) {
    if (aggregationExpressions == null) {
      return this;
    }

    List<AggregationDescriptor> aggregationDescriptorList =
        constructAggregationDescriptorList(aggregationExpressions, curStep);
    if (curStep.isOutputPartial()) {
      aggregationDescriptorList.forEach(
          aggregationDescriptor -> {
            updateTypeProviderByPartialAggregation(aggregationDescriptor, typeProvider);
          });
    }
    this.root =
        new AggregationNode(
            context.getQueryId().genPlanNodeId(),
            Collections.singletonList(this.getRoot()),
            aggregationDescriptorList,
            groupByTimeParameter);
    return this;
  }

  public LogicalPlanBuilder planGroupByTime(
      Set<Expression> aggregationExpressions,
      GroupByTimeParameter groupByTimeParameter,
      AggregationStep curStep) {
    if (aggregationExpressions == null) {
      return this;
    }

    this.root =
        createGroupByTimeNode(
            Collections.singletonList(this.getRoot()),
            aggregationExpressions,
            groupByTimeParameter,
            curStep);
    return this;
  }

  private PlanNode createGroupByTimeNode(
      List<PlanNode> children,
      Set<Expression> aggregationExpressions,
      GroupByTimeParameter groupByTimeParameter,
      AggregationStep curStep) {
    List<AggregationDescriptor> aggregationDescriptorList =
        constructAggregationDescriptorList(aggregationExpressions, curStep);
    return new GroupByTimeNode(
        context.getQueryId().genPlanNodeId(),
        children,
        aggregationDescriptorList,
        groupByTimeParameter);
  }

  private PlanNode createGroupByTLevelNode(
      List<PlanNode> children,
      Map<Expression, Set<Expression>> groupByLevelExpressions,
      AggregationStep curStep) {
    List<GroupByLevelDescriptor> groupByLevelDescriptors = new ArrayList<>();
    for (Expression groupedExpression : groupByLevelExpressions.keySet()) {
      AggregationType aggregationFunction =
          AggregationType.valueOf(
              ((FunctionExpression) groupedExpression).getFunctionName().toUpperCase());
      groupByLevelDescriptors.add(
          new GroupByLevelDescriptor(
              aggregationFunction,
              curStep,
              groupByLevelExpressions.get(groupedExpression).stream()
                  .map(Expression::getExpressions)
                  .flatMap(List::stream)
                  .collect(Collectors.toList()),
              groupedExpression.getExpressions().get(0)));
    }
    return new GroupByLevelNode(
        context.getQueryId().genPlanNodeId(), children, groupByLevelDescriptors);
  }

  private PlanNode createAggregationScanNode(
      PartialPath selectPath,
      List<AggregationDescriptor> aggregationDescriptorList,
      OrderBy scanOrder,
      GroupByTimeParameter groupByTimeParameter,
      Filter timeFilter) {
    if (selectPath instanceof MeasurementPath) { // non-aligned series
      SeriesAggregationScanNode seriesAggregationScanNode =
          new SeriesAggregationScanNode(
              context.getQueryId().genPlanNodeId(),
              (MeasurementPath) selectPath,
              aggregationDescriptorList,
              scanOrder,
              groupByTimeParameter);
      seriesAggregationScanNode.setTimeFilter(timeFilter);
      return seriesAggregationScanNode;
    } else if (selectPath instanceof AlignedPath) { // aligned series
      AlignedSeriesAggregationScanNode alignedSeriesAggregationScanNode =
          new AlignedSeriesAggregationScanNode(
              context.getQueryId().genPlanNodeId(),
              (AlignedPath) selectPath,
              aggregationDescriptorList,
              scanOrder,
              groupByTimeParameter);
      alignedSeriesAggregationScanNode.setTimeFilter(timeFilter);
      return alignedSeriesAggregationScanNode;
    } else {
      throw new IllegalArgumentException("unexpected path type");
    }
  }

  private List<AggregationDescriptor> constructAggregationDescriptorList(
      Set<Expression> aggregationExpressions, AggregationStep curStep) {
    return aggregationExpressions.stream()
        .map(
            expression -> {
              Validate.isTrue(expression instanceof FunctionExpression);
              AggregationType aggregationFunction =
                  AggregationType.valueOf(
                      ((FunctionExpression) expression).getFunctionName().toUpperCase());
              return new AggregationDescriptor(
                  aggregationFunction, curStep, expression.getExpressions());
            })
        .collect(Collectors.toList());
  }

  public LogicalPlanBuilder planFilterAndTransform(
      Expression queryFilter,
      Set<Expression> selectExpressions,
      boolean isGroupByTime,
      ZoneId zoneId) {
    if (queryFilter == null) {
      return this;
    }

    this.root =
        new FilterNode(
            context.getQueryId().genPlanNodeId(),
            this.getRoot(),
            selectExpressions.toArray(new Expression[0]),
            queryFilter,
            isGroupByTime,
            zoneId);
    return this;
  }

  public LogicalPlanBuilder planTransform(
      Set<Expression> selectExpressions, boolean isGroupByTime, ZoneId zoneId) {
    boolean needTransform = false;
    for (Expression expression : selectExpressions) {
      if (ExpressionAnalyzer.checkIsNeedTransform(expression)) {
        needTransform = true;
        break;
      }
    }
    if (!needTransform) {
      return this;
    }

    this.root =
        new TransformNode(
            context.getQueryId().genPlanNodeId(),
            this.getRoot(),
            selectExpressions.toArray(new Expression[0]),
            isGroupByTime,
            zoneId);
    return this;
  }

  public LogicalPlanBuilder planFilterNull(FilterNullParameter filterNullParameter) {
    if (filterNullParameter == null) {
      return this;
    }

    this.root =
        new FilterNullNode(
            context.getQueryId().genPlanNodeId(), this.getRoot(), filterNullParameter);
    return this;
  }

  public LogicalPlanBuilder planFill(FillDescriptor fillDescriptor) {
    if (fillDescriptor == null) {
      return this;
    }

    this.root = new FillNode(context.getQueryId().genPlanNodeId(), this.getRoot(), fillDescriptor);
    return this;
  }

  public LogicalPlanBuilder planLimit(int rowLimit) {
    if (rowLimit == 0) {
      return this;
    }

    this.root = new LimitNode(context.getQueryId().genPlanNodeId(), this.getRoot(), rowLimit);
    return this;
  }

  public LogicalPlanBuilder planOffset(int rowOffset) {
    if (rowOffset == 0) {
      return this;
    }

    this.root = new OffsetNode(context.getQueryId().genPlanNodeId(), this.getRoot(), rowOffset);
    return this;
  }

  /** Meta Query* */
  public LogicalPlanBuilder planTimeSeriesSchemaSource(
      PartialPath pathPattern,
      String key,
      String value,
      int limit,
      int offset,
      boolean orderByHeat,
      boolean contains,
      boolean prefixPath) {
    TimeSeriesSchemaScanNode timeSeriesMetaScanNode =
        new TimeSeriesSchemaScanNode(
            context.getQueryId().genPlanNodeId(),
            pathPattern,
            key,
            value,
            limit,
            offset,
            orderByHeat,
            contains,
            prefixPath);
    this.root = timeSeriesMetaScanNode;
    return this;
  }

  public LogicalPlanBuilder planDeviceSchemaSource(
      PartialPath pathPattern, int limit, int offset, boolean prefixPath, boolean hasSgCol) {
    this.root =
        new DevicesSchemaScanNode(
            context.getQueryId().genPlanNodeId(), pathPattern, limit, offset, prefixPath, hasSgCol);
    return this;
  }

  public LogicalPlanBuilder planSchemaQueryMerge(boolean orderByHeat) {
    SchemaQueryMergeNode schemaMergeNode =
        new SchemaQueryMergeNode(context.getQueryId().genPlanNodeId(), orderByHeat);
    schemaMergeNode.addChild(this.getRoot());
    this.root = schemaMergeNode;
    return this;
  }

  public LogicalPlanBuilder planSchemaFetchMerge() {
    this.root = new SchemaFetchMergeNode(context.getQueryId().genPlanNodeId());
    return this;
  }

  public LogicalPlanBuilder planSchemaFetchSource(
      List<String> storageGroupList, PathPatternTree patternTree) {
    PartialPath storageGroupPath;
    for (String storageGroup : storageGroupList) {
      try {
        storageGroupPath = new PartialPath(storageGroup);
        this.root.addChild(
            new SchemaFetchScanNode(
                context.getQueryId().genPlanNodeId(),
                storageGroupPath,
                patternTree.findOverlappedPattern(
                    storageGroupPath.concatNode(MULTI_LEVEL_PATH_WILDCARD))));
      } catch (IllegalPathException e) {
        // definitely won't happen
        throw new RuntimeException(e);
      }
    }
    return this;
  }

  public LogicalPlanBuilder planCountMerge() {
    CountSchemaMergeNode countMergeNode =
        new CountSchemaMergeNode(context.getQueryId().genPlanNodeId());
    countMergeNode.addChild(this.getRoot());
    this.root = countMergeNode;
    return this;
  }

  public LogicalPlanBuilder planDevicesCountSource(PartialPath partialPath, boolean prefixPath) {
    this.root = new DevicesCountNode(context.getQueryId().genPlanNodeId(), partialPath, prefixPath);
    return this;
  }

  public LogicalPlanBuilder planTimeSeriesCountSource(PartialPath partialPath, boolean prefixPath) {
    this.root =
        new TimeSeriesCountNode(context.getQueryId().genPlanNodeId(), partialPath, prefixPath);
    return this;
  }

  public LogicalPlanBuilder planLevelTimeSeriesCountSource(
      PartialPath partialPath, boolean prefixPath, int level) {
    this.root =
        new LevelTimeSeriesCountNode(
            context.getQueryId().genPlanNodeId(), partialPath, prefixPath, level);
    return this;
  }

  public LogicalPlanBuilder planChildPathsSchemaSource(PartialPath partialPath) {
    this.root = new ChildPathsSchemaScanNode(context.getQueryId().genPlanNodeId(), partialPath);
    return this;
  }

  public LogicalPlanBuilder planChildNodesSchemaSource(PartialPath partialPath) {
    this.root = new ChildNodesSchemaScanNode(context.getQueryId().genPlanNodeId(), partialPath);
    return this;
  }

  public LogicalPlanBuilder planNodeManagementMemoryMerge(
      Set<String> data, NodeManagementType type) {
    NodeManagementMemoryMergeNode memorySourceNode =
        new NodeManagementMemoryMergeNode(context.getQueryId().genPlanNodeId(), data, type);
    memorySourceNode.addChild(this.getRoot());
    this.root = memorySourceNode;
    return this;
  }
}
