/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.metadata.cache;

import org.apache.iotdb.db.metadata.lastCache.container.ILastCacheContainer;
import org.apache.iotdb.db.metadata.lastCache.container.LastCacheContainer;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

public class SchemaCacheEntry {

  private final MeasurementSchema measurementSchema;

  private final String alias;

  private final boolean isAligned;

  private volatile ILastCacheContainer lastCacheContainer = null;

  SchemaCacheEntry(MeasurementSchema measurementSchema, String alias, boolean isAligned) {
    this.measurementSchema = measurementSchema;
    this.alias = alias;
    this.isAligned = isAligned;
  }

  public String getSchemaEntryId() {
    return measurementSchema.getMeasurementId();
  }

  public MeasurementSchema getMeasurementSchema() {
    return measurementSchema;
  }

  public TSDataType getTsDataType() {
    return measurementSchema.getType();
  }

  public String getAlias() {
    return alias;
  }

  public boolean isAligned() {
    return isAligned;
  }

  public ILastCacheContainer getLastCacheContainer() {
    if (lastCacheContainer == null) {
      synchronized (this) {
        if (lastCacheContainer == null) {
          lastCacheContainer = new LastCacheContainer();
        }
      }
    }
    return lastCacheContainer;
  }

  public void setLastCacheContainer(ILastCacheContainer lastCacheContainer) {
    this.lastCacheContainer = lastCacheContainer;
  }
}
