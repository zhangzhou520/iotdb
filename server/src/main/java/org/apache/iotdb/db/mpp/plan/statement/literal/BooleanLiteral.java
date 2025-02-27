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

package org.apache.iotdb.db.mpp.plan.statement.literal;

import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;

import java.nio.ByteBuffer;
import java.util.Objects;

public class BooleanLiteral extends Literal {
  public static final BooleanLiteral TRUE_LITERAL = new BooleanLiteral("true");
  public static final BooleanLiteral FALSE_LITERAL = new BooleanLiteral("false");

  private final boolean value;

  public BooleanLiteral(String value) {
    this.value = Boolean.parseBoolean(value);
  }

  public BooleanLiteral(boolean value) {
    this.value = value;
  }

  @Override
  public void serialize(ByteBuffer byteBuffer) {
    ReadWriteIOUtils.write(LiteralType.BOOLEAN.ordinal(), byteBuffer);
    ReadWriteIOUtils.write(value, byteBuffer);
  }

  @Override
  public boolean isDataTypeConsistency(TSDataType dataType) {
    return dataType == TSDataType.BOOLEAN;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BooleanLiteral that = (BooleanLiteral) o;
    return value == that.value;
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }

  @Override
  public boolean getBoolean() {
    return value;
  }
}
