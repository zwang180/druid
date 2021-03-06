/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.server.coordinator.rules;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MinMaxPriorityQueue;
import com.google.common.collect.Ordering;
import io.druid.client.DruidServer;
import io.druid.server.coordination.ServerType;
import io.druid.server.coordinator.CoordinatorStats;
import io.druid.server.coordinator.DruidCluster;
import io.druid.server.coordinator.DruidCoordinatorRuntimeParams;
import io.druid.server.coordinator.LoadQueuePeonTester;
import io.druid.server.coordinator.SegmentReplicantLookup;
import io.druid.server.coordinator.ServerHolder;
import io.druid.timeline.DataSegment;
import io.druid.timeline.partition.NoneShardSpec;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BroadcastDistributionRuleTest
{
  private DruidCluster druidCluster;
  private ServerHolder holderOfSmallSegment;
  private List<ServerHolder> holdersOfLargeSegments = Lists.newArrayList();
  private List<ServerHolder> holdersOfLargeSegments2 = Lists.newArrayList();
  private final List<DataSegment> largeSegments = Lists.newArrayList();
  private final List<DataSegment> largeSegments2 = Lists.newArrayList();
  private DataSegment smallSegment;

  @Before
  public void setUp() throws Exception
  {
    smallSegment = new DataSegment(
        "small_source",
        new Interval("0/1000"),
        new DateTime().toString(),
        Maps.newHashMap(),
        Lists.newArrayList(),
        Lists.newArrayList(),
        NoneShardSpec.instance(),
        0,
        0
    );

    for (int i = 0; i < 3; i++) {
      largeSegments.add(
          new DataSegment(
              "large_source",
              new Interval((i * 1000) + "/" + ((i + 1) * 1000)),
              new DateTime().toString(),
              Maps.newHashMap(),
              Lists.newArrayList(),
              Lists.newArrayList(),
              NoneShardSpec.instance(),
              0,
              100
          )
      );
    }

    for (int i = 0; i < 2; i++) {
      largeSegments2.add(
          new DataSegment(
              "large_source2",
              new Interval((i * 1000) + "/" + ((i + 1) * 1000)),
              new DateTime().toString(),
              Maps.newHashMap(),
              Lists.newArrayList(),
              Lists.newArrayList(),
              NoneShardSpec.instance(),
              0,
              100
          )
      );
    }

    holderOfSmallSegment = new ServerHolder(
        new DruidServer(
            "serverHot2",
            "hostHot2",
            1000,
            ServerType.HISTORICAL,
            "hot",
            0
        ).addDataSegment(smallSegment.getIdentifier(), smallSegment)
         .toImmutableDruidServer(),
        new LoadQueuePeonTester()
    );

    holdersOfLargeSegments.add(
        new ServerHolder(
            new DruidServer(
                "serverHot1",
                "hostHot1",
                1000,
                ServerType.HISTORICAL,
                "hot",
                0
            ).addDataSegment(largeSegments.get(0).getIdentifier(), largeSegments.get(0))
             .toImmutableDruidServer(),
            new LoadQueuePeonTester()
        )
    );
    holdersOfLargeSegments.add(
        new ServerHolder(
            new DruidServer(
                "serverNorm1",
                "hostNorm1",
                1000,
                ServerType.HISTORICAL,
                DruidServer.DEFAULT_TIER,
                0
            ).addDataSegment(largeSegments.get(1).getIdentifier(), largeSegments.get(1))
             .toImmutableDruidServer(),
            new LoadQueuePeonTester()
        )
    );
    holdersOfLargeSegments.add(
        new ServerHolder(
            new DruidServer(
                "serverNorm2",
                "hostNorm2",
                100,
                ServerType.HISTORICAL,
                DruidServer.DEFAULT_TIER,
                0
            ).addDataSegment(largeSegments.get(2).getIdentifier(), largeSegments.get(2))
             .toImmutableDruidServer(),
            new LoadQueuePeonTester()
        )
    );

    holdersOfLargeSegments2.add(
        new ServerHolder(
            new DruidServer(
                "serverHot3",
                "hostHot3",
                1000,
                ServerType.HISTORICAL,
                "hot",
                0
            ).addDataSegment(largeSegments2.get(0).getIdentifier(), largeSegments2.get(0))
             .toImmutableDruidServer(),
            new LoadQueuePeonTester()
        )
    );
    holdersOfLargeSegments2.add(
        new ServerHolder(
            new DruidServer(
                "serverNorm3",
                "hostNorm3",
                100,
                ServerType.HISTORICAL,
                DruidServer.DEFAULT_TIER,
                0
            ).addDataSegment(largeSegments2.get(1).getIdentifier(), largeSegments2.get(1))
             .toImmutableDruidServer(),
            new LoadQueuePeonTester()
        )
    );

    druidCluster = new DruidCluster(
        null,
        ImmutableMap.of(
            "hot",
            MinMaxPriorityQueue.orderedBy(Ordering.natural().reverse()).create(
                Lists.newArrayList(
                    holdersOfLargeSegments.get(0),
                    holderOfSmallSegment,
                    holdersOfLargeSegments2.get(0)
                )
            ),
            DruidServer.DEFAULT_TIER,
            MinMaxPriorityQueue.orderedBy(Ordering.natural().reverse()).create(
                Lists.newArrayList(
                    holdersOfLargeSegments.get(1),
                    holdersOfLargeSegments.get(2),
                    holdersOfLargeSegments2.get(1)
                )
            )
        )
    );
  }

  @Test
  public void testBroadcastToSingleDataSource()
  {
    final ForeverBroadcastDistributionRule rule = new ForeverBroadcastDistributionRule(ImmutableList.of("large_source"));

    CoordinatorStats stats = rule.run(
        null,
        DruidCoordinatorRuntimeParams.newBuilder()
                                     .withDruidCluster(druidCluster)
                                     .withSegmentReplicantLookup(SegmentReplicantLookup.make(druidCluster))
                                     .withBalancerReferenceTimestamp(new DateTime("2013-01-01"))
                                     .withAvailableSegments(Lists.newArrayList(
                                         smallSegment,
                                         largeSegments.get(0),
                                         largeSegments.get(1),
                                         largeSegments.get(2),
                                         largeSegments2.get(0),
                                         largeSegments2.get(1)
                                     )).build(),
        smallSegment
    );

    assertEquals(3, stats.getGlobalStats().get(LoadRule.ASSIGNED_COUNT).intValue());
    assertTrue(stats.getPerTierStats().isEmpty());

    assertTrue(
        holdersOfLargeSegments.stream()
                              .allMatch(holder -> holder.getPeon().getSegmentsToLoad().contains(smallSegment))
    );

    assertTrue(
        holdersOfLargeSegments2.stream()
                               .noneMatch(holder -> holder.getPeon().getSegmentsToLoad().contains(smallSegment))
    );

    assertFalse(holderOfSmallSegment.getPeon().getSegmentsToLoad().contains(smallSegment));
  }

  @Test
  public void testBroadcastToMultipleDataSources()
  {
    final ForeverBroadcastDistributionRule rule = new ForeverBroadcastDistributionRule(
        ImmutableList.of("large_source", "large_source2")
    );

    CoordinatorStats stats = rule.run(
        null,
        DruidCoordinatorRuntimeParams.newBuilder()
                                     .withDruidCluster(druidCluster)
                                     .withSegmentReplicantLookup(SegmentReplicantLookup.make(druidCluster))
                                     .withBalancerReferenceTimestamp(new DateTime("2013-01-01"))
                                     .withAvailableSegments(Lists.newArrayList(
                                         smallSegment,
                                         largeSegments.get(0),
                                         largeSegments.get(1),
                                         largeSegments.get(2),
                                         largeSegments2.get(0),
                                         largeSegments2.get(1)
                                     )).build(),
        smallSegment
    );

    assertEquals(5, stats.getGlobalStats().get(LoadRule.ASSIGNED_COUNT).intValue());
    assertTrue(stats.getPerTierStats().isEmpty());

    assertTrue(
        holdersOfLargeSegments.stream()
                              .allMatch(holder -> holder.getPeon().getSegmentsToLoad().contains(smallSegment))
    );

    assertTrue(
        holdersOfLargeSegments2.stream()
                               .allMatch(holder -> holder.getPeon().getSegmentsToLoad().contains(smallSegment))
    );

    assertFalse(holderOfSmallSegment.getPeon().getSegmentsToLoad().contains(smallSegment));
  }

  @Test
  public void testBroadcastToAllServers()
  {
    final ForeverBroadcastDistributionRule rule = new ForeverBroadcastDistributionRule(null);

    CoordinatorStats stats = rule.run(
        null,
        DruidCoordinatorRuntimeParams.newBuilder()
                                     .withDruidCluster(druidCluster)
                                     .withSegmentReplicantLookup(SegmentReplicantLookup.make(druidCluster))
                                     .withBalancerReferenceTimestamp(new DateTime("2013-01-01"))
                                     .withAvailableSegments(Lists.newArrayList(
                                         smallSegment,
                                         largeSegments.get(0),
                                         largeSegments.get(1),
                                         largeSegments.get(2),
                                         largeSegments2.get(0),
                                         largeSegments2.get(1)
                                     )).build(),
        smallSegment
    );

    assertEquals(6, stats.getGlobalStats().get(LoadRule.ASSIGNED_COUNT).intValue());
    assertTrue(stats.getPerTierStats().isEmpty());

    assertTrue(
        druidCluster.getAllServers().stream()
                    .allMatch(holder -> holder.getPeon().getSegmentsToLoad().contains(smallSegment))
    );
  }
}
