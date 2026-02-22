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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for DockerInterpreterProcess covering:
 * - Real Docker API interactions and state mapping
 * - Concurrent health check scenarios (thread-safety)
 * - Grace window and fail-closed policy behavior
 * - Edge cases and error handling
 */
class DockerInterpreterProcessTest {

  protected static ZeppelinConfiguration zConf = spy(ZeppelinConfiguration.load());

  // ==================== Basic Configuration Tests ====================

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

    // Verify all key properties
    assertEquals("name", process.getInterpreterSettingName());
    assertEquals("/opt/spark", process.containerSparkHome);
    assertTrue(process.uploadLocalLibToContainter);
    assertNotNull(process.containerName);
    assertTrue(process.containerName.startsWith("zeppelin-"));
  }

  @Test
  void testEnvConfiguration() throws IOException {
    // Mock custom configuration
    when(zConf.getString(ConfVars.ZEPPELIN_DOCKER_CONTAINER_SPARK_HOME))
        .thenReturn("/custom/spark");
    when(zConf.getBoolean(ConfVars.ZEPPELIN_DOCKER_UPLOAD_LOCAL_LIB_TO_CONTAINTER))
        .thenReturn(false);
    when(zConf.getString(ConfVars.ZEPPELIN_DOCKER_HOST))
        .thenReturn("http://custom-docker-host:2375");

    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "5000");

    HashMap<String, String> envs = new HashMap<>();
    envs.put("CUSTOM_VAR", "custom_value");
    envs.put("ANOTHER_VAR", "another_value");

    DockerInterpreterProcess process = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "shared_process", "sh", "shell",
        properties, envs, "zeppelin.server.hostname", 12320, 5000, 10);

    // Verify configuration applied
    assertEquals("/custom/spark", process.containerSparkHome);
    assertFalse(process.uploadLocalLibToContainter);
    assertEquals("http://custom-docker-host:2375", process.dockerHost);

    // Verify custom env vars are present
    List<String> listEnvs = process.getListEnvs();
    assertTrue(listEnvs.stream().anyMatch(e -> e.startsWith("CUSTOM_VAR=")));
    assertTrue(listEnvs.stream().anyMatch(e -> e.startsWith("ANOTHER_VAR=")));
  }

  @Test
  void testTemplateBindingsCompleteness() throws IOException {
    Properties properties = new Properties();
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT.getVarName(), "8000");
    properties.setProperty(ConfVars.ZEPPELIN_INTERPRETER_RPC_PORTRANGE.getVarName(), "30000:31000");

    HashMap<String, String> envs = new HashMap<>();
    envs.put("TEST_ENV", "test_value");

    DockerInterpreterProcess process = new DockerInterpreterProcess(
        zConf, "interpreter-container:2.0", "test_group_id", "test_group",
        "test_setting", properties, envs, "test.host", 12345, 8000, 15);

    Properties bindings = process.getTemplateBindings();

    // Verify all required bindings
    assertEquals(10, bindings.size());
    assertNotNull(bindings.get("CONTAINER_ZEPPELIN_HOME"));
    assertEquals("interpreter-container:2.0", bindings.get("zeppelin.interpreter.container.image"));
    assertEquals("test_group_id", bindings.get("zeppelin.interpreter.group.id"));
    assertEquals("test_group", bindings.get("zeppelin.interpreter.group.name"));
    assertEquals("test_setting", bindings.get("zeppelin.interpreter.setting.name"));
    assertEquals("test.host", bindings.get("zeppelin.server.rpc.host"));
    assertEquals("8000", bindings.get("zeppelin.interpreter.connect.timeout"));

    // Verify environment variables
    List<String> listEnvs = process.getListEnvs();
    Map<String, String> envMap = new HashMap<>();
    for (String env : listEnvs) {
      String[] parts = env.split("=", 2);
      if (parts.length == 2) {
        envMap.put(parts[0], parts[1]);
      }
    }

    // Standard env vars
    assertTrue(envMap.containsKey("ZEPPELIN_HOME"));
    assertTrue(envMap.containsKey("ZEPPELIN_CONF_DIR"));
    assertTrue(envMap.containsKey("ZEPPELIN_FORCE_STOP"));
    assertTrue(envMap.containsKey("SPARK_HOME"));

    // Custom env var
    assertEquals("test_value", envMap.get("TEST_ENV"));
  }

  // ==================== Health Check Tests - Real Docker API Scenarios ====================

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
        zConf, "test-image:1.0", "test_group_id", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, mockDockerClient);
  }

  @AfterEach
  void tearDownHealthCheckTests() {
    Thread.interrupted(); // Clear interrupt flag after tests
  }

  /**
   * Tests all real Docker container states that can occur in production.
   * Format: status,running,dead,expectedAlive
   */
  @ParameterizedTest
  @CsvSource({
      // Running states - container is alive
      "running,true,false,true",
      "paused,false,false,true",
      "created,false,false,true",
      "restarting,false,false,true",
      // Terminal states - container is dead
      "exited,false,false,false",
      "dead,false,true,false",
      "removing,false,false,false",
      // Dead flag overrides running flag (should never happen but defensively coded)
      "running,true,true,false",
      // Edge case: running=false but not in terminal set (e.g., "paused")
      "paused,true,false,true"
  })
  void testContainerStateMapping(String status, boolean running, boolean dead,
                                  boolean expectedAlive) throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(status);
    when(mockContainerState.running()).thenReturn(running);
    when(mockContainerState.dead()).thenReturn(dead);

    boolean actual = process.isAlive();
    assertEquals(expectedAlive, actual,
        String.format("Container state %s (running=%s, dead=%s) should be %s",
            status, running, dead, expectedAlive ? "alive" : "dead"));
  }

  /**
   * Tests case sensitivity of Docker status strings.
   * Docker API returns lowercase status, but we should handle mixed case defensively.
   */
  @ParameterizedTest
  @ValueSource(strings = {"exited", "EXITED", "Exited", "eXiTeD"})
  void testContainerStateCaseInsensitivity(String status) throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(status);
    when(mockContainerState.running()).thenReturn(false);
    when(mockContainerState.dead()).thenReturn(false);

    assertFalse(process.isAlive(),
        "Terminal state '" + status + "' should be recognized regardless of case");
  }

  /**
   * Production scenario: Container was deleted externally (docker rm, node failure, etc.).
   * Should immediately return false and reset health check counters.
   */
  @Test
  void testContainerNotFound() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new ContainerNotFoundException(process.containerName, null));

    assertFalse(process.isAlive(), "Deleted container should not be alive");

    // Verify counters are reset (next failure should be #1, not #2)
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Transient error", 500));

    assertTrue(process.isAlive(),
        "After container-not-found reset, transient error should fail-open");
  }

  /**
   * Production scenario: Docker daemon returns null state (corrupted response, API bug).
   * Should throw exception and trigger grace window logic.
   */
  @Test
  void testNullContainerState() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(null);

    // First call - should fail-open during grace period
    assertTrue(process.isAlive(), "Null state should fail-open during initial grace period");

    // Subsequent calls within grace window
    assertTrue(process.isAlive(), "Null state should continue fail-open");
  }

  /**
   * Production scenario: Docker API returns state with null status field.
   * Should trigger transient error handling.
   */
  @Test
  void testNullContainerStatus() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn(null);

    // Should fail-open during grace period
    assertTrue(process.isAlive(), "Null status should fail-open during grace period");
  }

  /**
   * Production scenario: Process created but start() never called.
   * DockerClient is null, container name may be set.
   */
  @Test
  void testBeforeStart() {
    Properties properties = new Properties();
    DockerInterpreterProcess notStarted = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, null);

    assertFalse(notStarted.isAlive(), "Process before start() should not be alive");
    assertFalse(notStarted.isRunning(), "Process before start() should not be running");
  }

  /**
   * Production scenario: Docker daemon timeout, network partition, or API error.
   * Should fail-open initially (grace window), then fail-closed after MAX_CONSECUTIVE_FAILURES.
   */
  @Test
  void testTransientDockerErrorFailClosedPolicy() throws Exception {
    DockerException transientError = new DockerException("Connection timeout", 500);
    when(mockDockerClient.inspectContainer(process.containerName)).thenThrow(transientError);

    // First 2 failures (MAX_CONSECUTIVE_FAILURES = 2) - should fail-open
    assertTrue(process.isAlive(), "Failure #1 should fail-open during grace period");
    assertTrue(process.isAlive(), "Failure #2 should fail-open during grace period");

    // Third failure - exceeds MAX_CONSECUTIVE_FAILURES, should fail-closed
    assertFalse(process.isAlive(), "Failure #3 should fail-closed (exceeded max failures)");

    // Subsequent failures should remain closed
    assertFalse(process.isAlive(), "Failure #4 should remain fail-closed");
  }

  /**
   * Production scenario: Transient error followed by recovery.
   * Counters should reset on successful check, allowing new transient errors to fail-open.
   */
  @Test
  void testTransientErrorRecoveryResetsCounters() throws Exception {
    // Phase 1: Transient error
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Network glitch", 500));

    assertTrue(process.isAlive(), "Transient error should fail-open");

    // Phase 2: Recovery - container is running
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("running");
    when(mockContainerState.running()).thenReturn(true);
    when(mockContainerState.dead()).thenReturn(false);

    assertTrue(process.isAlive(), "Recovered container should be alive");

    // Phase 3: New transient error - counters were reset, should fail-open again
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Another network glitch", 500));

    assertTrue(process.isAlive(),
        "New transient error should fail-open (counters were reset by recovery)");
  }

  /**
   * Production scenario: Thread interrupted during Docker API call (e.g., shutdown).
   * Should restore interrupt flag and apply fail-open grace window.
   */
  @Test
  void testInterruptHandling() throws Exception {
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new InterruptedException("Thread interrupted"));

    // Should fail-open during grace period
    assertTrue(process.isAlive(), "Interrupted check should fail-open during grace period");

    // Interrupt flag should be restored for upstream handlers
    assertTrue(Thread.interrupted(), "Interrupt flag must be restored after InterruptedException");
  }

  /**
   * Critical invariant: isRunning() should never return true when isAlive() returns false.
   * This prevents inconsistent state that could cause interpreter restart loops.
   */
  @Test
  void testInvariantIsRunningImpliesIsAlive() throws Exception {
    // Test with terminal state
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("exited");
    when(mockContainerState.running()).thenReturn(false);
    when(mockContainerState.dead()).thenReturn(false);

    assertFalse(process.isAlive());
    assertFalse(process.isRunning(),
        "Invariant violated: isRunning() should be false when isAlive() is false");

    // Test with container not found
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new ContainerNotFoundException(process.containerName, null));

    assertFalse(process.isAlive());
    assertFalse(process.isRunning(),
        "Invariant violated: isRunning() should be false when container not found");
  }

  /**
   * Production scenario: stop() called before start() (e.g., launcher error).
   * Should handle null DockerClient gracefully without NPE.
   */
  @Test
  void testStopBeforeStart() {
    Properties properties = new Properties();
    DockerInterpreterProcess notStarted = new DockerInterpreterProcess(
        zConf, "test-image:1.0", "test_group", "test-group", "test-setting",
        properties, new HashMap<>(), "localhost", 12345, 5000, 10, null);

    // Should not throw NPE
    notStarted.stop();

    assertFalse(notStarted.isAlive());
    assertFalse(notStarted.isRunning());
  }

  // ==================== Concurrent Health Check Tests (Async Behavior) ====================

  /**
   * Tests thread-safety of concurrent isAlive() calls.
   * Multiple threads call isAlive() simultaneously - no race conditions should occur.
   */
  @Test
  void testConcurrentHealthChecksThreadSafety() throws Exception {
    // Setup: Container is running
    when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
    when(mockContainerInfo.state()).thenReturn(mockContainerState);
    when(mockContainerState.status()).thenReturn("running");
    when(mockContainerState.running()).thenReturn(true);
    when(mockContainerState.dead()).thenReturn(false);

    int numThreads = 20;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    List<Future<Boolean>> futures = new ArrayList<>();

    try {
      // Launch concurrent isAlive() calls
      for (int i = 0; i < numThreads; i++) {
        futures.add(executor.submit(() -> {
          barrier.await(); // Synchronize start
          return process.isAlive();
        }));
      }

      // All calls should return true (container is running)
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(5, TimeUnit.SECONDS),
            "Concurrent health check should return true");
      }
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests counter consistency under concurrent failures.
   * Multiple threads fail simultaneously - failure counter should be accurate.
   */
  @Test
  void testConcurrentFailureCounterConsistency() throws Exception {
    // Setup: All calls will fail with transient error
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Timeout", 500));

    int numThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    List<Future<Boolean>> futures = new ArrayList<>();

    try {
      // Launch concurrent failing health checks
      for (int i = 0; i < numThreads; i++) {
        futures.add(executor.submit(() -> {
          barrier.await(); // Synchronize start
          return process.isAlive();
        }));
      }

      // Wait for all to complete
      for (Future<Boolean> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }

      // After concurrent failures, subsequent check should fail-closed
      // (counter should be >= MAX_CONSECUTIVE_FAILURES)
      assertFalse(process.isAlive(),
          "After concurrent failures exceeding max, should fail-closed");
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests race condition: concurrent success and failure.
   * One thread sees success (resets counters), another sees failure.
   * The failure counter should be correctly reset.
   */
  @Test
  void testConcurrentSuccessAndFailureRaceCondition() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);

    // Setup: Alternating success/failure based on call order
    when(mockDockerClient.inspectContainer(anyString())).thenAnswer(invocation -> {
      int count = callCount.incrementAndGet();
      if (count % 2 == 0) {
        // Even calls: success
        when(mockContainerInfo.state()).thenReturn(mockContainerState);
        when(mockContainerState.status()).thenReturn("running");
        when(mockContainerState.running()).thenReturn(true);
        when(mockContainerState.dead()).thenReturn(false);
        return mockContainerInfo;
      } else {
        // Odd calls: failure
        throw new DockerException("Transient error", 500);
      }
    });

    int numThreads = 20;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    List<Future<Boolean>> futures = new ArrayList<>();

    try {
      for (int i = 0; i < numThreads; i++) {
        futures.add(executor.submit(() -> {
          barrier.await();
          return process.isAlive();
        }));
      }

      // All should return true (either success or fail-open)
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(5, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests async state transition: running → transient errors → terminal state.
   * Simulates real-world scenario where container crashes during health checks.
   */
  @Test
  void testAsyncStateTransitionRunningToTerminal() throws Exception {
    AtomicInteger phase = new AtomicInteger(0);

    when(mockDockerClient.inspectContainer(anyString())).thenAnswer(invocation -> {
      int currentPhase = phase.get();
      if (currentPhase == 0) {
        // Phase 0: Container running
        when(mockContainerInfo.state()).thenReturn(mockContainerState);
        when(mockContainerState.status()).thenReturn("running");
        when(mockContainerState.running()).thenReturn(true);
        when(mockContainerState.dead()).thenReturn(false);
        return mockContainerInfo;
      } else if (currentPhase == 1) {
        // Phase 1: Transient errors (container crashing)
        throw new DockerException("Timeout", 500);
      } else {
        // Phase 2: Container exited
        when(mockContainerInfo.state()).thenReturn(mockContainerState);
        when(mockContainerState.status()).thenReturn("exited");
        when(mockContainerState.running()).thenReturn(false);
        when(mockContainerState.dead()).thenReturn(false);
        return mockContainerInfo;
      }
    });

    // Phase 0: Healthy
    assertTrue(process.isAlive(), "Phase 0: Container should be alive");

    // Phase 1: Crashing (transient errors)
    phase.set(1);
    assertTrue(process.isAlive(), "Phase 1: Transient error #1 should fail-open");
    assertTrue(process.isAlive(), "Phase 1: Transient error #2 should fail-open");

    // Phase 2: Crashed (terminal state)
    phase.set(2);
    assertFalse(process.isAlive(), "Phase 2: Exited container should not be alive");
  }

  /**
   * Tests blocking behavior: one thread blocks in inspectContainer(),
   * other threads should not be blocked (no synchronization on isAlive).
   */
  @Test
  void testNonBlockingConcurrentHealthChecks() throws Exception {
    CountDownLatch blockingCallStarted = new CountDownLatch(1);
    CountDownLatch blockingCallCanFinish = new CountDownLatch(1);
    CountDownLatch otherCallsCompleted = new CountDownLatch(5);

    // First call blocks, subsequent calls succeed immediately
    AtomicInteger callCount = new AtomicInteger(0);
    when(mockDockerClient.inspectContainer(anyString())).thenAnswer(invocation -> {
      int count = callCount.incrementAndGet();
      if (count == 1) {
        // First call: block
        blockingCallStarted.countDown();
        assertTrue(blockingCallCanFinish.await(10, TimeUnit.SECONDS));
      }
      // All calls eventually succeed
      when(mockContainerInfo.state()).thenReturn(mockContainerState);
      when(mockContainerState.status()).thenReturn("running");
      when(mockContainerState.running()).thenReturn(true);
      when(mockContainerState.dead()).thenReturn(false);
      return mockContainerInfo;
    });

    ExecutorService executor = Executors.newFixedThreadPool(6);
    try {
      // Launch blocking call
      executor.submit(() -> {
        process.isAlive();
        return null;
      });

      // Wait for blocking call to start
      assertTrue(blockingCallStarted.await(5, TimeUnit.SECONDS));

      // Launch other calls - should not be blocked
      for (int i = 0; i < 5; i++) {
        executor.submit(() -> {
          process.isAlive();
          otherCallsCompleted.countDown();
          return null;
        });
      }

      // Other calls should complete even while first is blocked
      assertTrue(otherCallsCompleted.await(2, TimeUnit.SECONDS),
          "Other health checks should not be blocked");

      // Release blocking call
      blockingCallCanFinish.countDown();
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests grace window behavior under concurrent load.
   * Many threads fail concurrently, then one succeeds - all subsequent should fail-open.
   */
  @Test
  void testGraceWindowResetUnderConcurrentLoad() throws Exception {
    // Phase 1: All fail
    when(mockDockerClient.inspectContainer(process.containerName))
        .thenThrow(new DockerException("Timeout", 500));

    ExecutorService executor = Executors.newFixedThreadPool(10);
    try {
      // Generate failures
      List<Future<?>> failures = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        failures.add(executor.submit(() -> process.isAlive()));
      }
      for (Future<?> f : failures) {
        f.get(5, TimeUnit.SECONDS);
      }

      // Phase 2: One thread succeeds
      when(mockDockerClient.inspectContainer(process.containerName)).thenReturn(mockContainerInfo);
      when(mockContainerInfo.state()).thenReturn(mockContainerState);
      when(mockContainerState.status()).thenReturn("running");
      when(mockContainerState.running()).thenReturn(true);
      when(mockContainerState.dead()).thenReturn(false);

      assertTrue(process.isAlive(), "Success should reset counters");

      // Phase 3: New concurrent failures - should fail-open (counters reset)
      when(mockDockerClient.inspectContainer(process.containerName))
          .thenThrow(new DockerException("New timeout", 500));

      List<Future<Boolean>> newFailures = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        newFailures.add(executor.submit(() -> process.isAlive()));
      }

      // First few should fail-open (within MAX_CONSECUTIVE_FAILURES)
      assertTrue(newFailures.get(0).get(5, TimeUnit.SECONDS),
          "After counter reset, new failures should fail-open");
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }
}
