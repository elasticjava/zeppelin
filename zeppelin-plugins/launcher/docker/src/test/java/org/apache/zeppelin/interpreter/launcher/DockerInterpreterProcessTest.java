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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.ContainerState;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.interpreter.InterpreterOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Production-focused tests for DockerInterpreterProcess.
 *
 * Tests cover real Docker API scenarios, failure policy behavior, and edge cases
 * that occur in production environments.
 */
class DockerInterpreterProcessTest {

  protected static ZeppelinConfiguration zConf = spy(ZeppelinConfiguration.load());

  // ==================== Launcher Integration & Configuration ====================

  @Test
  void testCreateIntpProcess() throws IOException {
    DockerInterpreterLauncher launcher = new DockerInterpreterLauncher(zConf, null);

    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");

    InterpreterOption option = new InterpreterOption();
    InterpreterLaunchContext context = new InterpreterLaunchContext(
        properties, option, null, "user1", "intpGroupId", "groupId",
        "groupName", "name", 0, "host");

    InterpreterClient client = launcher.launch(context);

    assertTrue(client instanceof DockerInterpreterProcess);
    DockerInterpreterProcess process = (DockerInterpreterProcess) client;

    assertEquals("name", process.getInterpreterSettingName());
    assertEquals("/opt/spark", process.containerSparkHome);
    assertTrue(process.uploadLocalLibToContainter);
    assertNotNull(process.containerName);
    assertTrue(process.containerName.startsWith("zeppelin-"));
  }

  @Test
  void testEnvConfiguration() throws IOException {
    when(zConf.getString(ConfVars.ZEPPELIN_DOCKER_CONTAINER_SPARK_HOME))
        .thenReturn("/custom/spark");
    when(zConf.getBoolean(ConfVars.ZEPPELIN_DOCKER_UPLOAD_LOCAL_LIB_TO_CONTAINTER))
        .thenReturn(false);
    when(zConf.getString(ConfVars.ZEPPELIN_DOCKER_HOST))
        .thenReturn("http://custom-docker:2375");

    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");

    HashMap<String, String> envs = new HashMap<>();
    envs.put("CUSTOM_VAR", "custom_value");

    DockerInterpreterProcess process = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "shared_process", "sh", "shell",
        properties, envs, "zeppelin.server.hostname", 12320, 5000, 10);

    assertEquals("/custom/spark", process.containerSparkHome);
    assertFalse(process.uploadLocalLibToContainter);
    assertEquals("http://custom-docker:2375", process.dockerHost);

