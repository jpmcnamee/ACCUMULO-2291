/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test.functional;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.junit.Test;

/**
 * Do a selection of ITs with SSL turned on that cover a range of different connection scenarios. Note that you can run *all* the ITs against SSL-enabled mini
 * clusters with `mvn verify -DuseSslForIT`
 *
 */
public class SslIT extends ConfigurableMacIT {
  @Override
  public void configure(MiniAccumuloConfigImpl cfg) {
    super.configure(cfg);
    configureForSsl(cfg, createSharedTestDir(this.getClass().getName() + "-ssl"));
  }

  @Test(timeout = 60 * 1000)
  public void binary() throws AccumuloException, AccumuloSecurityException, Exception {
    getConnector().tableOperations().create("bt");
    BinaryIT.runTest(getConnector());
  }

  @Test(timeout = 2 * 60 * 1000)
  public void concurrency() throws Exception {
    ConcurrencyIT.runTest(getConnector());
  }

  @Test(timeout = 2 * 60 * 1000)
  public void adminStop() throws Exception {
    ShutdownIT.runAdminStopTest(getConnector(), getCluster());
  }

  @Test(timeout = 2 * 60 * 1000)
  public void bulk() throws Exception {
    BulkIT.runTest(getConnector(), getTableNames(1)[0]);
  }

  @Test(timeout = 60 * 1000)
  public void mapReduce() throws Exception {
    MapReduceIT.runTest(getConnector(), getCluster());
  }

}
