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

package org.apache.iotdb.db.queryengine.plan.relational.analyzer;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TConsensusGroupType;
import org.apache.iotdb.common.rpc.thrift.TDataNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.common.rpc.thrift.TSeriesPartitionSlot;
import org.apache.iotdb.common.rpc.thrift.TTimePartitionSlot;
import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.partition.DataPartition;
import org.apache.iotdb.commons.partition.DataPartitionQueryParam;
import org.apache.iotdb.commons.partition.executor.SeriesPartitionExecutor;
import org.apache.iotdb.db.protocol.session.IClientSession;
import org.apache.iotdb.db.queryengine.common.MPPQueryContext;
import org.apache.iotdb.db.queryengine.common.QueryId;
import org.apache.iotdb.db.queryengine.common.SessionInfo;
import org.apache.iotdb.db.queryengine.execution.warnings.WarningCollector;
import org.apache.iotdb.db.queryengine.plan.planner.plan.DistributedQueryPlan;
import org.apache.iotdb.db.queryengine.plan.planner.plan.LogicalQueryPlan;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.write.RelationalInsertTabletNode;
import org.apache.iotdb.db.queryengine.plan.relational.function.OperatorType;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ColumnHandle;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ColumnSchema;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ITableDeviceSchemaValidation;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.Metadata;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.OperatorNotFoundException;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.QualifiedObjectName;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.TableHandle;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.TableSchema;
import org.apache.iotdb.db.queryengine.plan.relational.planner.LogicalPlanner;
import org.apache.iotdb.db.queryengine.plan.relational.planner.distribute.TableDistributionPlanner;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.FilterNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.LimitNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.OffsetNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.OutputNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.ProjectNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.TableScanNode;
import org.apache.iotdb.db.queryengine.plan.relational.security.AccessControl;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.LogicalExpression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Statement;
import org.apache.iotdb.db.queryengine.plan.relational.sql.parser.SqlParser;
import org.apache.iotdb.db.queryengine.plan.statement.StatementTestUtils;
import org.apache.iotdb.db.queryengine.plan.statement.crud.InsertTabletStatement;

import org.apache.tsfile.file.metadata.IDeviceID.Factory;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.iotdb.db.queryengine.execution.warnings.WarningCollector.NOOP;
import static org.apache.iotdb.db.queryengine.plan.statement.component.Ordering.ASC;
import static org.apache.tsfile.read.common.type.BooleanType.BOOLEAN;
import static org.apache.tsfile.read.common.type.DoubleType.DOUBLE;
import static org.apache.tsfile.read.common.type.IntType.INT32;
import static org.apache.tsfile.read.common.type.LongType.INT64;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;

public class AnalyzerTest {

  private static final NopAccessControl nopAccessControl = new NopAccessControl();

  QueryId queryId = new QueryId("tmp_query");
  SessionInfo sessionInfo =
      new SessionInfo(
          1L,
          "iotdb-user",
          ZoneId.systemDefault(),
          IoTDBConstant.ClientVersion.V_1_0,
          "db",
          IClientSession.SqlDialect.TABLE);
  Metadata metadata = new TestMatadata();
  String sql;
  Analysis actualAnalysis;
  MPPQueryContext context;
  LogicalPlanner logicalPlanner;
  LogicalQueryPlan logicalQueryPlan;
  PlanNode rootNode;
  TableDistributionPlanner distributionPlanner;
  DistributedQueryPlan distributedQueryPlan;
  TableScanNode tableScanNode;

