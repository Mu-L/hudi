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

package org.apache.hudi.io.hfile;

import java.io.Closeable;
import java.io.IOException;

/**
 * The interface for HFile writer to implement.
 */
public interface HFileWriter extends Closeable {
  /**
   * Append a key-value pair into a data block.
   * The caller must guarantee that the key lexicographically increments or the same
   * as the last key.
    */
  void append(String key, byte[] value) throws IOException;

  /**
   * Append a piece of file info.
   */
  void appendFileInfo(String name, byte[] value);

  /**
   * Append a piece of meta info.
   */
  void appendMetaInfo(String name, byte[] value);
}
