/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.twostep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheKeyConfiguration;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.managers.communication.GridIoMessage;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearTxQueryEnlistRequest;
import org.apache.ignite.internal.processors.query.h2.twostep.msg.GridH2QueryRequest;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.apache.ignite.internal.processors.query.QueryUtils.DFLT_SCHEMA;

/**
 * Base class for partition pruning tests.
 */
@RunWith(Parameterized.class)
public abstract class AbstractPartitionPruningBaseTest extends GridCommonAbstractTest {
    /** Number of intercepted requests. */
    private static final AtomicInteger INTERCEPTED_REQS = new AtomicInteger();

    /** Partitions tracked during query execution. */
    private static final ConcurrentSkipListSet<Integer> INTERCEPTED_PARTS = new ConcurrentSkipListSet<>();

    /** Partitions tracked during query execution. */
    private static final ConcurrentSkipListSet<ClusterNode> INTERCEPTED_NODES = new ConcurrentSkipListSet<>();

    /** IP finder. */
    private static final TcpDiscoveryVmIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder().setShared(true);

    /** Memory. */
    protected static final String REGION_MEM = "mem";

    /** Disk. */
    protected static final String REGION_DISK = "disk";

    /** Client node name. */
    private static final String CLI_NAME = "cli";

    /** Whether the test table is created with SQL or QueryEntity API. */
    @Parameterized.Parameter
    public boolean createTableWithSql;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        cleanPersistenceDir();

        startGrid(getConfiguration("srv1"));
        startGrid(getConfiguration("srv2"));
        startGrid(getConfiguration("srv3"));

        startClientGrid(getConfiguration(CLI_NAME));

        client().cluster().state(ClusterState.ACTIVE);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        Ignite cli = client();

        cli.destroyCaches(cli.cacheNames());
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();

