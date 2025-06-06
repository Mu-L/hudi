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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.client.transaction.lock.models;

public enum LockGetResult {
  // Lock file does not exist with code 0
  NOT_EXISTS(0),
  // Successfully retrieved the lock file with code 1
  SUCCESS(1),
  // Unable to determine lock state due to transient errors with code 2
  UNKNOWN_ERROR(2);

  private final int code;

  LockGetResult(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}