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

package org.apache.hudi.keygen;

import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieKeyException;
import org.apache.hudi.exception.HoodieKeyGeneratorException;
import org.apache.hudi.keygen.constant.KeyGeneratorOptions;

import org.apache.avro.generic.GenericRecord;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.unsafe.types.UTF8String;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.hudi.keygen.CustomAvroKeyGenerator.getPartitionPathFormatException;

/**
 * This is a generic implementation of KeyGenerator where users can configure record key as a single field or a combination of fields. Similarly partition path can be configured to have multiple
 * fields or only one field. This class expects value for prop "hoodie.datasource.write.partitionpath.field" in a specific format. For example:
 *
 * properties.put("hoodie.datasource.write.partitionpath.field", "field1:PartitionKeyType1,field2:PartitionKeyType2").
 *
 * The complete partition path is created as <value for field1 basis PartitionKeyType1>/<value for field2 basis PartitionKeyType2> and so on.
 *
 * Few points to consider: 1. If you want to customize some partition path field on a timestamp basis, you can use field1:timestampBased 2. If you simply want to have the value of your configured
 * field in the partition path, use field1:simple 3. If you want your table to be non partitioned, simply leave it as blank.
 *
 * RecordKey is internally generated using either SimpleKeyGenerator or ComplexKeyGenerator.
 *
 */
public class CustomKeyGenerator extends BuiltinKeyGenerator {

  private final CustomAvroKeyGenerator customAvroKeyGenerator;
  private final List<BuiltinKeyGenerator> partitionKeyGenerators;
  private final BuiltinKeyGenerator recordKeyGenerator;

  public CustomKeyGenerator(TypedProperties props) {
    // NOTE: We have to strip partition-path configuration, since it could only be interpreted by
    //       this key-gen
    super(stripPartitionPathConfig(props));
    this.recordKeyFields = Option.ofNullable(props.getString(KeyGeneratorOptions.RECORDKEY_FIELD_NAME.key(), null))
        .map(recordKeyConfigValue ->
            Arrays.stream(recordKeyConfigValue.split(","))
                .map(String::trim)
                .collect(Collectors.toList())
        ).orElse(Collections.emptyList());
    String partitionPathFields = props.getString(KeyGeneratorOptions.PARTITIONPATH_FIELD_NAME.key());
    this.partitionPathFields = partitionPathFields == null
        ? Collections.emptyList()
        : Arrays.stream(partitionPathFields.split(",")).map(String::trim).collect(Collectors.toList());
    this.customAvroKeyGenerator = new CustomAvroKeyGenerator(props);
    this.recordKeyGenerator = getRecordKeyFieldNames().size() == 1
        ? new SimpleKeyGenerator(config, Option.ofNullable(config.getString(KeyGeneratorOptions.RECORDKEY_FIELD_NAME.key())), null)
        : new ComplexKeyGenerator(config);
    this.partitionKeyGenerators = getPartitionKeyGenerators(this.partitionPathFields, config);
  }

  private static List<BuiltinKeyGenerator> getPartitionKeyGenerators(List<String> partitionPathFields, TypedProperties config) {
    if (partitionPathFields.size() == 1 && partitionPathFields.get(0).isEmpty()) {
      return Collections.emptyList();
    } else {
      return partitionPathFields.stream().map(field -> {
        String[] fieldWithType = field.split(CUSTOM_KEY_GENERATOR_SPLIT_REGEX);
        if (fieldWithType.length != 2) {
          throw getPartitionPathFormatException();
        }
        String partitionPathField = fieldWithType[0];
        CustomAvroKeyGenerator.PartitionKeyType keyType = CustomAvroKeyGenerator.PartitionKeyType.valueOf(fieldWithType[1].toUpperCase());
        switch (keyType) {
          case SIMPLE:
            return new SimpleKeyGenerator(config, partitionPathField);
          case TIMESTAMP:
            try {
              return new TimestampBasedKeyGenerator(config, partitionPathField);
            } catch (IOException ioe) {
              throw new HoodieKeyGeneratorException("Unable to initialise TimestampBasedKeyGenerator class", ioe);
            }
          default:
            throw new HoodieKeyGeneratorException("Please provide valid PartitionKeyType with fields! You provided: " + keyType);
        }
      }).collect(Collectors.toList());
    }
  }

  @Override
  public String getRecordKey(GenericRecord record) {
    return customAvroKeyGenerator.getRecordKey(record);
  }

  @Override
  public String getPartitionPath(GenericRecord record) {
    return customAvroKeyGenerator.getPartitionPath(record);
  }

  @Override
  public String getRecordKey(Row row) {
    return recordKeyGenerator.getRecordKey(row);
  }

  @Override
  public String getPartitionPath(Row row) {
    return getPartitionPath(Option.empty(), Option.of(row), Option.empty());
  }

  @Override
  public UTF8String getPartitionPath(InternalRow row, StructType schema) {
    return UTF8String.fromString(getPartitionPath(Option.empty(), Option.empty(), Option.of(Pair.of(row, schema))));
  }

  public String getPartitionPath(Option<GenericRecord> record, Option<Row> row, Option<Pair<InternalRow, StructType>> internalRowStructTypePair) {
    if (getPartitionPathFields() == null) {
      throw new HoodieKeyException("Unable to find field names for partition path in cfg");
    }
    // Corresponds to no partition case
    if (partitionKeyGenerators.isEmpty()) {
      return "";
    }
    StringBuilder partitionPath = new StringBuilder();
    for (int i = 0; i < partitionKeyGenerators.size(); i++) {
      BuiltinKeyGenerator keyGenerator = partitionKeyGenerators.get(i);
      if (record.isPresent()) {
        partitionPath.append(keyGenerator.getPartitionPath(record.get()));
      } else if (row.isPresent()) {
        partitionPath.append(keyGenerator.getPartitionPath(row.get()));
      } else {
        partitionPath.append(keyGenerator.getPartitionPath(internalRowStructTypePair.get().getKey(),
            internalRowStructTypePair.get().getValue()));
      }
      if (i != partitionKeyGenerators.size() - 1) {
        partitionPath.append(customAvroKeyGenerator.getDefaultPartitionPathSeparator());
      }
    }
    return partitionPath.toString();
  }

  private static TypedProperties stripPartitionPathConfig(TypedProperties props) {
    TypedProperties filtered = TypedProperties.copy(props);
    // NOTE: We have to stub it out w/ empty string, since we properties are:
    //         - Expected to bear this config
    //         - Can't be stubbed out w/ null
    filtered.put(KeyGeneratorOptions.PARTITIONPATH_FIELD_NAME.key(), "");
    return filtered;
  }
}

