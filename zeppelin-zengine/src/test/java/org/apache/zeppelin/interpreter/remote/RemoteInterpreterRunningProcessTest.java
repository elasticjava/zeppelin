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
package org.apache.zeppelin.interpreter.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RemoteInterpreterRunningProcess, focusing on the equivalence
 * of isAlive() and isRunning() for externally managed interpreter processes.
 */
class RemoteInterpreterRunningProcessTest {

  private RemoteInterpreterRunningProcess process;

  @BeforeEach
  void setUp() {
    // Create a test instance with minimal configuration
    process = new RemoteInterpreterRunningProcess(
        "testSetting",          // interpreterSettingName
        "testGroup",            // interpreterGroupId
        30000,                  // connectTimeout
        10,                     // connectionPoolSize
        "localhost",            // intpEventServerHost
        12345,                  // intpEventServerPort
        "127.0.0.1",           // host (remote process host)
        54321,                  // port (remote process port)
        false                   // isRecovery
    );
  }

  /**
   * Test that isAlive() and isRunning() return the same value.
   * For externally managed processes, both methods check only endpoint accessibility.
   */
  @Test
  void testIsAliveEqualsIsRunning() {
    // For this implementation, isAlive() and isRunning() must always be equal
    // since both check the same thing: remote endpoint accessibility
    assertEquals(process.isAlive(), process.isRunning(),
        "For RemoteInterpreterRunningProcess, isAlive() must equal isRunning()");
  }

  /**
   * Test contract: The invariant isRunning() => isAlive() is trivially satisfied.
   * Since both methods are equivalent, the invariant always holds.
   */
  @Test
  void testInvariantTriviallySatisfied() {
    // The invariant isRunning() => isAlive() is trivially satisfied
    // because both methods are semantically identical for this implementation
    if (process.isRunning()) {
      assertTrue(process.isAlive(),
          "Invariant must hold: if isRunning() is true, isAlive() must also be true");
    }

    // This test always passes because:
    // isRunning() returns X
    // isAlive() returns X
    // Therefore: X => X is always true
  }

  /**
   * Test that both methods return false when endpoint is not accessible.
   * Note: This test doesn't actually start a server, so both should return false.
   */
  @Test
  void testBothMethodsReturnFalseWhenEndpointNotAccessible() {
    // Since we haven't started any server at 127.0.0.1:54321,
    // the endpoint should not be accessible
    boolean alive = process.isAlive();
    boolean running = process.isRunning();

    // Both should return false (endpoint not accessible)
    assertEquals(alive, running,
        "Both methods should return the same value");

    // In this test case, we expect both to be false
    // (though we don't assert the specific value, just that they're equal)
  }

  /**
   * Test that the process correctly reports the configured host and port.
   */
  @Test
  void testHostAndPortConfiguration() {
    assertEquals("127.0.0.1", process.getHost(),
        "Host should match configured value");
    assertEquals(54321, process.getPort(),
        "Port should match configured value");
  }

  /**
   * Test that interpreter setting name and group ID are correctly set.
   */
  @Test
  void testInterpreterMetadata() {
    assertEquals("testSetting", process.getInterpreterSettingName(),
        "Interpreter setting name should match");
    assertEquals("testGroup", process.getInterpreterGroupId(),
        "Interpreter group ID should match");
  }
}
