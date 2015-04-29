/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.state;

import com.google.common.collect.ImmutableMap;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.gateway.GatewayAllocator;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.disruption.BlockClusterStateProcessing;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;

/**
 */
@ElasticsearchIntegrationTest.ClusterScope(scope = ElasticsearchIntegrationTest.Scope.TEST, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0)
public class RareClusterStateTests extends ElasticsearchIntegrationTest {

    @Override
    protected int numberOfShards() {
        return 1;
    }

    @Override
    protected int numberOfReplicas() {
        return 0;
    }

    @Test
    public void testUnassignedShardAndEmptyNodesInRoutingTable() throws Exception {
        internalCluster().startNode();
        createIndex("a");
        ensureSearchable("a");
        ClusterState current = clusterService().state();
        GatewayAllocator allocator = internalCluster().getInstance(GatewayAllocator.class);

        AllocationDeciders allocationDeciders = new AllocationDeciders(ImmutableSettings.EMPTY, new AllocationDecider[0]);
        RoutingNodes routingNodes = new RoutingNodes(
                ClusterState.builder(current)
                        .routingTable(RoutingTable.builder(current.routingTable()).remove("a").addAsRecovery(current.metaData().index("a")))
                        .nodes(DiscoveryNodes.EMPTY_NODES)
                        .build()
        );
        ClusterInfo clusterInfo = new ClusterInfo(ImmutableMap.<String, DiskUsage>of(), ImmutableMap.<String, Long>of());

        RoutingAllocation routingAllocation = new RoutingAllocation(allocationDeciders, routingNodes, current.nodes(), clusterInfo);
        allocator.allocateUnassigned(routingAllocation);
    }

    @Test
    @TestLogging(value = "cluster.service:TRACE")
    public void testDeleteCreateInOneBulk() throws Exception {
        internalCluster().startNodesAsync(2, ImmutableSettings.builder()
                .put(DiscoveryModule.DISCOVERY_TYPE_KEY, "zen")
                .build()).get();
        assertFalse(client().admin().cluster().prepareHealth().setWaitForNodes("2").get().isTimedOut());
        prepareCreate("test").setSettings(IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS, true).addMapping("type").get();
        ensureGreen("test");

        // now that the cluster is stable, remove publishing timeout
        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(ImmutableSettings.builder().put(DiscoverySettings.PUBLISH_TIMEOUT, "0")));

        Set<String> nodes = new HashSet<>(Arrays.asList(internalCluster().getNodeNames()));
        nodes.remove(internalCluster().getMasterName());

        // block none master node.
        BlockClusterStateProcessing disruption = new BlockClusterStateProcessing(nodes.iterator().next(), getRandom());
        internalCluster().setDisruptionScheme(disruption);
        logger.info("--> indexing a doc");
        index("test", "type", "1");
        refresh();
        disruption.startDisrupting();
        logger.info("--> delete index and recreate it");
        assertFalse(client().admin().indices().prepareDelete("test").setTimeout("200ms").get().isAcknowledged());
        assertFalse(prepareCreate("test").setTimeout("200ms").setSettings(IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS, true).get().isAcknowledged());
        logger.info("--> letting cluster proceed");
        disruption.stopDisrupting();
        ensureGreen(TimeValue.timeValueMinutes(30), "test");
        assertHitCount(client().prepareSearch("test").get(), 0);
    }

    public void testDelayedMappingPropagationOnReplica() throws Exception {
        // Here we want to test that everything goes well if the mappings that
        // are needed for a document are not available on the replica at the
        // time of indexing it
        final List<String> nodeNames = internalCluster().startNodesAsync(2).get();
        assertFalse(client().admin().cluster().prepareHealth().setWaitForNodes("2").get().isTimedOut());

        final String master = internalCluster().getMasterName();
        assertThat(nodeNames, hasItem(master));
        String otherNode = null;
        for (String node : nodeNames) {
            if (node.equals(master) == false) {
                otherNode = node;
                break;
            }
        }
        assertNotNull(otherNode);

        // Force allocation of the primary on the master node by first only allocating on the master
        // and then allowing all nodes so that the replica gets allocated on the other node
        assertAcked(prepareCreate("index").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put("index.routing.allocation.include._name", master)).get());
        assertAcked(client().admin().indices().prepareUpdateSettings("index").setSettings(ImmutableSettings.builder()
                .put("index.routing.allocation.include._name", "")).get());
        ensureGreen();

        // Check routing tables
        ClusterState state = client().admin().cluster().prepareState().get().getState();
        assertEquals(master, state.nodes().masterNode().name());
        List<ShardRouting> shards = state.routingTable().allShards("index");
        assertThat(shards, hasSize(2));
        for (ShardRouting shard : shards) {
            if (shard.primary()) {
                // primary must be on the master
                assertEquals(state.nodes().masterNodeId(), shard.currentNodeId());
            } else {
                assertTrue(shard.active());
            }
        }

        // Block cluster state processing on the replica
        BlockClusterStateProcessing disruption = new BlockClusterStateProcessing(otherNode, getRandom());
        internalCluster().setDisruptionScheme(disruption);
        disruption.startDisrupting();
        final AtomicReference<Object> putMappingResponse = new AtomicReference<>();
        client().admin().indices().preparePutMapping("index").setType("type").setSource("field", "type=long").execute(new ActionListener<PutMappingResponse>() {
            @Override
            public void onResponse(PutMappingResponse response) {
                putMappingResponse.set(response);
            }
            @Override
            public void onFailure(Throwable e) {
                putMappingResponse.set(e);
            }
        });
        // Wait for mappings to be available on master
        assertBusy(new Runnable() {
            @Override
            public void run() {
                final IndicesService indicesService = internalCluster().getInstance(IndicesService.class, master);
                final IndexService indexService = indicesService.indexServiceSafe("index");
                assertNotNull(indexService);
                final MapperService mapperService = indexService.mapperService();
                DocumentMapper mapper = mapperService.documentMapper("type");
                assertNotNull(mapper);
                assertNotNull(mapper.mappers().getMapper("field"));
            }
        });

        final AtomicReference<Object> docIndexResponse = new AtomicReference<>();
        client().prepareIndex("index", "type", "1").setSource("field", 42).execute(new ActionListener<IndexResponse>() {
            @Override
            public void onResponse(IndexResponse response) {
                docIndexResponse.set(response);
            }
            @Override
            public void onFailure(Throwable e) {
                docIndexResponse.set(e);
            }
        });

        // Wait for document to be indexed on primary
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertTrue(client().prepareGet("index", "type", "1").setPreference("_primary").get().isExists());
            }
        });

        // The mappings have not been propagated to the replica yet as a consequence the document count not be indexed
        // We wait on purpose to make sure that the document is not indexed because the shard operation is stalled
        // and not just because it takes time to replicate the indexing request to the replica
        Thread.sleep(100);
        assertThat(putMappingResponse.get(), equalTo(null));
        assertThat(docIndexResponse.get(), equalTo(null));

        // Now make sure the indexing request finishes successfully
        disruption.stopDisrupting();
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat(putMappingResponse.get(), instanceOf(PutMappingResponse.class));
                PutMappingResponse resp = (PutMappingResponse) putMappingResponse.get();
                assertTrue(resp.isAcknowledged());
                assertThat(docIndexResponse.get(), instanceOf(IndexResponse.class));
                IndexResponse docResp = (IndexResponse) docIndexResponse.get();
                assertEquals(Arrays.toString(docResp.getShardInfo().getFailures()),
                        2, docResp.getShardInfo().getTotal()); // both shards should have succeeded
            }
        });
    }

}
