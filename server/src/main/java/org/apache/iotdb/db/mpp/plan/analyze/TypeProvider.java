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

package org.apache.iotdb.db.mpp.plan.analyze;

import org.apache.iotdb.db.exception.sql.StatementAnalyzeException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TypeProvider {

  private final Map<String, TSDataType> typeMap;

  public TypeProvider() {
    this.typeMap = new HashMap<>();
  }

  public TypeProvider(Map<String, TSDataType> typeMap) {
    this.typeMap = typeMap;
  }

  public TSDataType getType(String path) {
    if (!typeMap.containsKey(path)) {
      throw new StatementAnalyzeException(String.format("no data type found for path: %s", path));
    }
    return typeMap.get(path);
  }

  public void setType(String path, TSDataType dataType) {
    if (typeMap.containsKey(path) && typeMap.get(path) != dataType) {
      throw new StatementAnalyzeException(
          String.format("inconsistent data type for path: %s", path));
    }
    this.typeMap.put(path, dataType);
  }

  public boolean containsTypeInfoOf(String path) {
    return typeMap.containsKey(path);
  }

  public void serialize(ByteBuffer byteBuffer) {
    ReadWriteIOUtils.write(typeMap.size(), byteBuffer);
    for (Map.Entry<String, TSDataType> entry : typeMap.entrySet()) {
      ReadWriteIOUtils.write(entry.getKey(), byteBuffer);
      ReadWriteIOUtils.write(entry.getValue().ordinal(), byteBuffer);
    }
  }

  public static TypeProvider deserialize(ByteBuffer byteBuffer) {
    int mapSize = ReadWriteIOUtils.readInt(byteBuffer);
    Map<String, TSDataType> typeMap = new HashMap<>();
    while (mapSize > 0) {
      typeMap.put(
          ReadWriteIOUtils.readString(byteBuffer),
          TSDataType.values()[ReadWriteIOUtils.readInt(byteBuffer)]);
      mapSize--;
    }
    return new TypeProvider(typeMap);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TypeProvider that = (TypeProvider) o;
    return Objects.equals(typeMap, that.typeMap);
  }

  @Override
  public int hashCode() {
    return Objects.hash(typeMap);
  }
}