        super.afterTestsStopped();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String name) throws Exception {
        return super.getConfiguration(name)
            .setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(IP_FINDER))
            .setCommunicationSpi(new TrackingTcpCommunicationSpi())
            .setLocalHost("127.0.0.1")
            .setDataStorageConfiguration(new DataStorageConfiguration()
                .setDataRegionConfigurations(new DataRegionConfiguration()
                    .setName(REGION_DISK)
                    .setPersistenceEnabled(true))
                .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                    .setName(REGION_MEM)
                    .setPersistenceEnabled(false)));
    }

    /**
     * Create PARTITIONED table.
     *
     * @param name Name.
     * @param cols Columns.
     */
    protected void createPartitionedTable(String name, Object... cols) {
        if (createTableWithSql)
            createTable0(name, false, cols);
        else
            createCacheTable(name, false, cols);
    }

    /**
     * Create REPLICATED table.
     *
     * @param name Name.
     * @param cols Columns.
     */
    protected void createReplicatedTable(String name, Object... cols) {
        if (createTableWithSql)
            createTable0(name, true, cols);
        else
            createCacheTable(name, true, cols);
    }

    /**
     * Internal CREATE TABLE routine.
     *
     * @param name Name.
     * @param replicated Replicated table flag.
     * @param cols Columns.
     */
    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    private void createTable0(String name, boolean replicated, Object... cols) {
        List<String> pkCols = new ArrayList<>();

        String affCol = null;

        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(name).append("(");
        for (Object col : cols) {
            Column col0 = col instanceof Column ? (Column)col : new Column((String)col);

            sql.append(col0.name()).append(" VARCHAR, ");

            if (col0.pk())
                pkCols.add(col0.name());

            if (col0.affinity()) {
                if (affCol != null)
                    throw new IllegalStateException("Only one affinity column is allowed: " + col0.name());

                affCol = col0.name();
            }
        }

        if (pkCols.isEmpty())
            throw new IllegalStateException("No PKs!");

        sql.append("PRIMARY KEY (");

        boolean firstPkCol = true;

        for (String pkCol : pkCols) {
            if (firstPkCol)
                firstPkCol = false;
            else
                sql.append(", ");

            sql.append(pkCol);
        }

        sql.append(")");

        sql.append(") WITH \"template=" + (replicated ? "replicated" : "partitioned"));
        sql.append(", CACHE_NAME=" + name);

        if (affCol != null) {
            sql.append(", AFFINITY_KEY=" + affCol);
            sql.append(", KEY_TYPE=" + name + "_key");
        }

        sql.append("\"");

        executeSql(sql.toString());
    }

    /**
     * Create table with the QueryEntity API.
     *
     * @param name Name.
     * @param replicated Replicated table flag.
     * @param cols Columns.
     */
    private void createCacheTable(String name, boolean replicated, Object... cols) {
        QueryEntity e = new QueryEntity()
            .setValueType(name)
            .setTableName(name);

        List<String> pkCols = new ArrayList<>();

        String affCol = null;

        for (Object col : cols) {
            Column col0 = col instanceof Column ? (Column)col : new Column((String)col);

            e.addQueryField(col0.name, String.class.getName(), col0.alias);

            if (col0.pk())
                pkCols.add(col0.name());

            if (col0.affinity()) {
                if (affCol != null)
                    throw new IllegalStateException("Only one affinity column is allowed: " + col0.name());

                affCol = col0.name();

                pkCols.add(affCol);
            }
        }

        if (pkCols.isEmpty())
            throw new IllegalStateException("No PKs!");

        e.setKeyFields(new HashSet<>(pkCols));

        if (pkCols.size() == 1)
            e.setKeyFieldName(pkCols.get(0));

        String keyTypeName = pkCols.size() == 1 ? String.class.getName() : name + "_key";

        e.setKeyType(keyTypeName);

        CacheConfiguration<?, ?> ccfg = new CacheConfiguration<>()
            .setName(name)
            .setSqlSchema(DFLT_SCHEMA)
            .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            .setCacheMode(replicated ? CacheMode.REPLICATED : CacheMode.PARTITIONED)
            .setQueryEntities(Collections.singletonList(e));

        if (affCol != null)
            ccfg.setKeyConfiguration(new CacheKeyConfiguration()
                .setTypeName(keyTypeName)
                .setAffinityKeyFieldName(affCol));

        client().createCache(ccfg);
    }

    /**
     * Execute query with all possible combinations of argument placeholders.
     *
     * @param sql SQL.
     * @param resConsumer Result consumer.
     * @param args Arguments.
     */
    public void execute(String sql, Consumer<List<List<?>>> resConsumer, Object... args) {
        System.out.println(">>> TEST COMBINATION: " + sql);

        // Execute query as is.
        List<List<?>> res = executeSingle(sql, args);

        resConsumer.accept(res);

        // Start filling arguments recursively.
        if (args != null && args.length > 0)
            executeCombinations0(sql, resConsumer, new HashSet<>(), args);

        System.out.println();
    }

    /**
     * Execute query with all possible combinations of argument placeholders.
     *
     * @param sql SQL.
     * @param resConsumer Result consumer.
     * @param executedSqls Already executed SQLs.
     * @param args Arguments.
     */
    private void executeCombinations0(
        String sql,
        Consumer<List<List<?>>> resConsumer,
        Set<String> executedSqls,
        Object... args
    ) {
        assert args != null && args.length > 0;

        // Get argument positions.
        List<Integer> paramPoss = new ArrayList<>();

        int pos = 0;

        while (true) {
            int paramPos = sql.indexOf('?', pos);

            if (paramPos == -1)
                break;

            paramPoss.add(paramPos);

            pos = paramPos + 1;
        }

        for (int i = 0; i < args.length; i++) {
            // Prepare new SQL and arguments.
            int paramPos = paramPoss.get(i);

            String newSql = sql.substring(0, paramPos) + args[i] + sql.substring(paramPos + 1);

            Object[] newArgs = new Object[args.length - 1];

            int newArgsPos = 0;

            for (int j = 0; j < args.length; j++) {
                if (j != i)
                    newArgs[newArgsPos++] = args[j];
            }

            // Execute if this combination was never executed before.
            if (executedSqls.add(newSql)) {
                List<List<?>> res = executeSingle(newSql, newArgs);

                resConsumer.accept(res);
            }

            // Continue recursively.
            if (newArgs.length > 0)
                executeCombinations0(newSql, resConsumer, executedSqls, newArgs);
        }
    }

    /**
     * Execute SQL query.
     *
     * @param sql SQL.
     * @param args Parameters arguments.
     * @return Query results.
     */
    protected List<List<?>> executeSingle(String sql, Object... args) {
        clearIoState();

        return executeSql(sql, args);
    }

    /**
     * Execute SQL query.
     *
     * @param sql SQL.
     * @param args Parameters arguments.
     * @return Query results.
     */
    protected List<List<?>> executeSql(String sql, Object... args) {
        if (args == null || args.length == 0)
            System.out.println(">>> " + sql);
        else
            System.out.println(">>> " + sql + " " + Arrays.toString(args));

        SqlFieldsQuery qry = new SqlFieldsQuery(sql);

        if (args != null && args.length > 0)
            qry.setArgs(args);

        return executeSqlFieldsQuery(qry);
    }

    /**
     * Execute prepared SQL fields query.
     *
     * @param qry Query.
     * @return Result.
     */
    protected List<List<?>> executeSqlFieldsQuery(SqlFieldsQuery qry) {
        return client().context().query().querySqlFields(qry, false).getAll();
    }

    /**
     * @return Client node.
     */
    protected IgniteEx client() {
        return grid(CLI_NAME);
    }

    /**
     * Clear partitions.
     */
    protected static void clearIoState() {
        INTERCEPTED_REQS.set(0);
        INTERCEPTED_PARTS.clear();
        INTERCEPTED_NODES.clear();
    }

    /**
     * Make sure that expected partitions are logged.
     *
     * @param expParts Expected partitions.
     */
    protected static void assertPartitions(int... expParts) {
        Collection<Integer> expParts0 = new TreeSet<>();

        for (int expPart : expParts)
            expParts0.add(expPart);

        assertPartitions(expParts0);
    }

    /**
     * Make sure that expected partitions are logged.
     *
     * @param expParts Expected partitions.
     */
    protected static void assertPartitions(Collection<Integer> expParts) {
        TreeSet<Integer> expParts0 = new TreeSet<>(expParts);
        TreeSet<Integer> actualParts = new TreeSet<>(INTERCEPTED_PARTS);

        assertEquals("Unexpected partitions [exp=" + expParts + ", actual=" + actualParts + ']',
            expParts0, actualParts);
    }

    /**
     * Make sure that no partitions were extracted.
     */
    protected static void assertNoPartitions() {
        assertTrue("No requests were sent.", INTERCEPTED_REQS.get() > 0);
        assertTrue("Partitions are not empty: " + INTERCEPTED_PARTS, INTERCEPTED_PARTS.isEmpty());
    }

    /**
     * Make sure there were no requests sent because we determined empty partition set.
     */
    protected static void assertNoRequests() {
        assertEquals("Requests were sent: " + INTERCEPTED_REQS.get(), 0, INTERCEPTED_REQS.get());
    }

    /**
     * @param cacheName Cache name.
     * @param key Key.
     * @return Partition.
     */
    protected int partition(String cacheName, Object key) {
        return client().affinity(cacheName).partition(key);
    }

    /**
     * Make sure that expected nodes are logged.
     *
     * @param expNodes Expected nodes.
     */
    protected static void assertNodes(ClusterNode... expNodes) {
        Collection<ClusterNode> expNodes0 = new TreeSet<>();

        for (ClusterNode expNode : expNodes)
            expNodes0.add(expNode);

        assertNodes(expNodes0);
    }

    /**
     * Make sure that expected nodes are logged.
     *
     * @param expNodes Expected nodes.
     */
    protected static void assertNodes(Collection<ClusterNode> expNodes) {
        TreeSet<ClusterNode> expNodes0 = new TreeSet<>(expNodes);
        TreeSet<ClusterNode> actualNodes = new TreeSet<>(INTERCEPTED_NODES);

        assertEquals("Unexpected nodes [exp=" + expNodes + ", actual=" + actualNodes + ']',
            expNodes0, actualNodes);
    }

    /**
     * @param cacheName Cache name.
     * @param key Key.
     * @return Node.
     */
    protected ClusterNode node(String cacheName, Object key) {
        return client().affinity(cacheName).mapKeyToNode(key);
    }

    /**
     * TCP communication SPI which will track outgoing query requests.
     */
    private static class TrackingTcpCommunicationSpi extends TcpCommunicationSpi {
        /** {@inheritDoc} */
        @Override public void sendMessage(ClusterNode node, Message msg, IgniteInClosure<IgniteException> ackC) {
            if (msg instanceof GridIoMessage) {
                GridIoMessage msg0 = (GridIoMessage)msg;

                if (msg0.message() instanceof GridH2QueryRequest) {
                    INTERCEPTED_NODES.add(node);
                    INTERCEPTED_REQS.incrementAndGet();

                    GridH2QueryRequest req = (GridH2QueryRequest)msg0.message();

                    int[] parts = req.queryPartitions();

                    if (!F.isEmpty(parts)) {
                        for (int part : parts)
                            INTERCEPTED_PARTS.add(part);
                    }
                }
                else if (msg0.message() instanceof GridNearTxQueryEnlistRequest) {
                    INTERCEPTED_NODES.add(node);
                    INTERCEPTED_REQS.incrementAndGet();

                    GridNearTxQueryEnlistRequest req = (GridNearTxQueryEnlistRequest)msg0.message();

                    int[] parts = req.partitions();

                    if (!F.isEmpty(parts)) {
                        for (int part : parts)
                            INTERCEPTED_PARTS.add(part);
                    }
                }
            }

            super.sendMessage(node, msg, ackC);
        }
    }

    /**
     * @param table Table name.
     * @param cols Number of columns for this table.
     * @param until Inclusive upper boundary for inserting data.
     */
    protected void fillTable(String table, int cols, int until) {
        String sql = "INSERT INTO " + table + " VALUES ("
            + IntStream.range(0, cols).mapToObj(i -> "?").collect(Collectors.joining(","))
            + ")";

        for (int i = 1; i <= until; i++) {
            int v = i;

            Object[] arr = IntStream.range(0, cols).mapToObj(c -> v).toArray();

            executeSql(sql, arr);
        }
    }

    /**
     * @param name Name.
     * @return PK column.
     */
    public Column pkColumn(String name) {
        return new Column(name, null, true, false);
    }

    /**
     * @param name Name.
     * @param alias Alias.
     * @return PK column.
     */
    public Column pkColumn(String name, String alias) {
        return new Column(name, alias, true, false);
    }

    /**
     * @param name Name.
     * @return Affintiy column.
     */
    public Column affinityColumn(String name) {
        return new Column(name, null, true, true);
    }

    /**
     * @param name Name.
     * @param alias Alias.
     * @return Affintiy column.
     */
    public Column affinityColumn(String name, String alias) {
        return new Column(name, alias, true, true);
    }

    /**
     * Column.
     */
    private static class Column {
        /** Name. */
        private final String name;

        /** Alias. */
        private final String alias;

        /** PK. */
        private final boolean pk;

        /** Affinity key. */
        private final boolean aff;

        /**
         * Constructor.
         *
         * @param name Name.
         * @param pk PK flag.
         * @param aff Affinity flag.
         */
        public Column(String name, String alias, boolean pk, boolean aff) {
            this.name = name;
            this.alias = alias;
            this.pk = pk;
            this.aff = aff;
        }

        /**
         * Constructor.
         *
         * @param name Name.
         */
        public Column(String name) {
            this.name = name;
            alias = null;
            pk = false;
            aff = false;
        }

        /**
         * @return Name.
         */
        public String name() {
            return name;
        }

        /**
         * @return Alias.
         */
        public String alias() {
            return alias;
        }

        /**
         * @return PK flag.
         */
        public boolean pk() {
            return pk;
        }

        /**
         * @return Affintiy flag.
         */
        public boolean affinity() {
            return aff;
        }
    }
}
