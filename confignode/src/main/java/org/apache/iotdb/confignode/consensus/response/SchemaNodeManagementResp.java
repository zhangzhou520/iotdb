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

package org.apache.iotdb.confignode.consensus.response;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.partition.SchemaPartition;
import org.apache.iotdb.confignode.rpc.thrift.TSchemaNodeManagementResp;
import org.apache.iotdb.consensus.common.DataSet;
import org.apache.iotdb.rpc.TSStatusCode;

import java.util.Set;

public class SchemaNodeManagementResp implements DataSet {

  private TSStatus status;

  private SchemaPartition schemaPartition;

  private Set<String> matchedNode;

  public SchemaNodeManagementResp() {
    // empty constructor
  }

  public TSStatus getStatus() {
    return status;
  }

  public void setStatus(TSStatus status) {
    this.status = status;
  }

  public void setSchemaPartition(SchemaPartition schemaPartition) {
    this.schemaPartition = schemaPartition;
  }

  public SchemaPartition getSchemaPartition() {
    return schemaPartition;
  }

  public Set<String> getMatchedNode() {
    return matchedNode;
  }

  public void setMatchedNode(Set<String> matchedNode) {
    this.matchedNode = matchedNode;
  }

  public void convertToRpcSchemaNodeManagementPartitionResp(TSchemaNodeManagementResp resp) {
    resp.setStatus(status);
    if (status.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      resp.setSchemaRegionMap(schemaPartition.getSchemaPartitionMap());
      resp.setMatchedNode(matchedNode);
    }
  }
}
