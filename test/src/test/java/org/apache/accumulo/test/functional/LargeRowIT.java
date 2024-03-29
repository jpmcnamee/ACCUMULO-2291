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

import java.util.Collections;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeSet;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.test.TestIngest;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.junit.Test;

public class LargeRowIT extends ConfigurableMacIT {
  
  @Override
  public void configure(MiniAccumuloConfigImpl cfg) {
    cfg.setSiteConfig(Collections.singletonMap(Property.TSERV_MAJC_DELAY.getKey(), "10ms"));
  }

  private static final int SEED = 42;
  private static final String REG_TABLE_NAME = "lr";
  private static final String PRE_SPLIT_TABLE_NAME = "lrps";
  private static final int NUM_ROWS = 100;
  private static final int ROW_SIZE = 1 << 17;
  private static final int NUM_PRE_SPLITS = 9;
  private static final int SPLIT_THRESH = ROW_SIZE * NUM_ROWS / NUM_PRE_SPLITS;
  
  @Test(timeout = 4 * 60 * 1000)
  public void run() throws Exception {
    Random r = new Random();
    byte rowData[] = new byte[ROW_SIZE];
    r.setSeed(SEED + 1);
    TreeSet<Text> splitPoints = new TreeSet<Text>();
    for (int i = 0; i < NUM_PRE_SPLITS; i++) {
      r.nextBytes(rowData);
      TestIngest.toPrintableChars(rowData);
      splitPoints.add(new Text(rowData));
    }
    Connector c = getConnector();
    c.tableOperations().create(REG_TABLE_NAME);
    c.tableOperations().create(PRE_SPLIT_TABLE_NAME);
    c.tableOperations().addSplits(PRE_SPLIT_TABLE_NAME, splitPoints);
    test1(c);
    test2(c);
  }
  
  private void test1(Connector c) throws Exception {
    
    basicTest(c, REG_TABLE_NAME, 0);
    
    c.tableOperations().setProperty(REG_TABLE_NAME, Property.TABLE_SPLIT_THRESHOLD.getKey(), "" + SPLIT_THRESH);
    
    UtilWaitThread.sleep(12000);
    Logger.getLogger(LargeRowIT.class).warn("checking splits");
    FunctionalTestUtils.checkSplits(c, REG_TABLE_NAME, NUM_PRE_SPLITS / 2, NUM_PRE_SPLITS * 4);
    
    verify(c, REG_TABLE_NAME);
  }
  
  private void test2(Connector c) throws Exception {
    basicTest(c, PRE_SPLIT_TABLE_NAME, NUM_PRE_SPLITS);
  }
  
  private void basicTest(Connector c, String table, int expectedSplits) throws Exception {
    BatchWriter bw = c.createBatchWriter(table, new BatchWriterConfig());
    
    Random r = new Random();
    byte rowData[] = new byte[ROW_SIZE];
    
    r.setSeed(SEED);
    
    for (int i = 0; i < NUM_ROWS; i++) {
      
      r.nextBytes(rowData);
      TestIngest.toPrintableChars(rowData);
      
      Mutation mut = new Mutation(new Text(rowData));
      mut.put(new Text(""), new Text(""), new Value(("" + i).getBytes()));
      bw.addMutation(mut);
    }
    
    bw.close();
    
    FunctionalTestUtils.checkSplits(c, table, expectedSplits, expectedSplits);
    
    verify(c, table);
    
    FunctionalTestUtils.checkSplits(c, table, expectedSplits, expectedSplits);
    
    c.tableOperations().flush(table, null, null, false);
    
    // verify while table flush is running
    verify(c, table);
    
    // give split time to complete
    c.tableOperations().flush(table, null, null, true);
    
    FunctionalTestUtils.checkSplits(c, table, expectedSplits, expectedSplits);
    
    verify(c, table);
    
    FunctionalTestUtils.checkSplits(c, table, expectedSplits, expectedSplits);
  }
  
  private void verify(Connector c, String table) throws Exception {
    Random r = new Random();
    byte rowData[] = new byte[ROW_SIZE];
    
    r.setSeed(SEED);
    
    Scanner scanner = c.createScanner(table, Authorizations.EMPTY);
    
    for (int i = 0; i < NUM_ROWS; i++) {
      
      r.nextBytes(rowData);
      TestIngest.toPrintableChars(rowData);
      
      scanner.setRange(new Range(new Text(rowData)));
      
      int count = 0;
      
      for (Entry<Key,Value> entry : scanner) {
        if (!entry.getKey().getRow().equals(new Text(rowData))) {
          throw new Exception("verification failed, unexpected row i =" + i);
        }
        if (!entry.getValue().equals(Integer.toString(i).getBytes())) {
          throw new Exception("verification failed, unexpected value i =" + i + " value = " + entry.getValue());
        }
        count++;
      }
      
      if (count != 1) {
        throw new Exception("verification failed, unexpected count i =" + i + " count=" + count);
      }
      
    }
    
  }
  
}
