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

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.ContainerState;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.interpreter.InterpreterOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Tests for DockerInterpreterProcess focusing on real-world scenarios.
 * Tests cover the actual Docker API interactions and lifecycle management.
 */
class DockerInterpreterProcessTest {

  protected static ZeppelinConfiguration zConf = spy(ZeppelinConfiguration.load());

  @Test
  void testCreateIntpProcess() throws IOException {
    DockerInterpreterLauncher launcher
        = new DockerInterpreterLauncher(zConf, null);
    Properties properties = new Properties();
    properties.setProperty(
        ZeppelinConfiguration.ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");
    InterpreterOption option = new InterpreterOption();
    InterpreterLaunchContext context = new InterpreterLaunchContext(properties, option, null,
        "user1", "intpGroupId", "groupId",
        "groupName", "name", 0, "host");
    InterpreterClient client = launcher.launch(context);

    assertTrue(client instanceof DockerInterpreterProcess);
    DockerInterpreterProcess interpreterProcess = (DockerInterpreterProcess) client;
    assertEquals("name", interpreterProcess.getInterpreterSettingName());

    assertEquals("/opt/spark", interpreterProcess.containerSparkHome);
    assertTrue(interpreterProcess.uploadLocalLibToContainter);
    assertNotEquals("http://my-docker-host:2375", interpreterProcess.dockerHost);
  }

  @Test
  void testEnv() throws IOException {
    when(zConf.getString(ConfVars.ZEPPELIN_DOCKER_CONTAINER_SPARK_HOME))
        .thenReturn("my-spark-home");
    when(zConf.getBoolean(ConfVars.ZEPPELIN_DOCKER_UPLOAD_LOCAL_LIB_TO_CONTAINTER))
        .thenReturn(false);
    when(zConf.getString(ConfVars.ZEPPELIN_DOCKER_HOST))
        .thenReturn("http://my-docker-host:2375");

    Properties properties = new Properties();
    properties.setProperty(
        ZeppelinConfiguration.ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");
    HashMap<String, String> envs = new HashMap<String, String>();
    envs.put("MY_ENV1", "V1");

    DockerInterpreterProcess intp = spy(new DockerInterpreterProcess(
        zConf,
        "interpreter-container:1.0",
        "shared_process",
        "sh",
        "shell",
        properties,
        envs,
        "zeppelin.server.hostname",
        12320,
        5000, 10));

    assertEquals("my-spark-home", intp.containerSparkHome);
    assertFalse(intp.uploadLocalLibToContainter);
    assertEquals("http://my-docker-host:2375", intp.dockerHost);
  }

  @Test
  void testTemplateBindings() throws IOException {
    Properties properties = new Properties();
    properties.setProperty(
        ZeppelinConfiguration.ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");

    HashMap<String, String> envs = new HashMap<String, String>();
    envs.put("MY_ENV1", "V1");

    DockerInterpreterProcess intp = new DockerInterpreterProcess(
        zConf,
        "interpreter-container:1.0",
        "shared_process",
        "sh",
        "shell",
        properties,
        envs,
        "zeppelin.server.hostname",
        12320,
        5000, 10);

    Properties dockerProperties = intp.getTemplateBindings();
    assertEquals(10, dockerProperties.size());

    assertNotNull(dockerProperties.get("CONTAINER_ZEPPELIN_HOME"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.container.image"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.group.id"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.group.name"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.setting.name"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.localRepo"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.rpc.portRange"));
    assertNotNull(dockerProperties.get("zeppelin.server.rpc.host"));
    assertNotNull(dockerProperties.get("zeppelin.server.rpc.portRange"));
    assertNotNull(dockerProperties.get("zeppelin.interpreter.connect.timeout"));

    List<String> listEnvs = intp.getListEnvs();
    assertEquals(6, listEnvs.size());
    Map<String, String> mapEnv = new HashMap<>();
    for (int i = 0; i < listEnvs.size(); i++) {
      String env = listEnvs.get(i);
      String kv[] = env.split("=");
      mapEnv.put(kv[0], kv[1]);
    }
    assertEquals(6, mapEnv.size());
    assertTrue(mapEnv.containsKey("ZEPPELIN_HOME"));
    assertTrue(mapEnv.containsKey("ZEPPELIN_CONF_DIR"));
    assertTrue(mapEnv.containsKey("ZEPPELIN_FORCE_STOP"));
    assertTrue(mapEnv.containsKey("SPARK_HOME"));
    assertTrue(mapEnv.containsKey("MY_ENV1"));
  }

  // ==================== isAlive() Tests - Real Production Scenarios ====================

  private DockerClient mockDockerClient;
  private ContainerInfo mockContainerInfo;
  private ContainerState mockContainerState;
  private DockerInterpreterProcess process;

  @BeforeEach
  void setUpHealthCheckTests() {
    mockDockerClient = mock(DockerClient.class);
    mockContainerInfo = mock(ContainerInfo.class);
    mockContainerState = mock(ContainerState.class);

    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");

    process = new DockerInterpreterProcess(
        zConf,
        "test-image:1.0",
        "test_group_id",
        "test-group",
        "test-setting",
        properties,
        new HashMap<>(),
        "localhost",
        12345,
        5000,
        10,
        mockDockerClient
    );
  }

  @AfterEach
  void tearDownHealthCheckTests() {
    Thread.interrupted(); // Clear interrupt flag
  }

  /**
   * Tests Docker container state mapping for production scenarios.
   * Format: status,running,dead,expectedAlive
   */
  @ParameterizedTest
  @CsvSource({
      // Running states
      "running,true,false,true",
      "paused,false,false,true",
      "created,false,false,true",
      "restarting,false,false,true",
      // Terminal states
      "exited,false,false,false",
      "dead,false,true,false",
      "removing,false,false,false",
      // Dead flag overrides
      "running,true,true,false"
  })
  void testContainerStateMapping(String status, boolean running, boolean dead,
                                  boolean expectedAlive) throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(status);
    when(mockContainerState.running()).thenReturn(running);
    when(mockContainerState.dead()).thenReturn(dead);

    assertEquals(expectedAlive, process.isAlive(),
        String.format("Container state %s (running=%s, dead=%s)", status, running, dead));
  }

  /**
   * Production scenario: Container was deleted externally (e.g., by docker rm).
   * Should return false and reset counters.
   */
  @Test
  void testContainerNotFound() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new ContainerNotFoundException(process.containerName, null));

    assertFalse(process.isAlive(), "Deleted container should not be alive");
  }

