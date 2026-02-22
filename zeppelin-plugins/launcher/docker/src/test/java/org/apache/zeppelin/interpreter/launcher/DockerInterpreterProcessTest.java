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
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.ContainerState;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.interpreter.InterpreterOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

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

  // ==================== Tests for isAlive() and isRunning() semantics ====================

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
    // Clear interrupt flag to prevent side effects on other tests
    Thread.interrupted();
  }

  /**
   * Provides test data for Docker container state mapping.
   * Each argument contains: [status, running, paused, dead, expectedIsAlive]
   */
  static Stream<Arguments> dockerStateProvider() {
    return Stream.of(
        // Non-terminal states -> alive
        Arguments.of("created", false, false, false, true),
        Arguments.of("running", true, false, false, true),
        Arguments.of("paused", false, true, false, true),
        Arguments.of("restarting", false, false, false, true),

        // Terminal states -> not alive
        Arguments.of("exited", false, false, false, false),
        Arguments.of("dead", false, false, true, false),
        Arguments.of("removing", false, false, false, false),

        // Dead flag overrides status
        Arguments.of("running", true, false, true, false)  // Dead flag takes precedence
    );
  }

  /**
   * Verifies that isAlive() correctly maps Docker container states.
   * Tests the core logic without mocking - uses real state transitions.
   */
  @ParameterizedTest
  @MethodSource("dockerStateProvider")
  void testIsAliveForDockerStates(String status,
                                   Boolean running,
                                   Boolean paused,
                                   Boolean dead,
                                   boolean expectedAlive) throws Exception {
    // Setup mock behavior
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(status);
    when(mockContainerState.running()).thenReturn(running);
    when(mockContainerState.paused()).thenReturn(paused);
    when(mockContainerState.dead()).thenReturn(dead);

    // Execute
    boolean result = process.isAlive();

    // Verify
    assertEquals(expectedAlive, result,
        String.format("State %s (running=%s, paused=%s, dead=%s) should be %s",
            status, running, paused, dead, expectedAlive ? "alive" : "not alive"));
  }

  /**
   * Tests the invariant: isRunning() implies isAlive()
   * This ensures that we never report a container as running when it's not alive.
   */
  @Test
  void testInvariantIsRunningImpliesIsAlive() throws Exception {
    // Setup: Container is in terminal state (not alive)
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("exited");
    when(mockContainerState.running()).thenReturn(false);
    when(mockContainerState.dead()).thenReturn(false);

    // Even if remote endpoint is accessible, isRunning() should return false
    // because isAlive() returns false
    boolean alive = process.isAlive();
    boolean running = process.isRunning();

    assertFalse(alive, "Container in 'exited' state should not be alive");
    assertFalse(running, "Invariant violated: isRunning() returned true when isAlive() is false");
  }

  /**
   * Tests that container not found is handled correctly.
   * When a container doesn't exist, isAlive() should return false.
   */
  @Test
  void testIsAliveWhenContainerNotFound() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new com.spotify.docker.client.exceptions.ContainerNotFoundException(
            process.containerName, null));

    boolean result = process.isAlive();

    assertFalse(result, "Container that doesn't exist should not be alive");
  }

  /**
   * Tests case-insensitive status comparison.
   * Docker may return status in different cases depending on version.
   */
  @Test
  void testDockerStatusCaseInsensitive() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("RUNNING");  // Uppercase
    when(mockContainerState.running()).thenReturn(true);
    when(mockContainerState.dead()).thenReturn(false);

    boolean result = process.isAlive();

    assertTrue(result, "Status comparison should be case-insensitive");
  }
}