  @Test
  public void testMockQuery() throws OperatorNotFoundException {
    String sql =
        "SELECT s1, (s1 + 1) as t from table1 where time > 100 and s2 > 10 offset 2 limit 3";
    Metadata metadata = Mockito.mock(Metadata.class);
    Mockito.when(metadata.tableExists(Mockito.any())).thenReturn(true);

    TableHandle tableHandle = Mockito.mock(TableHandle.class);

    Map<String, ColumnHandle> map = new HashMap<>();
    TableSchema tableSchema = Mockito.mock(TableSchema.class);
    Mockito.when(tableSchema.getTableName()).thenReturn("table1");
    ColumnSchema column1 =
        ColumnSchema.builder().setName("time").setType(INT64).setHidden(false).build();
    ColumnHandle column1Handle = Mockito.mock(ColumnHandle.class);
    map.put("time", column1Handle);
    ColumnSchema column2 =
        ColumnSchema.builder().setName("s1").setType(INT32).setHidden(false).build();
    ColumnHandle column2Handle = Mockito.mock(ColumnHandle.class);
    map.put("s1", column2Handle);
    ColumnSchema column3 =
        ColumnSchema.builder().setName("s2").setType(INT64).setHidden(false).build();
    ColumnHandle column3Handle = Mockito.mock(ColumnHandle.class);
    map.put("s2", column3Handle);
    List<ColumnSchema> columnSchemaList = Arrays.asList(column1, column2, column3);
    Mockito.when(tableSchema.getColumns()).thenReturn(columnSchemaList);

    Mockito.when(
            metadata.getTableSchema(Mockito.any(), eq(new QualifiedObjectName("testdb", "table1"))))
        .thenReturn(Optional.of(tableSchema));

    //    ResolvedFunction lLessThanI =
    //        new ResolvedFunction(
    //            new BoundSignature("l<i", BOOLEAN, Arrays.asList(INT64, INT32)),
    //            new FunctionId("l<i"),
    //            FunctionKind.SCALAR,
    //            true);
    //
    //    ResolvedFunction iAddi =
    //        new ResolvedFunction(
    //            new BoundSignature("l+i", INT64, Arrays.asList(INT32, INT32)),
    //            new FunctionId("l+i"),
    //            FunctionKind.SCALAR,
    //            true);
    //
    //    Mockito.when(
    //            metadata.resolveOperator(eq(OperatorType.LESS_THAN), eq(Arrays.asList(INT64,
    // INT32))))
    //        .thenReturn(lLessThanI);
    //    Mockito.when(metadata.resolveOperator(eq(OperatorType.ADD), eq(Arrays.asList(INT32,
    // INT32))))
    //        .thenReturn(iAddi);

    Mockito.when(
            metadata.getOperatorReturnType(
                eq(OperatorType.LESS_THAN), eq(Arrays.asList(INT64, INT32))))
        .thenReturn(BOOLEAN);
    Mockito.when(
            metadata.getOperatorReturnType(eq(OperatorType.ADD), eq(Arrays.asList(INT32, INT32))))
        .thenReturn(DOUBLE);

    Analysis actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    System.out.println(actualAnalysis.getTypes());
  }

  @Test
  public void singleTableNoFilterTest() {
    // wildcard
    sql = "SELECT * FROM testdb.table1";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());

    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(((OutputNode) rootNode).getChild() instanceof TableScanNode);
    tableScanNode = (TableScanNode) ((OutputNode) rootNode).getChild();
    assertEquals("testdb.table1", tableScanNode.getQualifiedObjectName().toString());
    assertEquals(
        Arrays.asList("time", "tag1", "tag2", "tag3", "attr1", "attr2", "s1", "s2", "s3"),
        tableScanNode.getOutputColumnNames());
    assertEquals(9, tableScanNode.getOutputSymbols().size());
    assertEquals(9, tableScanNode.getAssignments().size());
    assertEquals(1, tableScanNode.getDeviceEntries().size());
    assertEquals(5, tableScanNode.getIdAndAttributeIndexMap().size());
    assertEquals(ASC, tableScanNode.getScanOrder());

