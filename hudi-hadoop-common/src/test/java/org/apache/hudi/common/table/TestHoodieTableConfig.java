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

package org.apache.hudi.common.table;

import org.apache.hudi.common.config.ConfigProperty;
import org.apache.hudi.common.config.HoodieConfig;
import org.apache.hudi.common.config.RecordMergeMode;
import org.apache.hudi.common.model.DefaultHoodieRecordPayload;
import org.apache.hudi.common.model.OverwriteWithLatestAvroPayload;
import org.apache.hudi.common.testutils.HoodieCommonTestHarness;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Triple;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.keygen.BaseKeyGenerator;
import org.apache.hudi.storage.HoodieStorage;
import org.apache.hudi.storage.HoodieStorageUtils;
import org.apache.hudi.storage.StoragePath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.apache.hudi.common.config.RecordMergeMode.COMMIT_TIME_ORDERING;
import static org.apache.hudi.common.config.RecordMergeMode.CUSTOM;
import static org.apache.hudi.common.config.RecordMergeMode.EVENT_TIME_ORDERING;
import static org.apache.hudi.common.model.HoodieRecordMerger.COMMIT_TIME_BASED_MERGE_STRATEGY_UUID;
import static org.apache.hudi.common.model.HoodieRecordMerger.EVENT_TIME_BASED_MERGE_STRATEGY_UUID;
import static org.apache.hudi.common.model.HoodieRecordMerger.PAYLOAD_BASED_MERGE_STRATEGY_UUID;
import static org.apache.hudi.common.model.HoodieRecordMerger.getRecordMergeStrategyId;
import static org.apache.hudi.common.table.HoodieTableConfig.RECORD_MERGE_MODE;
import static org.apache.hudi.common.table.HoodieTableConfig.TABLE_CHECKSUM;
import static org.apache.hudi.common.table.HoodieTableConfig.inferRecordMergeModeFromPayloadClass;
import static org.apache.hudi.common.util.ConfigUtils.recoverIfNeeded;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Tests {@link HoodieTableConfig}.
 */
class TestHoodieTableConfig extends HoodieCommonTestHarness {
  private HoodieStorage storage;
  private StoragePath metaPath;
  private StoragePath cfgPath;
  private StoragePath backupCfgPath;

  @BeforeEach
  public void setUp() throws Exception {
    initPath();
    storage = HoodieStorageUtils.getStorage(basePath, HoodieTestUtils.getDefaultStorageConfWithDefaults());
    metaPath = new StoragePath(basePath, HoodieTableMetaClient.METAFOLDER_NAME);
    Properties props = new Properties();
    props.setProperty(HoodieTableConfig.NAME.key(), "test-table");
    initializeNewTableConfig(props);
  }

  @AfterEach
  public void tearDown() throws Exception {
    storage.close();
  }

  private void initializeNewTableConfig(Properties properties) throws IOException {
    HoodieTableConfig.create(storage, metaPath, properties);
    cfgPath = new StoragePath(metaPath, HoodieTableConfig.HOODIE_PROPERTIES_FILE);
    backupCfgPath = new StoragePath(metaPath, HoodieTableConfig.HOODIE_PROPERTIES_FILE_BACKUP);
  }

  @Test
  void testCreate() throws IOException {
    assertTrue(
        storage.exists(new StoragePath(metaPath, HoodieTableConfig.HOODIE_PROPERTIES_FILE)));
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertEquals(7, config.getProps().size());
  }

  @Test
  void testUpdate() throws IOException {
    Properties updatedProps = new Properties();
    updatedProps.setProperty(HoodieTableConfig.NAME.key(), "test-table2");
    updatedProps.setProperty(HoodieTableConfig.PRECOMBINE_FIELDS.key(), "new_field");
    HoodieTableConfig.update(storage, metaPath, updatedProps);

    assertTrue(storage.exists(cfgPath));
    assertFalse(storage.exists(backupCfgPath));
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertEquals(8, config.getProps().size());
    assertEquals("test-table2", config.getTableName());
    assertEquals(Collections.singletonList("new_field"), config.getPreCombineFields());
    assertEquals(Option.of("new_field"), config.getPreCombineFieldsStr());
  }

