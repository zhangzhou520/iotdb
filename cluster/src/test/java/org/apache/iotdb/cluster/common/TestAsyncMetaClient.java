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

package org.apache.iotdb.cluster.common;

import org.apache.iotdb.cluster.client.ClientCategory;
import org.apache.iotdb.cluster.client.async.AsyncMetaClient;
import org.apache.iotdb.cluster.rpc.thrift.Node;

import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TProtocolFactory;

import java.io.IOException;

public class TestAsyncMetaClient extends AsyncMetaClient {

  private Node node;

  public TestAsyncMetaClient(
      TProtocolFactory protocolFactory, TAsyncClientManager clientManager, Node node)
      throws IOException {
    super(protocolFactory, clientManager, node, ClientCategory.META);
    this.node = node;
  }

  @Override
  public Node getNode() {
    return node;
  }

  public void setNode(Node node) {
    this.node = node;
  }
}
