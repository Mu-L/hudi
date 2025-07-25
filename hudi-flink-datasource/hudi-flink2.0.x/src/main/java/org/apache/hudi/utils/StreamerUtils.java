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

package org.apache.hudi.utils;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.formats.common.TimestampFormat;
import org.apache.flink.formats.json.JsonRowDataDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.RowType;
import org.apache.kafka.clients.consumer.ConsumerConfig;

import java.util.Properties;

/**
 * Adapter utils to create {@link DataStream} for Kafka source.
 */
public class StreamerUtils {
  public static DataStream<RowData> createKafkaStream(
      StreamExecutionEnvironment env,
      RowType rowType,
      String topic,
      Properties props) {
    return env.fromSource(
            KafkaSource.<RowData>builder()
                .setBootstrapServers(props.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .setValueOnlyDeserializer(new JsonRowDataDeserializationSchema(
                    rowType,
                    InternalTypeInfo.of(rowType),
                    false,
                    true,
                    TimestampFormat.ISO_8601
                ))
                .setTopics(topic)
                .build(),
            WatermarkStrategy.noWatermarks(),
            "kafka_source")
        .uid("uid_kafka_source");
  }
}
