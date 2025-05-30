/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.lookup;

import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.configuration.HadoopConfigurations;
import org.apache.hudi.util.StreamerUtil;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.runtime.typeutils.InternalSerializers;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.util.FlinkRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lookup function for Hoodie dimension table.
 *
 * <p>Note: reference Flink FileSystemLookupFunction to avoid additional connector jar dependencies.
 */
public class HoodieLookupFunction extends TableFunction<RowData> {

  private static final Logger LOG = LoggerFactory.getLogger(HoodieLookupFunction.class);

  // the max number of retries before throwing exception, in case of failure to load the table
  // into cache
  private static final int MAX_RETRIES = 3;
  // interval between retries
  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(10);

  private final HoodieLookupTableReader partitionReader;
  private final RowData.FieldGetter[] lookupFieldGetters;
  private final Duration reloadInterval;
  private final TypeSerializer<RowData> serializer;
  private final RowType rowType;

  // cache for lookup data
  private transient Map<RowData, List<RowData>> cache;
  // timestamp when cache expires
  private transient long nextLoadTime;

  private transient HoodieTableMetaClient metaClient;
  private transient HoodieInstant currentCommit;
  private final Configuration conf;

  public HoodieLookupFunction(
      HoodieLookupTableReader partitionReader,
      RowType rowType,
      int[] lookupKeys,
      Duration reloadInterval,
      Configuration conf) {
    this.partitionReader = partitionReader;
    this.rowType = rowType;
    this.lookupFieldGetters = new RowData.FieldGetter[lookupKeys.length];
    for (int i = 0; i < lookupKeys.length; i++) {
      lookupFieldGetters[i] =
          RowData.createFieldGetter(rowType.getTypeAt(lookupKeys[i]), lookupKeys[i]);
    }
    this.reloadInterval = reloadInterval;
    this.serializer = InternalSerializers.create(rowType);
    this.conf = conf;
  }

  @Override
  public void open(FunctionContext context) throws Exception {
    super.open(context);
    cache = new HashMap<>();
    nextLoadTime = -1L;
    org.apache.hadoop.conf.Configuration hadoopConf = HadoopConfigurations.getHadoopConf(conf);
    metaClient = StreamerUtil.metaClientForReader(conf, hadoopConf);
  }

  @Override
  public TypeInformation<RowData> getResultType() {
    return InternalTypeInfo.of(rowType);
  }

  public void eval(Object... values) {
    checkCacheReload();
    RowData lookupKey = GenericRowData.of(values);
    List<RowData> matchedRows = cache.get(lookupKey);
    if (matchedRows != null) {
      for (RowData matchedRow : matchedRows) {
        collect(matchedRow);
      }
    }
  }

  private void checkCacheReload() {
    if (nextLoadTime > System.currentTimeMillis()) {
      return;
    }
    if (nextLoadTime > 0) {
      LOG.info(
          "Lookup join cache has expired after {} minute(s), reloading",
          reloadInterval.toMinutes());
    } else {
      LOG.info("Populating lookup join cache");
    }

    HoodieActiveTimeline latestCommit = metaClient.reloadActiveTimeline();
    Option<HoodieInstant> latestCommitInstant = latestCommit.getCommitsTimeline().lastInstant();
    if (latestCommit.empty()) {
      LOG.info("No commit instant found currently.");
      return;
    }
    // Determine whether to reload data by comparing instant
    if (latestCommitInstant.get().equals(currentCommit)) {
      LOG.info("Ignore loading data because the commit instant " + currentCommit + " has not changed.");
      return;
    }

    int numRetry = 0;
    while (true) {
      cache.clear();
      try {
        long count = 0;
        GenericRowData reuse = new GenericRowData(rowType.getFieldCount());
        partitionReader.open();
        RowData row;
        while ((row = partitionReader.read(reuse)) != null) {
          count++;
          RowData rowData = serializer.copy(row);
          RowData key = extractLookupKey(rowData);
          List<RowData> rows = cache.computeIfAbsent(key, k -> new ArrayList<>());
          rows.add(rowData);
        }
        partitionReader.close();
        nextLoadTime = System.currentTimeMillis() + reloadInterval.toMillis();
        LOG.info("Loaded {} row(s) into lookup join cache", count);
        return;
      } catch (Exception e) {
        if (numRetry >= MAX_RETRIES) {
          throw new FlinkRuntimeException(
              String.format(
                  "Failed to load table into cache after %d retries", numRetry),
              e);
        }
        numRetry++;
        long toSleep = numRetry * RETRY_INTERVAL.toMillis();
        LOG.warn(
            String.format(
                "Failed to load table into cache, will retry in %d seconds",
                toSleep / 1000),
            e);
        try {
          Thread.sleep(toSleep);
        } catch (InterruptedException ex) {
          LOG.warn("Interrupted while waiting to retry failed cache load, aborting");
          throw new FlinkRuntimeException(ex);
        }
      }
    }
  }

  private RowData extractLookupKey(RowData row) {
    GenericRowData key = new GenericRowData(lookupFieldGetters.length);
    for (int i = 0; i < lookupFieldGetters.length; i++) {
      key.setField(i, lookupFieldGetters[i].getFieldOrNull(row));
    }
    return key;
  }

  @Override
  public void close() throws Exception {
    // no operation
  }

  @VisibleForTesting
  public Duration getReloadInterval() {
    return reloadInterval;
  }
}
