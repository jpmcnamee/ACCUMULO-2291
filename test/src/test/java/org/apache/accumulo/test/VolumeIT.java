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
package org.apache.accumulo.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.admin.DiskUsage;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.server.ServerConstants;
import org.apache.accumulo.server.init.Initialize;
import org.apache.accumulo.server.util.Admin;
import org.apache.accumulo.test.functional.ConfigurableMacIT;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class VolumeIT extends ConfigurableMacIT {

  private static final Text EMPTY = new Text();
  private static final Value EMPTY_VALUE = new Value(new byte[] {});
  public static File volDirBase;
  public static Path v1;
  public static Path v2;

  @BeforeClass
  public static void createVolumeDirs() throws IOException {
    volDirBase = createSharedTestDir(VolumeIT.class.getName() + "-volumes");
    File v1f = new File(volDirBase, "v1");
    File v2f = new File(volDirBase, "v2");
    v1f.mkdir();
    v2f.mkdir();
    v1 = new Path("file://" + v1f.getAbsolutePath());
    v2 = new Path("file://" + v2f.getAbsolutePath());
  }

  @After
  public void clearDirs() throws IOException {
    FileUtils.deleteDirectory(new File(v1.toUri()));
    FileUtils.deleteDirectory(new File(v2.toUri()));
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg) {
    // Run MAC on two locations in the local file system
    cfg.setProperty(Property.INSTANCE_DFS_URI, v1.toString());
    cfg.setProperty(Property.INSTANCE_DFS_DIR, "/accumulo");
    cfg.setProperty(Property.INSTANCE_VOLUMES, v1.toString() + "," + v2.toString());
    super.configure(cfg);
  }

  @Test
  public void test() throws Exception {
    // create a table
    Connector connector = getConnector();
    String tableName = getTableNames(1)[0];
    connector.tableOperations().create(tableName);
    SortedSet<Text> partitions = new TreeSet<Text>();
    // with some splits
    for (String s : "d,m,t".split(","))
      partitions.add(new Text(s));
    connector.tableOperations().addSplits(tableName, partitions);
    // scribble over the splits
    BatchWriter bw = connector.createBatchWriter(tableName, new BatchWriterConfig());
    String[] rows = "a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z".split(",");
    for (String s : rows) {
      Mutation m = new Mutation(new Text(s));
      m.put(EMPTY, EMPTY, EMPTY_VALUE);
      bw.addMutation(m);
    }
    bw.close();
    // write the data to disk, read it back
    connector.tableOperations().flush(tableName, null, null, true);
    Scanner scanner = connector.createScanner(tableName, Authorizations.EMPTY);
    int i = 0;
    for (Entry<Key,Value> entry : scanner) {
      assertEquals(rows[i++], entry.getKey().getRow().toString());
    }
    // verify the new files are written to the different volumes
    scanner = connector.createScanner(MetadataTable.NAME, Authorizations.EMPTY);
    scanner.setRange(new Range("1", "1<"));
    scanner.fetchColumnFamily(DataFileColumnFamily.NAME);
    int fileCount = 0;

    for (Entry<Key,Value> entry : scanner) {
      boolean inV1 = entry.getKey().getColumnQualifier().toString().contains(v1.toString());
      boolean inV2 = entry.getKey().getColumnQualifier().toString().contains(v2.toString());
      assertTrue(inV1 || inV2);
      fileCount++;
    }
    assertEquals(4, fileCount);
    List<DiskUsage> diskUsage = connector.tableOperations().getDiskUsage(Collections.singleton(tableName));
    assertEquals(1, diskUsage.size());
    long usage = diskUsage.get(0).getUsage().longValue();
    System.out.println("usage " + usage);
    assertTrue(usage > 700 && usage < 800);
  }

  private void verifyData(List<String> expected, Scanner createScanner) {

    List<String> actual = new ArrayList<String>();

    for (Entry<Key,Value> entry : createScanner) {
      Key k = entry.getKey();
      actual.add(k.getRow() + ":" + k.getColumnFamily() + ":" + k.getColumnQualifier() + ":" + entry.getValue());
    }

    Collections.sort(expected);
    Collections.sort(actual);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testRelativePaths() throws Exception {

    List<String> expected = new ArrayList<String>();

    Connector connector = getConnector();
    String tableName = getTableNames(1)[0];
    connector.tableOperations().create(tableName, false);

    String tableId = connector.tableOperations().tableIdMap().get(tableName);

    SortedSet<Text> partitions = new TreeSet<Text>();
    // with some splits
    for (String s : "c,g,k,p,s,v".split(","))
      partitions.add(new Text(s));

    connector.tableOperations().addSplits(tableName, partitions);

    BatchWriter bw = connector.createBatchWriter(tableName, new BatchWriterConfig());

    // create two files in each tablet

    String[] rows = "a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z".split(",");
    for (String s : rows) {
      Mutation m = new Mutation(s);
      m.put("cf1", "cq1", "1");
      bw.addMutation(m);
      expected.add(s + ":cf1:cq1:1");
    }

    bw.flush();
    connector.tableOperations().flush(tableName, null, null, true);

    for (String s : rows) {
      Mutation m = new Mutation(s);
      m.put("cf1", "cq1", "2");
      bw.addMutation(m);
      expected.add(s + ":cf1:cq1:2");
    }

    bw.close();
    connector.tableOperations().flush(tableName, null, null, true);

    verifyData(expected, connector.createScanner(tableName, Authorizations.EMPTY));

    connector.tableOperations().offline(tableName, true);

    connector.securityOperations().grantTablePermission("root", MetadataTable.NAME, TablePermission.WRITE);

    Scanner metaScanner = connector.createScanner(MetadataTable.NAME, Authorizations.EMPTY);
    metaScanner.fetchColumnFamily(MetadataSchema.TabletsSection.DataFileColumnFamily.NAME);
    metaScanner.setRange(new KeyExtent(new Text(tableId), null, null).toMetadataRange());

    BatchWriter mbw = connector.createBatchWriter(MetadataTable.NAME, new BatchWriterConfig());

    for (Entry<Key,Value> entry : metaScanner) {
      String cq = entry.getKey().getColumnQualifier().toString();
      if (cq.startsWith(v1.toString())) {
        Path path = new Path(cq);
        String relPath = "/" + path.getParent().getName() + "/" + path.getName();
        Mutation fileMut = new Mutation(entry.getKey().getRow());
        fileMut.putDelete(entry.getKey().getColumnFamily(), entry.getKey().getColumnQualifier());
        fileMut.put(entry.getKey().getColumnFamily().toString(), relPath, entry.getValue().toString());
        mbw.addMutation(fileMut);
      }
    }

    mbw.close();

    connector.tableOperations().online(tableName, true);

    verifyData(expected, connector.createScanner(tableName, Authorizations.EMPTY));

    connector.tableOperations().compact(tableName, null, null, true, true);

    verifyData(expected, connector.createScanner(tableName, Authorizations.EMPTY));

    for (Entry<Key,Value> entry : metaScanner) {
      String cq = entry.getKey().getColumnQualifier().toString();
      Path path = new Path(cq);
      Assert.assertTrue("relative path not deleted " + path.toString(), path.depth() > 2);
    }

  }


  @Test
  public void testAddVolumes() throws Exception {

    String[] tableNames = getTableNames(2);

    // grab this before shutting down cluster
    String uuid = new ZooKeeperInstance(cluster.getInstanceName(), cluster.getZooKeepers()).getInstanceID();

    verifyVolumesUsed(tableNames[0], v1, v2);

    Assert.assertEquals(0, cluster.exec(Admin.class, "stopAll").waitFor());
    cluster.stop();
    
    Configuration conf = new Configuration(false);
    conf.addResource(new Path(cluster.getConfig().getConfDir().toURI().toString(), "accumulo-site.xml"));
    
    File v3f = new File(volDirBase, "v3");
    v3f.mkdir();
    Path v3 = new Path("file://" + v3f.getAbsolutePath());
 
    conf.set(Property.INSTANCE_VOLUMES.getKey(), v1.toString() + "," + v2.toString()+","+v3.toString());
    BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(new File(cluster.getConfig().getConfDir(), "accumulo-site.xml")));
    conf.writeXml(fos);
    fos.close();
    
    // initialize volume
    Assert.assertEquals(0, cluster.exec(Initialize.class, "--add-volumes").waitFor());

    // check that all volumes are initialized
    for (Path volumePath : Arrays.asList(v1, v2, v3)) {
      FileSystem fs = volumePath.getFileSystem(CachedConfiguration.getInstance());
      Path vp = new Path(volumePath, "accumulo");
      Path vpi = new Path(vp, ServerConstants.INSTANCE_ID_DIR);
      FileStatus[] iids = fs.listStatus(vpi);
      Assert.assertEquals(1, iids.length);
      Assert.assertEquals(uuid, iids[0].getPath().getName());
    }

    // start cluster and verify that new volume is used
    cluster.start();

    verifyVolumesUsed(tableNames[1], v1, v2, v3);

  }

  private void verifyVolumesUsed(String tableName, Path... paths) throws AccumuloException, AccumuloSecurityException, TableExistsException,
      TableNotFoundException,
      MutationsRejectedException {
    TreeSet<Text> splits = new TreeSet<Text>();
    for (int i = 0; i < 100; i++) {
      splits.add(new Text(String.format("%06d", i * 100)));
    }

    Connector conn = cluster.getConnector("root", ROOT_PASSWORD);
    conn.tableOperations().create(tableName);
    conn.tableOperations().addSplits(tableName, splits);

    List<String> expected = new ArrayList<String>();

    BatchWriter bw = conn.createBatchWriter(tableName, new BatchWriterConfig());
    for (int i = 0; i < 100; i++) {
      String row = String.format("%06d", i * 100 + 3);
      Mutation m = new Mutation(row);
      m.put("cf1", "cq1", "1");
      bw.addMutation(m);
      expected.add(row + ":cf1:cq1:1");
    }

    bw.close();

    verifyData(expected, conn.createScanner(tableName, Authorizations.EMPTY));

    conn.tableOperations().flush(tableName, null, null, true);

    verifyData(expected, conn.createScanner(tableName, Authorizations.EMPTY));

    String tableId = conn.tableOperations().tableIdMap().get(tableName);
    Scanner metaScanner = conn.createScanner(MetadataTable.NAME, Authorizations.EMPTY);
    metaScanner.fetchColumnFamily(MetadataSchema.TabletsSection.DataFileColumnFamily.NAME);
    metaScanner.setRange(new KeyExtent(new Text(tableId), null, null).toMetadataRange());

    int counts[] = new int[paths.length];

    for (Entry<Key,Value> entry : metaScanner) {
      String cq = entry.getKey().getColumnQualifier().toString();
      for (int i = 0; i < paths.length; i++) {
        if (cq.startsWith(paths[i].toString())) {
          counts[i]++;
        }
      }
    }

    // if a volume is chosen randomly for each tablet, then the probability that a volume will not be chosen for any tablet is ((num_volumes -
    // 1)/num_volumes)^num_tablets. For 100 tablets and 3 volumes the probability that only 2 volumes would be chosen is 2.46e-18

    int sum = 0;
    for (int count : counts) {
      Assert.assertTrue(count > 0);
      sum += count;
    }

    Assert.assertEquals(100, sum);
  }

}
