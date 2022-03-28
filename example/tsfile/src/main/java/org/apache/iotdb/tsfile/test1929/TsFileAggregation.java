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
package org.apache.iotdb.tsfile.test1929;

import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Path;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;

import java.io.IOException;

public class TsFileAggregation {

  private static final String DEVICE1 = "device_1";
  public static int deviceNum;
  public static int sensorNum;
  public static int treeType; // 0=Zesong Tree, 1=B+ Tree
  public static int fileNum;

  private static final TSFileConfig config = TSFileDescriptor.getInstance().getConfig();

  public static void main(String[] args) throws IOException {
    Options opts = new Options();
    Option deviceNumOption =
        OptionBuilder.withArgName("args").withLongOpt("deviceNum").hasArg().create("d");
    opts.addOption(deviceNumOption);
    Option sensorNumOption =
        OptionBuilder.withArgName("args").withLongOpt("sensorNum").hasArg().create("m");
    opts.addOption(sensorNumOption);
    Option fileNumOption =
        OptionBuilder.withArgName("args").withLongOpt("fileNum").hasArg().create("f");
    opts.addOption(fileNumOption);
    Option treeTypeOption =
        OptionBuilder.withArgName("args").withLongOpt("treeType").hasArg().create("t");
    opts.addOption(treeTypeOption);
    Option degreeOption =
        OptionBuilder.withArgName("args").withLongOpt("degree").hasArg().create("c");
    opts.addOption(degreeOption);

    BasicParser parser = new BasicParser();
    CommandLine cl;
    try {
      cl = parser.parse(opts, args);
      deviceNum = Integer.parseInt(cl.getOptionValue("d"));
      sensorNum = Integer.parseInt(cl.getOptionValue("m"));
      fileNum = Integer.parseInt(cl.getOptionValue("f"));
      treeType = 1; // Integer.parseInt(cl.getOptionValue("t"));
      config.setMaxDegreeOfIndexNode(Integer.parseInt(cl.getOptionValue("c")));
    } catch (Exception e) {
      e.printStackTrace();
    }

    long totalStartTime = System.nanoTime();
    for (int fileIndex = 0; fileIndex < fileNum; fileIndex++) {
      // file path
      String path =
          "/data/szs/data/data/sequence/root.b/"
              + config.getMaxDegreeOfIndexNode()
              + "/"
              + deviceNum
              + "."
              + sensorNum
              + "/test"
              + fileIndex
              + ".tsfile";

      // aggregation query
      try (TsFileSequenceReader reader = new TsFileSequenceReader(path, false)) {
        Path seriesPath = new Path(DEVICE1, "sensor_1");
        TimeseriesMetadata timeseriesMetadata = null;
        if (treeType == 0) {
          timeseriesMetadata = reader.readTimeseriesMetadataV4(seriesPath, false);
        } else if (treeType == 1) {
          timeseriesMetadata = reader.readTimeseriesMetadataV5(seriesPath);
        } else if (treeType == 2) {
          timeseriesMetadata = reader.readTimeseriesMetadataHash(seriesPath);
        }
        long count = timeseriesMetadata.getStatistics().getCount();
      }
    }
    long totalTime = (System.nanoTime() - totalStartTime) / 1000_000;
    System.out.println("Average cost time: " + (double) totalTime / (double) fileNum + "ms");
  }
}