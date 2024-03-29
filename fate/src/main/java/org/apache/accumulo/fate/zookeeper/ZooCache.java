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
package org.apache.accumulo.fate.zookeeper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**
 * Caches values stored in zookeeper and keeps them up to date as they change in zookeeper.
 * 
 */
public class ZooCache {
  private static final Logger log = Logger.getLogger(ZooCache.class);

  private ZCacheWatcher watcher = new ZCacheWatcher();
  private Watcher externalWatcher = null;

  private HashMap<String,byte[]> cache;
  private HashMap<String,Stat> statCache;
  private HashMap<String,List<String>> childrenCache;

  private ZooReader zReader;

  private ZooKeeper getZooKeeper() {
    return zReader.getZooKeeper();
  }

  private class ZCacheWatcher implements Watcher {
    @Override
    public void process(WatchedEvent event) {

      if (log.isTraceEnabled())
        log.trace(event);

      switch (event.getType()) {
        case NodeDataChanged:
        case NodeChildrenChanged:
        case NodeCreated:
        case NodeDeleted:
          remove(event.getPath());
          break;
        case None:
          switch (event.getState()) {
            case Disconnected:
              if (log.isTraceEnabled())
                log.trace("Zoo keeper connection disconnected, clearing cache");
              clear();
              break;
            case SyncConnected:
              break;
            case Expired:
              if (log.isTraceEnabled())
                log.trace("Zoo keeper connection expired, clearing cache");
              clear();
              break;
            default:
              log.warn("Unhandled: " + event);
          }
          break;
        default:
          log.warn("Unhandled: " + event);
      }

      if (externalWatcher != null) {
        externalWatcher.process(event);
      }
    }
  }

  public ZooCache(String zooKeepers, int sessionTimeout) {
    this(zooKeepers, sessionTimeout, null);
  }

  public ZooCache(String zooKeepers, int sessionTimeout, Watcher watcher) {
    this(new ZooReader(zooKeepers, sessionTimeout), watcher);
  }

  public ZooCache(ZooReader reader, Watcher watcher) {
    this.zReader = reader;
    this.cache = new HashMap<String,byte[]>();
    this.statCache = new HashMap<String,Stat>();
    this.childrenCache = new HashMap<String,List<String>>();
    this.externalWatcher = watcher;
  }

  private interface ZooRunnable {
    void run(ZooKeeper zooKeeper) throws KeeperException, InterruptedException;
  }

  private synchronized void retry(ZooRunnable op) {

    int sleepTime = 100;

    while (true) {

      ZooKeeper zooKeeper = getZooKeeper();

      try {
        op.run(zooKeeper);
        return;

      } catch (KeeperException e) {
        if (e.code() == Code.NONODE) {
          log.error("Looked up non existant node in cache " + e.getPath(), e);
        }
        log.warn("Zookeeper error, will retry", e);
      } catch (InterruptedException e) {
        log.info("Zookeeper error, will retry", e);
      } catch (ConcurrentModificationException e) {
        log.debug("Zookeeper was modified, will retry");
      }

      try {
        // do not hold lock while sleeping
        wait(sleepTime);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      if (sleepTime < 10000)
        sleepTime = (int) (sleepTime + sleepTime * Math.random());

    }
  }

  public synchronized List<String> getChildren(final String zPath) {

    ZooRunnable zr = new ZooRunnable() {

      @Override
      public void run(ZooKeeper zooKeeper) throws KeeperException, InterruptedException {

        if (childrenCache.containsKey(zPath))
          return;

        try {
          List<String> children = zooKeeper.getChildren(zPath, watcher);
          childrenCache.put(zPath, children);
        } catch (KeeperException ke) {
          if (ke.code() != Code.NONODE) {
            throw ke;
          }
        }
      }

    };

    retry(zr);

    List<String> children = childrenCache.get(zPath);
    if (children == null) {
      return null;
    }
    return Collections.unmodifiableList(children);
  }

  public synchronized byte[] get(final String zPath) {
    return get(zPath, null);
  }

  public synchronized byte[] get(final String zPath, Stat stat) {
    ZooRunnable zr = new ZooRunnable() {

      @Override
      public void run(ZooKeeper zooKeeper) throws KeeperException, InterruptedException {

        if (cache.containsKey(zPath))
          return;

        /*
         * The following call to exists() is important, since we are caching that a node does not exist. Once the node comes into existance, it will be added to
         * the cache. But this notification of a node coming into existance will only be given if exists() was previously called.
         * 
         * If the call to exists() is bypassed and only getData() is called with a special case that looks for Code.NONODE in the KeeperException, then
         * non-existance can not be cached.
         */

        Stat stat = zooKeeper.exists(zPath, watcher);

        byte[] data = null;

        if (stat == null) {
          if (log.isTraceEnabled())
            log.trace("zookeeper did not contain " + zPath);
        } else {
          try {
            data = zooKeeper.getData(zPath, watcher, stat);
          } catch (KeeperException.BadVersionException e1) {
            throw new ConcurrentModificationException();
          } catch (KeeperException.NoNodeException e2) {
            throw new ConcurrentModificationException();
          }
          if (log.isTraceEnabled())
            log.trace("zookeeper contained " + zPath + " " + (data == null ? null : new String(data)));
        }
        if (log.isTraceEnabled())
          log.trace("putting " + zPath + " " + (data == null ? null : new String(data)) + " in cache");
        put(zPath, data, stat);
      }

    };

    retry(zr);

    if (stat != null) {
      Stat cstat = statCache.get(zPath);
      if (cstat != null) {
        try {
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          DataOutputStream dos = new DataOutputStream(baos);
          cstat.write(dos);
          dos.close();

          ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
          DataInputStream dis = new DataInputStream(bais);
          stat.readFields(dis);

          dis.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    return cache.get(zPath);
  }

  private synchronized void put(String zPath, byte[] data, Stat stat) {
    cache.put(zPath, data);
    statCache.put(zPath, stat);
  }

  private synchronized void remove(String zPath) {
    if (log.isTraceEnabled())
      log.trace("removing " + zPath + " from cache");
    cache.remove(zPath);
    childrenCache.remove(zPath);
    statCache.remove(zPath);
  }

  public synchronized void clear() {
    cache.clear();
    childrenCache.clear();
    statCache.clear();
  }

  public synchronized void clear(String zPath) {

    for (Iterator<String> i = cache.keySet().iterator(); i.hasNext();) {
      String path = i.next();
      if (path.startsWith(zPath))
        i.remove();
    }

    for (Iterator<String> i = childrenCache.keySet().iterator(); i.hasNext();) {
      String path = i.next();
      if (path.startsWith(zPath))
        i.remove();
    }

    for (Iterator<String> i = statCache.keySet().iterator(); i.hasNext();) {
      String path = i.next();
      if (path.startsWith(zPath))
        i.remove();
    }
  }

  private static Map<String,ZooCache> instances = new HashMap<String,ZooCache>();

  public static synchronized ZooCache getInstance(String zooKeepers, int sessionTimeout) {
    String key = zooKeepers + ":" + sessionTimeout;
    ZooCache zc = instances.get(key);
    if (zc == null) {
      zc = new ZooCache(zooKeepers, sessionTimeout);
      instances.put(key, zc);
    }

    return zc;
  }
}
