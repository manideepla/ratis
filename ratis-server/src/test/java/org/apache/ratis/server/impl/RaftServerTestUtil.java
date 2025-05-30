/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.server.impl;

import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.DataStreamMap;
import org.apache.ratis.server.DataStreamServer;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.RaftConfiguration;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.leader.LogAppender;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.segmented.SegmentedRaftLog;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.MemoizedSupplier;
import org.apache.ratis.util.Slf4jUtils;
import org.apache.ratis.util.TimeDuration;
import org.apache.ratis.util.function.CheckedConsumer;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RaftServerTestUtil {
  static final Logger LOG = LoggerFactory.getLogger(RaftServerTestUtil.class);

  private RaftServerTestUtil() {
  }

  public static final RaftGroupMemberId TEST_MEMBER_ID = RaftGroupMemberId.valueOf(
      RaftPeerId.valueOf("test"), RaftGroupId.emptyGroupId());

  public static DelayLocalExecutionInjection getLogSyncDelay() {
    return new DelayLocalExecutionInjection(RaftServerImpl.LOG_SYNC);
  }

  public static void setStateMachineUpdaterLogLevel(Level level) {
    Slf4jUtils.setLogLevel(StateMachineUpdater.LOG, level);
  }
  public static void setWatchRequestsLogLevel(Level level) {
    Slf4jUtils.setLogLevel(WatchRequests.LOG, level);
  }
  public static void setPendingRequestsLogLevel(Level level) {
    Slf4jUtils.setLogLevel(PendingRequests.LOG, level);
  }

  public static void waitAndCheckNewConf(MiniRaftCluster cluster,
      RaftPeer[] peers, int numOfNewPeers, int numOfRemovedPeers, Collection<RaftPeerId> deadPeers)
      throws Exception {
    final TimeDuration sleepTime = cluster.getTimeoutMax().apply(n -> n * (numOfRemovedPeers + numOfNewPeers + 2));
    JavaUtils.attempt(() -> waitAndCheckNewConf(cluster, Arrays.asList(peers), deadPeers),
        10, sleepTime, "waitAndCheckNewConf", LOG);
  }

  public static void waitAndCheckNewConf(MiniRaftCluster cluster,
      RaftPeer[] peers, int numOfRemovedPeers, Collection<RaftPeerId> deadPeers)
      throws Exception {
    final TimeDuration sleepTime = cluster.getTimeoutMax().apply(n -> n * (numOfRemovedPeers + 2));
    JavaUtils.attempt(() -> waitAndCheckNewConf(cluster, Arrays.asList(peers), deadPeers),
        10, sleepTime, "waitAndCheckNewConf", LOG);
  }
  private static void waitAndCheckNewConf(MiniRaftCluster cluster,
      Collection<RaftPeer> peers, Collection<RaftPeerId> deadPeers) {
    LOG.info("waitAndCheckNewConf: peers={}, deadPeers={}, {}", peers, deadPeers, cluster.printServers());
    Assertions.assertNotNull(cluster.getLeader());

    int numIncluded = 0;
    int deadIncluded = 0;
    final RaftConfigurationImpl current = RaftConfigurationImpl.newBuilder()
        .setConf(peers).setLogEntryIndex(0).build();
    for (RaftServer.Division d : cluster.iterateDivisions()) {
      final RaftServerImpl server = (RaftServerImpl)d;
      LOG.info("checking {}", server);
      if (deadPeers != null && deadPeers.contains(server.getId())) {
        if (current.containsInConf(server.getId())) {
          deadIncluded++;
        }
        continue;
      }
      final RaftConfigurationImpl conf = server.getState().getRaftConf();
      if (current.containsInConf(server.getId())) {
        numIncluded++;
        Assertions.assertTrue(conf.isStable());
        Assertions.assertTrue(conf.hasNoChange(peers, Collections.emptyList()));
      } else if (server.getInfo().isAlive()) {
        // The server is successfully removed from the conf
        // It may not be shutdown since it may not be able to talk to the new leader (who is not in its conf).
        Assertions.assertTrue(conf.isStable());
        Assertions.assertFalse(conf.containsInConf(server.getId()));
      }
    }
    Assertions.assertEquals(peers.size(), numIncluded + deadIncluded);
  }

  public static long getNextIndex(RaftServer.Division server) {
    return ((RaftServerImpl)server).getState().getNextIndex();
  }

  public static long getLatestInstalledSnapshotIndex(RaftServer.Division server) {
    return ((RaftServerImpl)server).getState().getLatestInstalledSnapshotIndex();
  }

  static ServerState getState(RaftServer.Division server) {
    return ((RaftServerImpl)server).getState();
  }

  public static ConfigurationManager getConfigurationManager(RaftServer.Division server) {
    return (ConfigurationManager) RaftTestUtil.getDeclaredField(getState(server), "configurationManager");
  }

  public static Logger getTransactionContextLog() {
    return TransactionManager.LOG;
  }

  public static Map<TermIndex, MemoizedSupplier<TransactionContext>> getTransactionContextMap(
      RaftServer.Division server) {
    return ((RaftServerImpl)server).getTransactionContextMapForTesting();
  }

  public static RaftConfiguration newRaftConfiguration(Collection<RaftPeer> peers) {
    return RaftConfigurationImpl.newBuilder().setConf(peers).build();
  }

  public static void setRaftConf(RaftServer proxy, RaftGroupId groupId, RaftConfiguration conf) {
    ((RaftServerImpl)getDivision(proxy, groupId)).getState().setRaftConf(conf);
  }

  public static RaftServerRpc getServerRpc(RaftServer.Division server) {
    return ((RaftServerImpl)server).getRaftServer().getServerRpc();
  }

  private static Optional<LeaderStateImpl> getLeaderState(RaftServer.Division server) {
    return ((RaftServerImpl)server).getRole().getLeaderState();
  }

  public static Stream<LogAppender> getLogAppenders(RaftServer.Division server) {
    return getLeaderState(server).map(LeaderStateImpl::getLogAppenders).orElse(null);
  }

  public static void assertLeaderLease(RaftServer.Division leader, boolean hasLease) {
    final LeaderStateImpl l = getLeaderState(leader).orElse(null);
    Assertions.assertNotNull(l);
    Assertions.assertEquals(l.hasLease(), hasLease);
  }

  public static void restartLogAppenders(RaftServer.Division server) {
    final LeaderStateImpl leaderState = getLeaderState(server).orElseThrow(
        () -> new IllegalStateException(server + " is not the leader"));
    leaderState.getLogAppenders().forEach(leaderState::restart);
  }

  public static RaftServer.Division getDivision(RaftServer server, RaftGroupId groupId) {
    return JavaUtils.callAsUnchecked(() -> server.getDivision(groupId));
  }

  public static DataStreamServer newDataStreamServer(RaftServer server) {
    return new DataStreamServerImpl(server, null);
  }

  public static DataStreamMap newDataStreamMap(Object name) {
    return new DataStreamMapImpl(name);
  }

  public static void assertLostMajorityHeartbeatsRecently(RaftServer.Division leader) {
    final FollowerState f = ((RaftServerImpl)leader).getRole().getFollowerState().orElse(null);
    Assertions.assertNotNull(f);
    Assertions.assertTrue(f.lostMajorityHeartbeatsRecently());
  }

  public static SegmentedRaftLog newSegmentedRaftLog(RaftGroupMemberId memberId, DivisionInfo info,
      RaftStorage storage, RaftProperties properties) {
    final RaftServerImpl server = Mockito.mock(RaftServerImpl.class);
    Mockito.when(server.getInfo()).thenReturn(info);

    return SegmentedRaftLog.newBuilder()
        .setMemberId(memberId)
        .setServer(server)
        .setNotifyTruncatedLogEntry(server::notifyTruncatedLogEntry)
        .setGetTransactionContext(server::getTransactionContext)
        .setSubmitUpdateCommitEvent(server::submitUpdateCommitEvent)
        .setStorage(storage)
        .setProperties(properties)
        .build();
  }

  public static boolean isHighestPriority(RaftConfiguration config, RaftPeerId peerId) {
    return ((RaftConfigurationImpl)config).isHighestPriority(peerId);
  }

  public static void runWithMinorityPeers(MiniRaftCluster cluster, Collection<RaftPeer> peersInNewConf,
      CheckedConsumer<Collection<RaftPeer>, IOException> consumer) throws IOException {
    Collection<RaftPeer> peers = parseMinorityPeers(cluster, peersInNewConf);
    while (peers != null) {
      consumer.accept(peers);
      peers = parseMinorityPeers(cluster, peersInNewConf);
    }
  }

  private static Collection<RaftPeer> parseMinorityPeers(MiniRaftCluster cluster, Collection<RaftPeer> peersInNewConf) {
    RaftConfigurationImpl conf = (RaftConfigurationImpl) cluster.getLeader().getRaftConf();
    Set<RaftPeer> peers = new HashSet<>(conf.getCurrentPeers());

    // Add new peers to construct minority conf peers.
    List<RaftPeer> peersToAdd = peersInNewConf.stream().filter(
        peer -> !conf.containsInConf(peer.getId(), RaftProtos.RaftPeerRole.FOLLOWER)).collect(Collectors.toList());
    if (!peersToAdd.isEmpty()) {
      for (RaftPeer peer : peersToAdd) {
        if (peers.add(peer) && conf.changeMajority(peers)) {
          peers.remove(peer);
          break;
        }
      }
      return peers;
    }

    // All new peers has been added. Handle the removed peers.
    List<RaftPeer> peersToRemove = peers.stream().filter(peer -> !peersInNewConf.contains(peer))
        .collect(Collectors.toList());
    if (!peersToRemove.isEmpty()) {
      return peersInNewConf;
    }

    // The peers in new conf are the same as current conf, return null.
    return null;
  }
}