  @Test
  void testDelete() throws IOException {
    Set<String> deletedProps = CollectionUtils.createSet(HoodieTableConfig.TIMELINE_HISTORY_PATH.key(),
        "hoodie.invalid.config");
    HoodieTableConfig.delete(storage, metaPath, deletedProps);

    assertTrue(storage.exists(cfgPath));
    assertFalse(storage.exists(backupCfgPath));
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertEquals(6, config.getProps().size());
    assertNull(config.getProps().getProperty("hoodie.invalid.config"));
    assertFalse(config.getProps().contains(HoodieTableConfig.TIMELINE_HISTORY_PATH.key()));
  }

  @Test
  void testReadsWhenPropsFileDoesNotExist() throws IOException {
    storage.deleteFile(cfgPath);
    assertThrows(HoodieIOException.class, () -> {
      new HoodieTableConfig(storage, metaPath, null, null, null);
    });
  }

  @Test
  void testReadsWithUpdateFailures() throws IOException {
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    storage.deleteFile(cfgPath);
    try (OutputStream out = storage.create(backupCfgPath)) {
      config.getProps().store(out, "");
    }

    assertFalse(storage.exists(cfgPath));
    assertTrue(storage.exists(backupCfgPath));
    config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertEquals(7, config.getProps().size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testUpdateRecovery(boolean shouldPropsFileExist) throws IOException {
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    if (!shouldPropsFileExist) {
      storage.deleteFile(cfgPath);
    }
    try (OutputStream out = storage.create(backupCfgPath)) {
      config.getProps().store(out, "");
    }

    recoverIfNeeded(storage, cfgPath, backupCfgPath);
    assertTrue(storage.exists(cfgPath));
    assertFalse(storage.exists(backupCfgPath));
    config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertEquals(7, config.getProps().size());
  }

  @Test
  void testReadRetry() throws IOException {
    // When both the hoodie.properties and hoodie.properties.backup do not exist then the read fails
    storage.rename(cfgPath, new StoragePath(cfgPath.toString() + ".bak"));
    assertThrows(HoodieIOException.class, () -> new HoodieTableConfig(storage, metaPath, null, null, null));

    // Should return the backup config if hoodie.properties is not present
    storage.rename(new StoragePath(cfgPath.toString() + ".bak"), backupCfgPath);
    new HoodieTableConfig(storage, metaPath, null, null, null);

    // Should return backup config if hoodie.properties is corrupted
    Properties props = new Properties();
    props.put(TABLE_CHECKSUM.key(), "0");
    try (OutputStream out = storage.create(cfgPath)) {
      props.store(out, "Wrong checksum in file so is invalid");
    }
    new HoodieTableConfig(storage, metaPath, null, null, null);

    // Should throw exception if both hoodie.properties and backup are corrupted
    try (OutputStream out = storage.create(backupCfgPath)) {
      props.store(out, "Wrong checksum in file so is invalid");
    }
    assertThrows(IllegalArgumentException.class, () -> new HoodieTableConfig(storage,
        metaPath, null, null, null));
  }

  @Test
  void testConcurrentlyUpdate() throws ExecutionException, InterruptedException {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    Future updaterFuture = executor.submit(() -> {
      for (int i = 0; i < 100; i++) {
        Properties updatedProps = new Properties();
        updatedProps.setProperty(HoodieTableConfig.NAME.key(), "test-table" + i);
        updatedProps.setProperty(HoodieTableConfig.PRECOMBINE_FIELDS.key(), "new_field" + i);
        HoodieTableConfig.update(storage, metaPath, updatedProps);
      }
    });

    Future readerFuture = executor.submit(() -> {
      for (int i = 0; i < 100; i++) {
        // Try to load the table properties, won't throw any exception
        new HoodieTableConfig(storage, metaPath, null, null, null);
      }
    });

    updaterFuture.get();
    readerFuture.get();
    executor.shutdown();
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableVersion.class, names = {"SEVEN", "EIGHT"})
  void testPartitionFields(HoodieTableVersion version) {
    Properties updatedProps = new Properties();
    updatedProps.setProperty(HoodieTableConfig.PARTITION_FIELDS.key(), version.greaterThan(HoodieTableVersion.SEVEN) ? "p1:simple,p2:timestamp" : "p1,p2");
    updatedProps.setProperty(HoodieTableConfig.VERSION.key(), String.valueOf(HoodieTableVersion.EIGHT.versionCode()));
    HoodieTableConfig.update(storage, metaPath, updatedProps);

    // Test makes sure that the partition fields returned by table config do not have partition type
    // to ensure backward compatibility for the API
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertArrayEquals(new String[] {"p1", "p2"}, config.getPartitionFields().get());
    assertEquals("p1,p2", config.getPartitionFieldProp());
  }

  @ParameterizedTest
  @ValueSource(strings = {"p1:simple,p2:timestamp", "p1,p2"})
  void testPartitionFieldAPIs(String partitionFields) {
    Properties updatedProps = new Properties();
    updatedProps.setProperty(HoodieTableConfig.PARTITION_FIELDS.key(), partitionFields);
    HoodieTableConfig.update(storage, metaPath, updatedProps);

    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertEquals(partitionFields, HoodieTableConfig.getPartitionFieldPropForKeyGenerator(config).get());
    assertEquals("p1,p2", HoodieTableConfig.getPartitionFieldProp(config).get());
    assertArrayEquals(Arrays.stream(partitionFields.split(BaseKeyGenerator.FIELD_SEPARATOR)).toArray(), HoodieTableConfig.getPartitionFieldsForKeyGenerator(config).get().toArray());
    assertArrayEquals(new String[] {"p1", "p2"}, HoodieTableConfig.getPartitionFields(config).get());
    assertEquals("p1", HoodieTableConfig.getPartitionFieldWithoutKeyGenPartitionType(partitionFields.split(",")[0], config));
  }

  @Test
  void testValidateConfigVersion() {
    assertTrue(HoodieTableConfig.validateConfigVersion(HoodieTableConfig.INITIAL_VERSION, HoodieTableVersion.EIGHT));
    assertTrue(HoodieTableConfig.validateConfigVersion(ConfigProperty.key("").noDefaultValue().withDocumentation(""),
        HoodieTableVersion.SIX));
    assertFalse(HoodieTableConfig.validateConfigVersion(HoodieTableConfig.INITIAL_VERSION, HoodieTableVersion.SIX));
  }

  @Test
  void testDropInvalidConfigs() {
    // test invalid configs are dropped
    HoodieConfig config = new HoodieConfig();
    config.setValue(HoodieTableConfig.VERSION, String.valueOf(HoodieTableVersion.SIX.versionCode()));
    config.setValue(HoodieTableConfig.INITIAL_VERSION, String.valueOf(HoodieTableVersion.EIGHT.versionCode()));
    config.setValue(RECORD_MERGE_MODE, COMMIT_TIME_ORDERING.name());

    HoodieTableConfig.dropInvalidConfigs(config);
    assertTrue(config.contains(HoodieTableConfig.VERSION));
    assertFalse(config.contains(HoodieTableConfig.INITIAL_VERSION));
    assertFalse(config.contains(RECORD_MERGE_MODE));

    // test valid ones are not dropped
    config = new HoodieConfig();
    config.setValue(HoodieTableConfig.VERSION, String.valueOf(HoodieTableVersion.EIGHT.versionCode()));
    config.setValue(RECORD_MERGE_MODE, COMMIT_TIME_ORDERING.name());
    HoodieTableConfig.dropInvalidConfigs(config);
    assertTrue(config.contains(RECORD_MERGE_MODE));
  }

  @Test
  void testDefinedTableConfigs() {
    List<ConfigProperty<?>> configProperties = HoodieTableConfig.definedTableConfigs();
    assertEquals(40, configProperties.size());
    configProperties.forEach(c -> {
      assertNotNull(c);
      assertFalse(c.doc().isEmpty());
    });
  }

  @Test
  void testTableMergeProperties() throws IOException {
    // for out of the box, there are no merge properties
    HoodieTableConfig config = new HoodieTableConfig(storage, metaPath, null, null, null);
    assertTrue(config.getTableMergeProperties().isEmpty());

    // delete and re-create w/ merge properties
    storage.deleteFile(cfgPath);
    storage.deleteFile(backupCfgPath);

    Properties props = new Properties();
    props.setProperty(HoodieTableConfig.NAME.key(), "test-table");
    // no merge props
    props.setProperty(HoodieTableConfig.MERGE_PROPERTIES_PREFIX + "key1", "value1");
    props.setProperty(HoodieTableConfig.MERGE_PROPERTIES_PREFIX + "key2", "value2");
    // add some random property which does not match the prefix.
    props.setProperty("key3", "value3");

    initializeNewTableConfig(props);
    config = new HoodieTableConfig(storage, metaPath, null, null, null);
    Map<String, String> expectedProps = new HashMap<>();
    expectedProps.put("key1","value1");
    expectedProps.put("key2","value2");
    assertEquals(expectedProps, config.getTableMergeProperties());
  }

  private static Stream<Arguments> argumentsForInferringRecordMergeMode() {
    String defaultPayload = DefaultHoodieRecordPayload.class.getName();
    String overwritePayload = OverwriteWithLatestAvroPayload.class.getName();
    String customPayload = "custom_payload";
    String customStrategy = "custom_strategy";
    String orderingFieldName = "timestamp";

    Stream<Arguments> arguments = Stream.of(
        //test empty args with both null and ""
        arguments(null, null, null, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, null, null, "",
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, null, null, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, "", "", null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, "", "", orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),

        //test legal event time ordering combos
        arguments(EVENT_TIME_ORDERING, null, null, null,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(EVENT_TIME_ORDERING, null, null, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(EVENT_TIME_ORDERING, defaultPayload, null, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(EVENT_TIME_ORDERING, null, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, defaultPayload, null, null,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, defaultPayload, null, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, null, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, null, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "false", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),

        //test legal commit time ordering combos
        arguments(COMMIT_TIME_ORDERING, null, null, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(COMMIT_TIME_ORDERING, null, null, "",
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(COMMIT_TIME_ORDERING, null, null, orderingFieldName,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(COMMIT_TIME_ORDERING, overwritePayload, null, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(COMMIT_TIME_ORDERING, null, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, overwritePayload, null, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, overwritePayload, null, "",
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, overwritePayload, null, orderingFieldName,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, null, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, null, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "false", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID),

        //test legal custom merge mode combos
        arguments(CUSTOM, customPayload, null, null,
            "false", CUSTOM, customPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID),
        arguments(CUSTOM, customPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "false", CUSTOM, customPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID),
        arguments(null, customPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "false", CUSTOM, customPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID),
        arguments(null, customPayload, null, null,
            "false", CUSTOM, customPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID),
        arguments(CUSTOM, null, customStrategy, null,
            "false", CUSTOM, defaultPayload, customStrategy),
        arguments(CUSTOM, customPayload, customStrategy, null,
            "false", CUSTOM, customPayload, null),

        //test legal configs that work but should not be used usually
        arguments(CUSTOM, defaultPayload, customStrategy, null,
            "six-only", CUSTOM, defaultPayload, customStrategy),
        arguments(CUSTOM, defaultPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "six-only", CUSTOM, defaultPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID),
        arguments(CUSTOM, overwritePayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "six-only", CUSTOM, overwritePayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID),
        arguments(null, defaultPayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "false", null, defaultPayload, null),
        arguments(null, overwritePayload, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "false", null, overwritePayload, null),

        //test illegal combos due to missing info
        arguments(CUSTOM, null, null, null,
            "true", null, null, null),
        arguments(CUSTOM, null, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),

        //test illegal combos
        arguments(EVENT_TIME_ORDERING, overwritePayload, null, orderingFieldName,
            "true", null, null, null),
        arguments(EVENT_TIME_ORDERING, customPayload, null, orderingFieldName,
            "true", null, null, null),
        arguments(EVENT_TIME_ORDERING, null, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "true", null, null, null),
        arguments(EVENT_TIME_ORDERING, null, customStrategy, orderingFieldName,
            "true", null, null, null),
        arguments(EVENT_TIME_ORDERING, null, PAYLOAD_BASED_MERGE_STRATEGY_UUID, orderingFieldName,
            "true", null, null, null),
        arguments(COMMIT_TIME_ORDERING, defaultPayload, null, null,
            "true", null, null, null),
        arguments(COMMIT_TIME_ORDERING, customPayload, null, null,
            "true", null, null, null),
        arguments(COMMIT_TIME_ORDERING, null, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),
        arguments(COMMIT_TIME_ORDERING, null, customStrategy, null,
            "true", null, null, null),
        arguments(COMMIT_TIME_ORDERING, null, PAYLOAD_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),
        arguments(CUSTOM, defaultPayload, null, null,
            "true", null, null, null),
        arguments(CUSTOM, overwritePayload, null, null,
            "true", null, null, null),
        arguments(CUSTOM, null, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),
        arguments(CUSTOM, null, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),
        arguments(CUSTOM, defaultPayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),
        arguments(CUSTOM, overwritePayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "true", null, null, null),

        // dimensions that should pass validation on table version 6, not table version 8
        arguments(null, defaultPayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "eight-only", EVENT_TIME_ORDERING, defaultPayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID),
        arguments(null, overwritePayload, EVENT_TIME_BASED_MERGE_STRATEGY_UUID, null,
            "eight-only", COMMIT_TIME_ORDERING, overwritePayload, COMMIT_TIME_BASED_MERGE_STRATEGY_UUID)
    );
    return arguments;
  }

  @ParameterizedTest
  @MethodSource("argumentsForInferringRecordMergeMode")
  void testInferMergeMode(RecordMergeMode inputMergeMode, String inputPayloadClass,
                                 String inputMergeStrategy, String orderingFieldName,
                                 String shouldThrowString, RecordMergeMode outputMergeMode,
                                 String outputPayloadClass, String outputMergeStrategy) throws IOException {
    Arrays.stream(new HoodieTableVersion[] {HoodieTableVersion.EIGHT, HoodieTableVersion.SIX})
        .forEach(tableVersion -> {
          boolean shouldThrow = "eight-only".equals(shouldThrowString)
              ? tableVersion.greaterThanOrEquals(HoodieTableVersion.EIGHT)
              : "six-only".equals(shouldThrowString)
              ? !tableVersion.greaterThanOrEquals(HoodieTableVersion.EIGHT)
              : Boolean.parseBoolean(shouldThrowString);
          RecordMergeMode expectedMergeMode = outputMergeMode;
          String expectedMergeStrategy = outputMergeStrategy;
          if (!shouldThrow && (outputMergeMode == null || outputMergeStrategy == null)) {
            expectedMergeMode = tableVersion.greaterThanOrEquals(HoodieTableVersion.EIGHT)
                ? CUSTOM : inferRecordMergeModeFromPayloadClass(outputPayloadClass);
            expectedMergeStrategy = getRecordMergeStrategyId(expectedMergeMode, outputPayloadClass, inputMergeStrategy, tableVersion);
          }
          if (shouldThrow) {
            assertThrows(IllegalArgumentException.class,
                () -> HoodieTableConfig.inferCorrectMergingBehavior(
                    inputMergeMode, inputPayloadClass, inputMergeStrategy, orderingFieldName,
                    tableVersion));
          } else {
            Triple<RecordMergeMode, String, String> inferredConfigs =
                HoodieTableConfig.inferCorrectMergingBehavior(
                    inputMergeMode, inputPayloadClass, inputMergeStrategy, orderingFieldName,
                    tableVersion);
            assertEquals(expectedMergeMode, inferredConfigs.getLeft());
            assertEquals(outputPayloadClass, inferredConfigs.getMiddle());
            assertEquals(expectedMergeStrategy, inferredConfigs.getRight());
          }
        });
  }
}
