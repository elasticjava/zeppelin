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

import java.io.IOException;

/**
 * Interface to InterpreterClient which is created by InterpreterLauncher. This is the component
 * that is used for the communication from zeppelin-server process to zeppelin interpreter
 * process and also manage the lifecycle of interpreter process.
 */
public interface InterpreterClient {

  /**
   * InterpreterGroupId that is associated with this interpreter process.
   *
   * @return
   */
  String getInterpreterGroupId();

  /**
   * InterpreterSetting name of this interpreter process.
   *
   * @return
   */
  String getInterpreterSettingName();

  /**
   * Start interpreter process.
   *
   * @param userName
   * @throws IOException
   */
  void start(String userName) throws IOException;

  /**
   * Stop interpreter process.
   *
   */
  void stop();

  /**
   * Host name of interpreter process thrift server
   *
   * @return
   */
  String getHost();

  /**
   * Port of interpreter process thrift server
   *
   * @return
   */
  int getPort();

  /**
   * Checks if the interpreter process is alive.
   *
   * <p>An interpreter is considered <b>alive</b> if its execution unit
   * (container, YARN application, process) exists and is in a non-terminal state.
   *
   * <p>Examples of alive states:
   * <ul>
   *   <li>YARN: Non-terminal states (e.g., ACCEPTED, SUBMITTED, RUNNING)</li>
   *   <li>Docker: Non-terminal container states (e.g., created, running, paused, restarting)</li>
   *   <li>Kubernetes: Pending, Running</li>
   * </ul>
   *
   * <p><b>Invariant:</b> {@code isRunning() => isAlive()} must always hold.
   * This means: if {@code isRunning()} returns {@code true}, then {@code isAlive()}
   * must also return {@code true}. The reverse is not required - a process can be
   * alive without being fully running (e.g., in a starting or restarting state).
   *
   * @return true if the process exists and is not in a terminal state
   */
  boolean isAlive();

  /**
   * Checks if the interpreter process is running and ready.
   *
   * <p>An interpreter is <b>running</b> if it is in an active execution state
   * (e.g., RUNNING for YARN, running for Docker). Implementations may additionally
   * check communication readiness (e.g., remote endpoint accessibility), but this
   * is an implementation detail, not a requirement of the definition.
   *
   * <p><b>Invariant:</b> If this returns {@code true}, {@link #isAlive()} must also
   * return {@code true}.
   *
   * @return true if the process is actively running
   */
  boolean isRunning();

  /**
   * Return true if recovering successfully, otherwise return false.
   *
   * @return
   */
  boolean recover();
}
