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

package org.apache.iotdb.consensus.multileader;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/** An index controller class to balance the performance degradation of frequent disk I/O. */
@ThreadSafe
public class IndexController {

  private final Logger logger = LoggerFactory.getLogger(IndexController.class);
  private static final int FLUSH_INTERVAL = 500;

  private volatile long lastFlushedIndex;
  private volatile long currentIndex;

  private final String storageDir;
  private final String prefix;
  private final boolean incrementIntervalAfterRestart;

  public IndexController(String storageDir, String prefix, boolean incrementIntervalAfterRestart) {
    this.storageDir = storageDir;
    this.prefix = prefix + '-';
    this.incrementIntervalAfterRestart = incrementIntervalAfterRestart;
    restore();
  }

  public synchronized long incrementAndGet() {
    currentIndex++;
    checkPersist();
    return currentIndex;
  }

  public synchronized long updateAndGet(int index) {
    currentIndex = Math.max(currentIndex, index);
    checkPersist();
    return currentIndex;
  }

  public long getCurrentIndex() {
    return currentIndex;
  }

  private void checkPersist() {
    if (currentIndex - lastFlushedIndex >= FLUSH_INTERVAL) {
      persist();
    }
  }

  private void persist() {
    File oldFile = new File(storageDir, prefix + lastFlushedIndex);
    File newFile = new File(storageDir, prefix + currentIndex);
    try {
      if (oldFile.exists()) {
        FileUtils.moveFile(oldFile, newFile);
      }
      logger.info(
          "Version file updated, previous: {}, current: {}",
          oldFile.getAbsolutePath(),
          newFile.getAbsolutePath());
      lastFlushedIndex = currentIndex;
    } catch (IOException e) {
      logger.error("Error occurred when flushing next version.", e);
    }
  }

  private void restore() {
    File directory = new File(storageDir);
    File[] versionFiles = directory.listFiles((dir, name) -> name.startsWith(prefix));
    File versionFile;
    if (versionFiles != null && versionFiles.length > 0) {
      long maxVersion = 0;
      int maxVersionIndex = 0;
      for (int i = 0; i < versionFiles.length; i++) {
        long fileVersion = Long.parseLong(versionFiles[i].getName().split("-")[1]);
        if (fileVersion > maxVersion) {
          maxVersion = fileVersion;
          maxVersionIndex = i;
        }
      }
      lastFlushedIndex = maxVersion;
      for (int i = 0; i < versionFiles.length; i++) {
        if (i != maxVersionIndex) {
          try {
            Files.delete(Paths.get(versionFiles[i].getName()));
          } catch (Exception e) {
            logger.error("Delete outdated version file {} failed.", versionFiles[i].getName(), e);
          }
        }
      }
    } else {
      versionFile = new File(directory, prefix + "0");
      lastFlushedIndex = 0;
      try {
        if (!versionFile.createNewFile()) {
          logger.warn("Cannot create new version file {}", versionFile);
        }
      } catch (IOException e) {
        logger.error("Error occurred when creating new file {}.", versionFile.getName(), e);
      }
    }
    if (incrementIntervalAfterRestart) {
      // prevent overlapping in case of failure
      currentIndex = lastFlushedIndex + FLUSH_INTERVAL;
      persist();
    } else {
      currentIndex = lastFlushedIndex;
    }
  }
}