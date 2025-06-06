/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.io.storage;

import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.io.compress.CompressionCodec;
import org.apache.hudi.storage.StorageConfiguration;

public class HoodieHFileConfig {
  private final CompressionCodec compressionCodec;
  private final int blockSize;
  private final long maxFileSize;
  private final StorageConfiguration storageConf;
  private final BloomFilter bloomFilter;
  private final String keyFieldName;

  public HoodieHFileConfig(StorageConfiguration storageConf,
                           CompressionCodec compressionCodec,
                           int blockSize,
                           long maxFileSize,
                           String keyFieldName,
                           BloomFilter bloomFilter) {
    this.storageConf = storageConf;
    this.compressionCodec = compressionCodec;
    this.blockSize = blockSize;
    this.maxFileSize = maxFileSize;
    this.bloomFilter = bloomFilter;
    this.keyFieldName = keyFieldName;
  }

  public StorageConfiguration getStorageConf() {
    return storageConf;
  }

  public CompressionCodec getCompressionCodec() {
    return compressionCodec;
  }

  public int getBlockSize() {
    return blockSize;
  }

  public long getMaxFileSize() {
    return maxFileSize;
  }

  public boolean useBloomFilter() {
    return bloomFilter != null;
  }

  public BloomFilter getBloomFilter() {
    return bloomFilter;
  }

  public String getKeyFieldName() {
    return keyFieldName;
  }
}