    List<String> listEnvs = process.getListEnvs();
    assertTrue(listEnvs.stream().anyMatch(e -> e.startsWith("CUSTOM_VAR=")));
  }

  @Test
  void testTemplateBindings() throws IOException {
    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "8000");

    DockerInterpreterProcess process = new DockerInterpreterProcess(
        zConf, "interpreter-image:2.0", "test_group_id", "test_group",
        "test_setting", properties, new HashMap<>(), "test.host", 12345, 8000, 15);

    Properties bindings = process.getTemplateBindings();

    assertEquals(10, bindings.size());
    assertNotNull(bindings.get("CONTAINER_ZEPPELIN_HOME"));
    assertEquals("interpreter-image:2.0", bindings.get("zeppelin.interpreter.container.image"));
    assertEquals("test_group_id", bindings.get("zeppelin.interpreter.group.id"));
    assertEquals("test_group", bindings.get("zeppelin.interpreter.group.name"));
    assertEquals("test_setting", bindings.get("zeppelin.interpreter.setting.name"));
    assertEquals("test.host", bindings.get("zeppelin.server.rpc.host"));
    assertEquals("8000", bindings.get("zeppelin.interpreter.connect.timeout"));
  }

  // ==================== Health Check Tests ====================

  private DockerClient mockDockerClient;
  private ContainerInfo mockContainerInfo;
  private ContainerState mockContainerState;
  private DockerInterpreterProcess process;

  @BeforeEach
  void setUp() {
    mockDockerClient = mock(DockerClient.class);
    mockContainerInfo = mock(ContainerInfo.class);
    mockContainerState = mock(ContainerState.class);

    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");

    process = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group_id", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, mockDockerClient);
  }

  @AfterEach
  void tearDown() {
    Thread.interrupted(); // Clear interrupt flag
  }

  /**
   * Tests real Docker container states from production.
   * Format: status,running,dead,expectedAlive
   */
  @ParameterizedTest
  @CsvSource({
      // Alive states
      "running,true,false,true",
      "paused,false,false,true",
      "created,false,false,true",
      "restarting,false,false,true",
      // Terminal states (container is dead)
      "exited,false,false,false",
      "dead,false,true,false",
      "removing,false,false,false",
      // Edge case: dead flag overrides running
      "running,true,true,false"
  })
  void testContainerStates(String status, boolean running, boolean dead, boolean expectedAlive)
      throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(status);
    when(mockContainerState.running()).thenReturn(running);
    when(mockContainerState.dead()).thenReturn(dead);

    assertEquals(expectedAlive, process.isAlive(),
        String.format("state=%s running=%s dead=%s", status, running, dead));
  }

  /**
   * Docker API returns lowercase status, but code handles mixed case defensively.
   */
  @ParameterizedTest
  @ValueSource(strings = {"exited", "EXITED", "Exited", "eXiTeD"})
  void testTerminalStateCaseInsensitive(String status) throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(status);
    when(mockContainerState.running()).thenReturn(false);
    when(mockContainerState.dead()).thenReturn(false);

    assertFalse(process.isAlive(), "Terminal state '" + status + "' should be dead");
  }

  /**
   * Container deleted externally (docker rm, node failure, etc.).
   * Should return false and reset counters.
   */
  @Test
  void testContainerNotFound() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new ContainerNotFoundException(process.containerName, null));

    assertFalse(process.isAlive());

    // Verify counters reset: next transient error should fail-open
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Timeout", 500));

    assertTrue(process.isAlive(), "After 404 reset, transient error should fail-open");
  }

  /**
   * Docker API returns null state (corrupted response, API bug).
   */
  @Test
  void testNullContainerState() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(null);

    // Should fail-open during grace period
    assertTrue(process.isAlive());
  }

  /**
   * Docker API returns state with null status field.
   */
  @Test
  void testNullStatus() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(null);

    assertTrue(process.isAlive());
  }

  /**
   * Process created but start() never called (DockerClient is null).
   */
  @Test
  void testBeforeStart() {
    Properties properties = new Properties();
    DockerInterpreterProcess notStarted = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, null);

    assertFalse(notStarted.isAlive());
    assertFalse(notStarted.isRunning());
  }

  /**
   * Tests fail-closed policy: MAX_CONSECUTIVE_FAILURES = 2
   * Failures: 1, 2 → fail-open (grace period)
   * Failure 3+ → fail-closed
   */
  @Test
  void testFailureThreshold() throws Exception {
    DockerException error = new DockerException("Timeout", 500);
    when(mockDockerClient.inspectContainer(process.containerName)).thenThrow(error);

    // Failures 1 and 2: fail-open
    assertTrue(process.isAlive(), "Failure #1 should fail-open");
    assertTrue(process.isAlive(), "Failure #2 should fail-open");

    // Failure 3: exceeds MAX_CONSECUTIVE_FAILURES=2, fail-closed
    assertFalse(process.isAlive(), "Failure #3 should fail-closed");
  }

  /**
   * Tests counter reset after recovery.
   * Pattern: Error → Success → Error (should fail-open again)
   */
  @Test
  void testRecoveryResetsCounters() throws Exception {
    // Phase 1: Transient error
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Timeout", 500));
    assertTrue(process.isAlive());

    // Phase 2: Recovery
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("running");
    when(mockContainerState.running()).thenReturn(true);
    when(mockContainerState.dead()).thenReturn(false);

    assertTrue(process.isAlive());

    // Phase 3: New error - counters were reset, should fail-open
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("New timeout", 500));
    assertTrue(process.isAlive(), "After recovery, new error should fail-open");
  }

  /**
   * Tests state transition: running → errors → terminal state.
   * This is what happens when a container crashes.
   */
  @Test
  void testContainerCrashTransition() throws Exception {
    // Phase 1: Running
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("running");
    when(mockContainerState.running()).thenReturn(true);
    when(mockContainerState.dead()).thenReturn(false);

    assertTrue(process.isAlive(), "Container should be alive");

    // Phase 2: Crashing (transient errors during crash)
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Timeout", 500));
    assertTrue(process.isAlive(), "During crash: error #1 fails open");
    assertTrue(process.isAlive(), "During crash: error #2 fails open");

    // Phase 3: Crashed (terminal state)
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerState.status()).thenReturn("exited");
    when(mockContainerState.running()).thenReturn(false);

    assertFalse(process.isAlive(), "Crashed container should be dead");
  }

  /**
   * Thread interrupted during API call (e.g., shutdown).
   * Should restore interrupt flag and apply grace window.
   */
  @Test
  void testInterruptHandling() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new InterruptedException("Interrupted"));

    assertTrue(process.isAlive(), "Interrupted check should fail-open");
    assertTrue(Thread.interrupted(), "Interrupt flag must be restored");
  }

  /**
   * Critical invariant: isRunning() should never be true when isAlive() is false.
   * This prevents interpreter restart loops.
   */
  @Test
  void testInvariantIsRunningImpliesIsAlive() throws Exception {
    // Terminal state
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("exited");
    when(mockContainerState.running()).thenReturn(false);
    when(mockContainerState.dead()).thenReturn(false);

    assertFalse(process.isAlive());
    assertFalse(process.isRunning(), "isRunning() must be false when isAlive() is false");

    // Container not found
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new ContainerNotFoundException(process.containerName, null));

    assertFalse(process.isAlive());
    assertFalse(process.isRunning(), "isRunning() must be false for non-existent container");
  }

  /**
   * stop() called before start() (e.g., launcher error).
   * Should not throw NPE.
   */
  @Test
  void testStopBeforeStart() {
    Properties properties = new Properties();
    DockerInterpreterProcess notStarted = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, null);

    notStarted.stop(); // Should not throw NPE

    assertFalse(notStarted.isAlive());
    assertFalse(notStarted.isRunning());
  }
}
