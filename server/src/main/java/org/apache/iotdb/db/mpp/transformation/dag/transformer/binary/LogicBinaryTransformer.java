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

package org.apache.iotdb.db.mpp.transformation.dag.transformer.binary;

import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.mpp.transformation.api.LayerPointReader;
import org.apache.iotdb.tsfile.exception.write.UnSupportedDataTypeException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;

import java.io.IOException;

public abstract class LogicBinaryTransformer extends BinaryTransformer {

  protected LogicBinaryTransformer(
      LayerPointReader leftPointReader, LayerPointReader rightPointReader) {
    super(leftPointReader, rightPointReader);
  }

  @Override
  protected void checkType() {
    if (leftPointReaderDataType != TSDataType.BOOLEAN
        || rightPointReaderDataType != TSDataType.BOOLEAN) {
      throw new UnSupportedDataTypeException("Unsupported data type: " + TSDataType.BOOLEAN);
    }
  }

  @Override
  protected void transformAndCache() throws QueryProcessException, IOException {
    cachedBoolean = evaluate(leftPointReader.currentBoolean(), rightPointReader.currentBoolean());
  }

  protected abstract boolean evaluate(boolean leftOperand, boolean rightOperand);

  @Override
  public TSDataType getDataType() {
    return TSDataType.BOOLEAN;
  }
}
