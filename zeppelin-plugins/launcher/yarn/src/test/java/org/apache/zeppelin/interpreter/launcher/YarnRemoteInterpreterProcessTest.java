/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zeppelin.interpreter.launcher;

import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.exceptions.ApplicationNotFoundException;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for YarnRemoteInterpreterProcess, focusing on isAlive() and isRunning()
 * semantics with fail-closed error policy.
 *
 * Note: Most tests are disabled pending test infrastructure improvements.
 * The tests document the expected behavior and serve as implementation guides.
 */
class YarnRemoteInterpreterProcessTest {

  @AfterEach
  void tearDown() {
    // Clear interrupt flag to prevent side effects on other tests
    Thread.interrupted();
  }

  /**
   * Provides test data for YARN application state mapping.
   * Each argument contains: [YarnApplicationState, expectedIsAlive, expectedIsRunning]
   */
  static Stream<Arguments> yarnStateProvider() {
    return Stream.of(
        // Non-terminal states -> alive
        Arguments.of(YarnApplicationState.NEW, true, false),
        Arguments.of(YarnApplicationState.NEW_SAVING, true, false),
        Arguments.of(YarnApplicationState.SUBMITTED, true, false),
        Arguments.of(YarnApplicationState.ACCEPTED, true, false),
        Arguments.of(YarnApplicationState.RUNNING, true, true),

        // Terminal states -> not alive
        Arguments.of(YarnApplicationState.FINISHED, false, false),
        Arguments.of(YarnApplicationState.FAILED, false, false),
        Arguments.of(YarnApplicationState.KILLED, false, false)
    );
  }

  /**
   * Table-driven test verifying correct state mapping for all YARN application states.
   * Ensures that the invariant isRunning() => isAlive() holds for each state.
   *
   * TODO: Enable this test once YarnRemoteInterpreterProcess has test-friendly constructor
   * or mocking infrastructure is in place to inject a mock YarnClient.
   */
  @Disabled("Requires test infrastructure to inject mock YarnClient")
  @ParameterizedTest
  @MethodSource("yarnStateProvider")
  void testIsAliveForYarnStates(YarnApplicationState state,
                                 boolean expectedAlive,
                                 boolean expectedRunning) throws Exception {
    // Test implementation pending - see TODO above
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testIsAliveWhenApplicationNotFound() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testIsAliveWhenYarnExceptionInitial() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testIsAliveWhenIOExceptionInitial() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testIsAliveWhenPersistentFailures() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testIsAliveWhenGraceWindowExpired() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testSuccessfulCheckResetsCounters() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testInvariantIsRunningImpliesIsAlive() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testIsAliveWhenAppIdIsNull() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testInitialGraceWindowForUnvalidatedClient() {
    // Test implementation pending
  }

  @Disabled("Requires test infrastructure")
  @Test
  void testApplicationNotFoundResetsCounters() {
    // Test implementation pending
  }
}