    distributionPlanner = new TableDistributionPlanner(actualAnalysis, logicalQueryPlan, context);
  }

  @Test
  public void singleTableWithFilterTest1() {
    // only global time filter
    sql = "SELECT * FROM table1 where time > 1";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(((OutputNode) rootNode).getChild() instanceof TableScanNode);
    tableScanNode = (TableScanNode) ((OutputNode) rootNode).getChild();
    assertEquals("testdb.table1", tableScanNode.getQualifiedObjectName().toString());
    assertEquals(
        Arrays.asList("time", "tag1", "tag2", "tag3", "attr1", "attr2", "s1", "s2", "s3"),
        tableScanNode.getOutputColumnNames());
    assertEquals(9, tableScanNode.getAssignments().size());
    assertEquals(1, tableScanNode.getDeviceEntries().size());
    assertEquals(5, tableScanNode.getIdAndAttributeIndexMap().size());
    assertEquals("(\"time\" > 1)", tableScanNode.getTimePredicate().get().toString());
    assertNull(tableScanNode.getPushDownPredicate());
    assertEquals(ASC, tableScanNode.getScanOrder());
    distributionPlanner = new TableDistributionPlanner(actualAnalysis, logicalQueryPlan, context);
  }

  @Test
  public void singleTableWithFilterTest2() {
    // measurement value filter, which can be pushed down to TableScanNode
    sql = "SELECT tag1, attr1, s2 FROM table1 where s1 > 1";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertNotNull(tableScanNode.getPushDownPredicate());
    assertEquals("(\"s1\" > 1)", tableScanNode.getPushDownPredicate().toString());
    assertFalse(tableScanNode.getTimePredicate().isPresent());
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1", "s2"), tableScanNode.getOutputColumnNames());
  }

  @Test
  public void singleTableWithFilterTest3() {
    // measurement value filter with time filter, take apart into pushDownPredicate and
    // timePredicate of TableScanNode
    sql =
        "SELECT tag1, attr1, s2 FROM table1 where s1 > 1 and s2>2 and tag1='A' and time > 1 and time < 10";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertNotNull(tableScanNode.getPushDownPredicate());
    assertEquals(
        "((\"s1\" > 1) AND (\"s2\" > 2))", tableScanNode.getPushDownPredicate().toString());
    assertTrue(tableScanNode.getTimePredicate().isPresent());
    assertEquals(
        "((\"time\" > 1) AND (\"time\" < 10))", tableScanNode.getTimePredicate().get().toString());
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1", "s2"), tableScanNode.getOutputColumnNames());
  }

  @Test
  public void singleTableWithFilterTest4() {
    // measurement value filter with time filter
    // transfer to : ((("time" > 1) OR ("s1" > 1)) AND (("time" > 1) OR ("s2" > 2)) AND (("time" >
    // 1) OR ("time" < 10)))
    sql = "SELECT tag1, attr1, s2 FROM table1 where time > 1 or s1 > 1 and s2 > 2 and time < 10";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertTrue(tableScanNode.getTimePredicate().isPresent());
    assertEquals(
        "((\"time\" > 1) OR (\"time\" < 10))", tableScanNode.getTimePredicate().get().toString());
    assertNotNull(tableScanNode.getPushDownPredicate());
    assertEquals(
        "(((\"time\" > 1) OR (\"s1\" > 1)) AND ((\"time\" > 1) OR (\"s2\" > 2)))",
        tableScanNode.getPushDownPredicate().toString());
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1", "s2"), tableScanNode.getOutputColumnNames());
  }

  @Test
  public void singleTableWithFilterTest5() {
    // measurement value filter with time filter
    sql = "SELECT tag1, attr1, s2 FROM table1 where time > 1 or s1 > 1 or time < 10 or s2 > 2";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertNotNull(tableScanNode.getPushDownPredicate());
    assertEquals(
        "((\"time\" > 1) OR (\"s1\" > 1) OR (\"time\" < 10) OR (\"s2\" > 2))",
        tableScanNode.getPushDownPredicate().toString());
    assertFalse(tableScanNode.getTimePredicate().isPresent());
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1", "s2"), tableScanNode.getOutputColumnNames());
  }

  @Test
  public void singleTableWithFilterTest6() {
    // value filter which can not be pushed down
    sql = "SELECT tag1, attr1, s2 FROM table1 where diff(s1) > 1";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof FilterNode);
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1", "s2"), tableScanNode.getOutputColumnNames());

    sql = "SELECT tag1, attr1, s2 FROM table1 where diff(s1) + 1 > 1";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof FilterNode);
    FilterNode filterNode = (FilterNode) rootNode.getChildren().get(0);
    assertEquals("((diff(\"s1\") + 1) > 1)", filterNode.getPredicate().toString());
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1", "s2"), tableScanNode.getOutputColumnNames());
  }

  @Ignore
  @Test
  public void singleTableWithFilterTest00() {
    // TODO(beyyes) fix the CNFs parse error
    sql =
        "SELECT tag1, attr1, s2 FROM table1 where (time > 1 and s1 > 1 or s2 < 7) or (time < 10 and s1 > 4)";
    actualAnalysis = analyzeSQL(sql, metadata);
  }

  @Test
  public void singleTableProjectTest() {
    // 1. project without filter
    sql = "SELECT tag1, attr1, s1 FROM table1";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertEquals(
        Arrays.asList("time", "tag1", "attr1", "s1"), tableScanNode.getOutputColumnNames());

    // 2. project with filter
    sql = "SELECT tag1, attr1, s1 FROM table1 WHERE tag2='A' and s2=8";
    actualAnalysis = analyzeSQL(sql, metadata);
    assertNotNull(actualAnalysis);
    assertEquals(1, actualAnalysis.getTables().size());
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    TableScanNode tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertFalse(tableScanNode.getTimePredicate().isPresent());
    assertEquals("(\"s2\" = 8)", tableScanNode.getPushDownPredicate().toString());
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertEquals(
        Arrays.asList("time", "tag1", "tag2", "attr1", "s1", "s2"),
        tableScanNode.getOutputColumnNames());

    // 3. project with filter and function
    sql =
        "SELECT s1+s3, CAST(s2 AS DOUBLE) FROM table1 WHERE REPLACE(tag1, 'low', '!')='!' AND attr2='B'";
    actualAnalysis = analyzeSQL(sql, metadata);
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof ProjectNode);
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof FilterNode);
    FilterNode filterNode = (FilterNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertEquals("(REPLACE(\"tag1\", 'low', '!') = '!')", filterNode.getPredicate().toString());
    assertTrue(
        rootNode.getChildren().get(0).getChildren().get(0).getChildren().get(0)
            instanceof TableScanNode);
    tableScanNode =
        (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0).getChildren().get(0);
    assertFalse(tableScanNode.getTimePredicate().isPresent());
    assertNull(tableScanNode.getPushDownPredicate());
    assertEquals(
        Arrays.asList("time", "tag1", "attr2", "s1", "s2", "s3"),
        tableScanNode.getOutputColumnNames());

    // 4. project with not all attributes, to test the rightness of PruneUnUsedColumns
    sql = "SELECT tag2, attr2, s2 FROM table1";
    actualAnalysis = analyzeSQL(sql, metadata);
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0);
    assertEquals(
        Arrays.asList("time", "tag2", "attr2", "s2"), tableScanNode.getOutputColumnNames());
    assertEquals(2, tableScanNode.getIdAndAttributeIndexMap().size());
  }

  @Test
  public void expressionTest() {
    // 1. is null / is not null
    sql = "SELECT * FROM table1 WHERE tag1 is not null and s1 is null";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof FilterNode);
    FilterNode filterNode = (FilterNode) rootNode.getChildren().get(0);
    assertEquals(
        "((\"tag1\" IS NOT NULL) AND (\"s1\" IS NULL))", filterNode.getPredicate().toString());
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof TableScanNode);
    TableScanNode tableScanNode =
        (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertNull(tableScanNode.getPushDownPredicate());
    assertFalse(tableScanNode.getTimePredicate().isPresent());

    // 2. like
    sql = "SELECT * FROM table1 WHERE tag1 like '%m'";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof FilterNode);
    filterNode = (FilterNode) rootNode.getChildren().get(0);
    assertEquals("(\"tag1\" LIKE '%m')", filterNode.getPredicate().toString());
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof TableScanNode);
    tableScanNode = (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertNull(tableScanNode.getPushDownPredicate());
    assertFalse(tableScanNode.getTimePredicate().isPresent());

    // 3. in / not in
    sql =
        "SELECT *, s1/2, s2+1, s2*3, s1+s2, s2%1 FROM table1 WHERE tag1 in ('A', 'B') and tag2 not in ('A', 'C')";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof ProjectNode);
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof FilterNode);
    filterNode = (FilterNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertEquals(
        "((\"tag1\" IN \"tag1\") AND (NOT (\"tag2\" IN \"tag2\")))",
        filterNode.getPredicate().toString());
    assertTrue(
        rootNode.getChildren().get(0).getChildren().get(0).getChildren().get(0)
            instanceof TableScanNode);
    tableScanNode =
        (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0).getChildren().get(0);
    assertNull(tableScanNode.getPushDownPredicate());
    assertFalse(tableScanNode.getTimePredicate().isPresent());

    // 4. not
    sql = "SELECT * FROM table1 WHERE tag1 not like '%m'";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
  }

  @Test
  public void functionTest() {
    // 1. cast
    sql = "SELECT CAST(s2 AS DOUBLE) FROM table1 WHERE CAST(s1 AS DOUBLE) > 1.0";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();

    // 2. substring
    sql =
        "SELECT SUBSTRING(tag1, 2), SUBSTRING(tag2, s1) FROM table1 WHERE SUBSTRING(tag2, 1) = 'A'";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();

    // 3. round
    sql = "SELECT ROUND(s1, 1) FROM table1 WHERE ROUND(s2, 2) > 1.0";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();

    // 4. replace
    sql = "SELECT REPLACE(tag1, 'A', 'B') FROM table1 WHERE REPLACE(attr1, 'C', 'D') = 'D'";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
  }

  @Test
  public void diffTest() {
    // 1. only diff
    sql = "SELECT DIFF(s1) FROM table1 WHERE DIFF(s2) > 0";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof ProjectNode);
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof FilterNode);
    FilterNode filterNode = (FilterNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertEquals("(DIFF(\"s2\") > 0)", filterNode.getPredicate().toString());

    // 2. diff with time filter, tag filter and measurement filter
    sql = "SELECT s1 FROM table1 WHERE DIFF(s2) > 0 and time > 5 and tag1 = 'A' and s1 = 1";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalPlanner = new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP);
    logicalQueryPlan = logicalPlanner.plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof FilterNode);
    filterNode = (FilterNode) rootNode.getChildren().get(0);
    assertEquals(
        "((DIFF(\"s2\") > 0) AND (\"time\" > 5) AND (\"tag1\" = 'A') AND (\"s1\" = 1))",
        filterNode.getPredicate().toString());
  }

  @Test
  public void predicatePushDownTest() {
    // `is null expression`, `not expression` cannot be pushed down into TableScanOperator
    sql =
        "SELECT *, s1/2, s2+1 FROM table1 WHERE tag1 in ('A', 'B') and tag2 = 'C' "
            + "and s2 iS NUll and S1 = 6 and s3 < 8.0 and tAG1 LIKE '%m'";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode instanceof OutputNode);
    assertTrue(rootNode.getChildren().get(0) instanceof ProjectNode);
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof FilterNode);
    FilterNode filterNode = (FilterNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertTrue(filterNode.getPredicate() instanceof LogicalExpression);
    assertEquals(3, ((LogicalExpression) filterNode.getPredicate()).getTerms().size());
    assertTrue(
        rootNode.getChildren().get(0).getChildren().get(0).getChildren().get(0)
            instanceof TableScanNode);
    tableScanNode =
        (TableScanNode) rootNode.getChildren().get(0).getChildren().get(0).getChildren().get(0);
    assertTrue(
        tableScanNode.getPushDownPredicate() != null
            && tableScanNode.getPushDownPredicate() instanceof LogicalExpression);
    assertEquals(2, ((LogicalExpression) tableScanNode.getPushDownPredicate()).getTerms().size());
  }

  @Test
  public void limitOffsetTest() {
    sql = "SELECT tag1, attr1, s1 FROM table1 offset 3 limit 5";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof LimitNode);
    LimitNode limitNode = (LimitNode) rootNode.getChildren().get(0);
    assertEquals(5, limitNode.getCount());
    OffsetNode offsetNode = (OffsetNode) limitNode.getChild();
    assertEquals(3, offsetNode.getCount());

    sql =
        "SELECT *, s1/2, s2+1 FROM table1 WHERE tag1 in ('A', 'B') and tag2 = 'C' "
            + "and s2 iS NUll and S1 = 6 and s3 < 8.0 and tAG1 LIKE '%m' offset 3 limit 5";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
    assertTrue(rootNode.getChildren().get(0) instanceof ProjectNode);
    assertTrue(rootNode.getChildren().get(0).getChildren().get(0) instanceof LimitNode);
    limitNode = (LimitNode) rootNode.getChildren().get(0).getChildren().get(0);
    assertEquals(5, limitNode.getCount());
    offsetNode = (OffsetNode) limitNode.getChild();
    assertEquals(3, offsetNode.getCount());
  }

  @Test
  public void sortTest() {
    // when TableScan locates multi regions, use default MergeSortNode
    sql = "SELECT * FROM table1 ";
    context = new MPPQueryContext(sql, queryId, sessionInfo, null, null);
    actualAnalysis = analyzeSQL(sql, metadata);
    logicalQueryPlan =
        new LogicalPlanner(context, metadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);
    rootNode = logicalQueryPlan.getRootNode();
  }

  @Test
  public void analyzeTablet() {

    TableSchema tableSchema = StatementTestUtils.genTableSchema();
    Metadata mockMetadata =
        new TestMatadata() {
          @Override
          public TableSchema validateTableHeaderSchema(
              String database, TableSchema schema, MPPQueryContext context) {
            assertEquals(tableSchema, schema);
            return tableSchema;
          }

          @Override
          public void validateDeviceSchema(
              ITableDeviceSchemaValidation schemaValidation, MPPQueryContext context) {
            assertEquals(sessionInfo.getDatabaseName().get(), schemaValidation.getDatabase());
            assertEquals(StatementTestUtils.tableName(), schemaValidation.getTableName());
            Object[] columns = StatementTestUtils.genColumns();
            for (int i = 0; i < schemaValidation.getDeviceIdList().size(); i++) {
              Object[] objects = schemaValidation.getDeviceIdList().get(i);
              assertEquals(objects[0].toString(), StatementTestUtils.tableName());
              assertEquals(objects[1].toString(), ((String[]) columns[0])[i]);
            }
            List<String> attributeColumnNameList = schemaValidation.getAttributeColumnNameList();
            assertEquals(Collections.singletonList("attr1"), attributeColumnNameList);
            assertEquals(1, schemaValidation.getAttributeValueList().size());
            assertArrayEquals(
                (Object[]) columns[1], schemaValidation.getAttributeValueList().get(0));
          }

          @Override
          public DataPartition getOrCreateDataPartition(
              List<DataPartitionQueryParam> dataPartitionQueryParams, String userName) {
            int seriesSlotNum = 1000;
            String partitionExecutorName =
                "org.apache.iotdb.commons.partition.executor.hash.BKDRHashExecutor";
            SeriesPartitionExecutor seriesPartitionExecutor =
                SeriesPartitionExecutor.getSeriesPartitionExecutor(
                    partitionExecutorName, seriesSlotNum);

            Map<String, Map<TSeriesPartitionSlot, Map<TTimePartitionSlot, List<TRegionReplicaSet>>>>
                dataPartitionMap = new HashMap<>();
            assertEquals(3, dataPartitionQueryParams.size());

            for (DataPartitionQueryParam dataPartitionQueryParam : dataPartitionQueryParams) {
              String databaseName = dataPartitionQueryParam.getDatabaseName();
              assertEquals(sessionInfo.getDatabaseName().get(), databaseName);

              String tableName = dataPartitionQueryParam.getDeviceID().getTableName();
              assertEquals(StatementTestUtils.tableName(), tableName);

              TSeriesPartitionSlot partitionSlot =
                  seriesPartitionExecutor.getSeriesPartitionSlot(
                      dataPartitionQueryParam.getDeviceID());
              for (TTimePartitionSlot tTimePartitionSlot :
                  dataPartitionQueryParam.getTimePartitionSlotList()) {
                dataPartitionMap
                    .computeIfAbsent(databaseName, d -> new HashMap<>())
                    .computeIfAbsent(partitionSlot, slot -> new HashMap<>())
                    .computeIfAbsent(tTimePartitionSlot, slot -> new ArrayList<>())
                    .add(
                        new TRegionReplicaSet(
                            new TConsensusGroupId(
                                TConsensusGroupType.DataRegion, partitionSlot.slotId),
                            Collections.singletonList(
                                new TDataNodeLocation(
                                    partitionSlot.slotId, null, null, null, null, null))));
              }
            }
            return new DataPartition(dataPartitionMap, partitionExecutorName, seriesSlotNum);
          }
        };

    InsertTabletStatement insertTabletStatement = StatementTestUtils.genInsertTabletStatement(true);
    context = new MPPQueryContext("", queryId, sessionInfo, null, null);
    actualAnalysis =
        analyzeStatement(
            insertTabletStatement.toRelationalStatement(context),
            mockMetadata,
            new SqlParser(),
            sessionInfo);
    logicalQueryPlan =
        new LogicalPlanner(context, mockMetadata, sessionInfo, WarningCollector.NOOP)
            .plan(actualAnalysis);

    RelationalInsertTabletNode insertTabletNode =
        (RelationalInsertTabletNode) logicalQueryPlan.getRootNode();

    assertEquals(insertTabletNode.getTableName(), StatementTestUtils.tableName());
    assertEquals(3, insertTabletNode.getRowCount());
    Object[] columns = StatementTestUtils.genColumns();
    for (int i = 0; i < insertTabletNode.getRowCount(); i++) {
      assertEquals(
          Factory.DEFAULT_FACTORY.create(
              new String[] {StatementTestUtils.tableName(), ((String[]) columns[0])[i]}),
          insertTabletNode.getDeviceID(i));
    }
    assertEquals(columns, insertTabletNode.getColumns());
    assertArrayEquals(StatementTestUtils.genTimestamps(), insertTabletNode.getTimes());

    distributionPlanner = new TableDistributionPlanner(actualAnalysis, logicalQueryPlan, context);
    distributedQueryPlan = distributionPlanner.plan();
    assertEquals(3, distributedQueryPlan.getInstances().size());
  }

  public static Analysis analyzeSQL(String sql, Metadata metadata) {
    SqlParser sqlParser = new SqlParser();
    Statement statement = sqlParser.createStatement(sql, ZoneId.systemDefault());
    SessionInfo session =
        new SessionInfo(
            0, "test", ZoneId.systemDefault(), "testdb", IClientSession.SqlDialect.TABLE);
    return analyzeStatement(statement, metadata, sqlParser, session);
  }

  public static Analysis analyzeStatement(
      Statement statement, Metadata metadata, SqlParser sqlParser, SessionInfo session) {
    try {
      StatementAnalyzerFactory statementAnalyzerFactory =
          new StatementAnalyzerFactory(metadata, sqlParser, nopAccessControl);

      Analyzer analyzer =
          new Analyzer(
              session,
              statementAnalyzerFactory,
              Collections.emptyList(),
              Collections.emptyMap(),
              NOOP);
      return analyzer.analyze(statement);
    } catch (Exception e) {
      e.printStackTrace();
      fail(statement + ", " + e.getMessage());
    }
    fail();
    return null;
  }

  private static class NopAccessControl implements AccessControl {}
}
