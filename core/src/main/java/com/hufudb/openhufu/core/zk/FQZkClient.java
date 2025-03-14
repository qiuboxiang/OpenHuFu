package com.hufudb.openhufu.core.zk;

import com.hufudb.openhufu.core.sql.rel.FQTable;
import com.hufudb.openhufu.core.sql.schema.FQSchemaManager;
import com.hufudb.openhufu.core.table.GlobalTableConfig;
import com.hufudb.openhufu.core.table.LocalTableConfig;
import com.hufudb.openhufu.core.zk.watcher.EndpointWatcher;
import com.hufudb.openhufu.core.zk.watcher.GlobalTableWatcher;
import com.hufudb.openhufu.core.zk.watcher.LocalTableWatcher;
import java.util.List;
import org.apache.calcite.schema.Table;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;

public class FQZkClient extends ZkClient {

  private final FQSchemaManager manager;
  private final String schemaDirectoryPath;

  public FQZkClient(ZkConfig zkConfig, FQSchemaManager manager) {
    super(zkConfig.servers, zkConfig.zkRoot);
    this.schemaDirectoryPath = buildPath(schemaRootPath, zkConfig.schemaName);
    this.manager = manager;
    loadZkTable();
  }

  public void loadZkTable() {
    try {
      watchEndpoints();
      watchSchemaDirectory();
    } catch (KeeperException | InterruptedException e) { //NOSONAR
      LOG.error("Error when load zk table", e);
    }
    LOG.info("Load table from zk success");
  }

  private void watchEndpoints() throws KeeperException, InterruptedException {
    List<String> endpoints =
        zk.getChildren(endpointRootPath, new EndpointWatcher(manager, zk, endpointRootPath));
    for (String endpoint : endpoints) {
      manager.addOwner(endpoint, null);
    }
  }

  private void watchSchemaDirectory() throws KeeperException, InterruptedException {
    if (zk.exists(schemaDirectoryPath, false) == null) {
      LOG.info("Create Schema Directory: {}", schemaDirectoryPath);
      zk.create(schemaDirectoryPath, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    } else {
      LOG.info("Schema Directory {} already exists", schemaDirectoryPath);
    }
    List<String> globalTables = zk.getChildren(schemaDirectoryPath, false);
    for (String globalTable : globalTables) {
      watchGlobalTable(globalTable);
    }
  }

  public void watchGlobalTable(String tableName) throws KeeperException, InterruptedException {
    String gPath = buildPath(schemaDirectoryPath, tableName);
    List<String> endpoints = zk.getChildren(gPath, null);
    GlobalTableConfig tableMeta = new GlobalTableConfig(tableName);
    for (String endpoint : endpoints) {
      manager.addOwner(endpoint, null);
      String localTableName = watchLocalTable(buildPath(gPath, endpoint));
      tableMeta.localTables.add(new LocalTableConfig(endpoint, localTableName));
    }
    Table table = FQTable.create(manager, tableMeta);
    if (table != null) {
      manager.addTable(tableName, table);
      zk.getChildren(gPath, new GlobalTableWatcher(manager, zk, gPath));
    }
  }

  private String watchLocalTable(String lPath) throws KeeperException, InterruptedException {
    return new String(zk.getData(lPath, new LocalTableWatcher(manager, zk, lPath), null));
  }
}