  /**
   * Production scenario: Docker daemon returns null state (rare but possible).
   * Should trigger grace window logic.
   */
  @Test
  void testNullContainerState() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(null);

    // First call - grace window active, should fail-open
    assertTrue(process.isAlive(), "Null state should fail-open during grace period");
  }

  /**
   * Production scenario: Process created but start() never called.
   * DockerClient is null.
   */
  @Test
  void testBeforeStart() {
    Properties properties = new Properties();
    DockerInterpreterProcess notStarted = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, null);

    assertFalse(notStarted.isAlive(), "Process before start() should not be alive");
  }

  /**
   * Production scenario: Docker daemon timeout or network error.
   * Should fail-open initially, then fail-closed after grace period.
   */
  @Test
  void testDockerDaemonError() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Connection timeout", 500));

    // First failure - within grace window
    assertTrue(process.isAlive(), "First failure should fail-open");

    // Multiple failures to exceed MAX_CONSECUTIVE_FAILURES (5)
    for (int i = 0; i < 6; i++) {
      process.isAlive();
    }

    // After exceeding limit, should fail-closed
    assertFalse(process.isAlive(), "After persistent failures should fail-closed");
  }

  /**
   * Production scenario: Transient error, then recovery.
   * Counters should reset on successful check.
   */
  @Test
  void testTransientErrorRecovery() throws Exception {
    // First: transient error
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Transient error", 500));
    assertTrue(process.isAlive(), "Transient error should fail-open");

    // Then: recovery
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("running");
    when(mockContainerState.running()).thenReturn(true);
    when(mockContainerState.dead()).thenReturn(false);

    assertTrue(process.isAlive(), "After recovery should be alive");

    // Another transient error - counters were reset, should fail-open again
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Another transient error", 500));
    assertTrue(process.isAlive(), "New transient error should fail-open (counters reset)");
  }

  /**
   * Production scenario: Thread interrupted during inspect.
   * Should restore interrupt flag and apply grace window.
   */
  @Test
  void testInterruptHandling() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new InterruptedException("Thread interrupted"));

    // Should fail-open and restore interrupt flag
    assertTrue(process.isAlive(), "Interrupted check should fail-open");
    assertTrue(Thread.interrupted(), "Interrupt flag should be restored");
  }

  /**
   * Critical invariant: isRunning() should never be true when isAlive() is false.
   * This prevents inconsistent state.
   */
  @Test
  void testInvariantIsRunningImpliesIsAlive() throws Exception {
    // Container in terminal state
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("exited");
    when(mockContainerState.running()).thenReturn(false);
    when(mockContainerState.dead()).thenReturn(false);

    assertFalse(process.isAlive());
    assertFalse(process.isRunning(), "Invariant: isRunning() should be false when isAlive() is false");
  }

  /**
   * Production scenario: stop() called before start().
   * Should handle null DockerClient gracefully.
   */
  @Test
  void testStopBeforeStart() {
    Properties properties = new Properties();
    DockerInterpreterProcess notStarted = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, null);

    // Should not throw NPE
    notStarted.stop();
  }
}
