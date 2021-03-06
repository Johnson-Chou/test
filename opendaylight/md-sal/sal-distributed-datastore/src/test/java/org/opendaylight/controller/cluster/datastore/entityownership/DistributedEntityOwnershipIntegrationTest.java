/*
 * Copyright (c) 2015 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.datastore.entityownership;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.opendaylight.controller.cluster.datastore.entityownership.AbstractEntityOwnershipTest.ownershipChange;
import static org.opendaylight.controller.cluster.datastore.entityownership.DistributedEntityOwnershipService.ENTITY_OWNERSHIP_SHARD_NAME;
import static org.opendaylight.controller.cluster.datastore.entityownership.EntityOwnersModel.CANDIDATE_NAME_NODE_ID;
import static org.opendaylight.controller.cluster.datastore.entityownership.EntityOwnersModel.entityPath;
import akka.actor.ActorSystem;
import akka.actor.Address;
import akka.actor.AddressFromURIString;
import akka.actor.Status.Failure;
import akka.actor.Status.Success;
import akka.cluster.Cluster;
import akka.testkit.JavaTestKit;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Uninterruptibles;
import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.opendaylight.controller.cluster.datastore.DatastoreContext;
import org.opendaylight.controller.cluster.datastore.DistributedDataStore;
import org.opendaylight.controller.cluster.datastore.IntegrationTestKit;
import org.opendaylight.controller.cluster.datastore.entityownership.selectionstrategy.EntityOwnerSelectionStrategyConfig;
import org.opendaylight.controller.cluster.datastore.messages.AddShardReplica;
import org.opendaylight.controller.md.cluster.datastore.model.SchemaContextHelper;
import org.opendaylight.controller.md.sal.common.api.clustering.CandidateAlreadyRegisteredException;
import org.opendaylight.controller.md.sal.common.api.clustering.Entity;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipCandidateRegistration;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipChange;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipListener;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipState;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.md.sal.clustering.entity.owners.rev150804.entity.owners.entity.type.entity.Candidate;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

/**
 * End-to-end integration tests for the entity ownership functionality.
 *
 * @author Thomas Pantelis
 */
public class DistributedEntityOwnershipIntegrationTest {
    private static final Address MEMBER_1_ADDRESS = AddressFromURIString.parse("akka.tcp://cluster-test@127.0.0.1:2558");
    private static final String MODULE_SHARDS_CONFIG = "module-shards-default.conf";
    private static final String MODULE_SHARDS_MEMBER_1_CONFIG = "module-shards-default-member-1.conf";
    private static final String ENTITY_TYPE1 = "entityType1";
    private static final String ENTITY_TYPE2 = "entityType2";
    private static final Entity ENTITY1 = new Entity(ENTITY_TYPE1, "entity1");
    private static final Entity ENTITY1_2 = new Entity(ENTITY_TYPE2, "entity1");
    private static final Entity ENTITY2 = new Entity(ENTITY_TYPE1, "entity2");
    private static final Entity ENTITY3 = new Entity(ENTITY_TYPE1, "entity3");
    private static final Entity ENTITY4 = new Entity(ENTITY_TYPE1, "entity4");
    private static final SchemaContext SCHEMA_CONTEXT = SchemaContextHelper.entityOwners();

    private ActorSystem leaderSystem;
    private ActorSystem follower1System;
    private ActorSystem follower2System;

    private final DatastoreContext.Builder leaderDatastoreContextBuilder =
            DatastoreContext.newBuilder().shardHeartbeatIntervalInMillis(100).shardElectionTimeoutFactor(5);

    private final DatastoreContext.Builder followerDatastoreContextBuilder =
            DatastoreContext.newBuilder().shardHeartbeatIntervalInMillis(100).shardElectionTimeoutFactor(10000);

    private DistributedDataStore leaderDistributedDataStore;
    private DistributedDataStore follower1DistributedDataStore;
    private DistributedDataStore follower2DistributedDataStore;
    private DistributedEntityOwnershipService leaderEntityOwnershipService;
    private DistributedEntityOwnershipService follower1EntityOwnershipService;
    private DistributedEntityOwnershipService follower2EntityOwnershipService;
    private IntegrationTestKit leaderTestKit;
    private IntegrationTestKit follower1TestKit;
    private IntegrationTestKit follower2TestKit;

    @Mock
    private EntityOwnershipListener leaderMockListener;

    @Mock
    private EntityOwnershipListener leaderMockListener2;

    @Mock
    private EntityOwnershipListener follower1MockListener;

    @Mock
    private EntityOwnershipListener follower2MockListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @After
    public void tearDown() {
        if(leaderSystem != null) {
            JavaTestKit.shutdownActorSystem(leaderSystem);
        }

        if(follower1System != null) {
            JavaTestKit.shutdownActorSystem(follower1System);
        }

        if(follower2System != null) {
            JavaTestKit.shutdownActorSystem(follower2System);
        }
    }

    private void startAllSystems() {
        startLeaderSystem();
        startFollower1System();
        startFollower2System();
    }

    private void startFollower2System() {
        follower2System = ActorSystem.create("cluster-test", ConfigFactory.load().getConfig("Member3"));
        Cluster.get(follower2System).join(MEMBER_1_ADDRESS);
    }

    private void startFollower1System() {
        follower1System = ActorSystem.create("cluster-test", ConfigFactory.load().getConfig("Member2"));
        Cluster.get(follower1System).join(MEMBER_1_ADDRESS);
    }

    private void startLeaderSystem() {
        leaderSystem = ActorSystem.create("cluster-test", ConfigFactory.load().getConfig("Member1"));
        Cluster.get(leaderSystem).join(MEMBER_1_ADDRESS);
    }

    private void initDatastores(String type) {
        initLeaderDatastore(type, MODULE_SHARDS_CONFIG);

        initFollower1Datastore(type, MODULE_SHARDS_CONFIG);

        follower2TestKit = new IntegrationTestKit(follower2System, followerDatastoreContextBuilder);
        follower2DistributedDataStore = follower2TestKit.setupDistributedDataStore(
                type, MODULE_SHARDS_CONFIG, false, SCHEMA_CONTEXT);

        leaderDistributedDataStore.waitTillReady();
        follower1DistributedDataStore.waitTillReady();
        follower2DistributedDataStore.waitTillReady();

        startLeaderService();

        startFollower1Service();

        follower2EntityOwnershipService = new DistributedEntityOwnershipService(follower2DistributedDataStore,
                EntityOwnerSelectionStrategyConfig.newBuilder().build());
        follower2EntityOwnershipService.start();

        leaderTestKit.waitUntilLeader(leaderDistributedDataStore.getActorContext(), ENTITY_OWNERSHIP_SHARD_NAME);
    }

    private void startFollower1Service() {
        follower1EntityOwnershipService = new DistributedEntityOwnershipService(follower1DistributedDataStore,
                EntityOwnerSelectionStrategyConfig.newBuilder().build());
        follower1EntityOwnershipService.start();
    }

    private void startLeaderService() {
        leaderEntityOwnershipService = new DistributedEntityOwnershipService(leaderDistributedDataStore,
                EntityOwnerSelectionStrategyConfig.newBuilder().build());
        leaderEntityOwnershipService.start();
    }

    private void initFollower1Datastore(String type, String moduleConfig) {
        follower1TestKit = new IntegrationTestKit(follower1System, followerDatastoreContextBuilder);
        follower1DistributedDataStore = follower1TestKit.setupDistributedDataStore(
                type, moduleConfig, false, SCHEMA_CONTEXT);
    }

    private void initLeaderDatastore(String type, String moduleConfig) {
        leaderTestKit = new IntegrationTestKit(leaderSystem, leaderDatastoreContextBuilder);
        leaderDistributedDataStore = leaderTestKit.setupDistributedDataStore(
                type, moduleConfig, false, SCHEMA_CONTEXT);
    }

    @Test
    public void test() throws Exception {
        startAllSystems();
        initDatastores("test");

        leaderEntityOwnershipService.registerListener(ENTITY_TYPE1, leaderMockListener);
        leaderEntityOwnershipService.registerListener(ENTITY_TYPE2, leaderMockListener2);
        follower1EntityOwnershipService.registerListener(ENTITY_TYPE1, follower1MockListener);

        // Register leader candidate for entity1 and verify it becomes owner

        leaderEntityOwnershipService.registerCandidate(ENTITY1);
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY1, false, true, true));
        verify(follower1MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY1, false, false, true));
        reset(leaderMockListener, follower1MockListener);

        verifyGetOwnershipState(leaderEntityOwnershipService, ENTITY1, true, true);
        verifyGetOwnershipState(follower1EntityOwnershipService, ENTITY1, false, true);

        // Register leader candidate for entity1_2 (same id, different type) and verify it becomes owner

        leaderEntityOwnershipService.registerCandidate(ENTITY1_2);
        verify(leaderMockListener2, timeout(5000)).ownershipChanged(ownershipChange(ENTITY1_2, false, true, true));
        verify(leaderMockListener, timeout(300).never()).ownershipChanged(ownershipChange(ENTITY1_2));
        reset(leaderMockListener2);

        // Register follower1 candidate for entity1 and verify it gets added but doesn't become owner

        follower1EntityOwnershipService.registerCandidate(ENTITY1);
        verifyCandidates(leaderDistributedDataStore, ENTITY1, "member-1", "member-2");
        verifyOwner(leaderDistributedDataStore, ENTITY1, "member-1");
        verify(leaderMockListener, timeout(300).never()).ownershipChanged(ownershipChange(ENTITY1));
        verify(follower1MockListener, timeout(300).never()).ownershipChanged(ownershipChange(ENTITY1));

        // Register follower1 candidate for entity2 and verify it becomes owner

        follower1EntityOwnershipService.registerCandidate(ENTITY2);
        verify(follower1MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, false, true, true));
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, false, false, true));
        reset(leaderMockListener, follower1MockListener);

        // Register follower2 candidate for entity2 and verify it gets added but doesn't become owner

        follower2EntityOwnershipService.registerListener(ENTITY_TYPE1, follower2MockListener);
        follower2EntityOwnershipService.registerCandidate(ENTITY2);
        verifyCandidates(leaderDistributedDataStore, ENTITY2, "member-2", "member-3");
        verifyOwner(leaderDistributedDataStore, ENTITY2, "member-2");

        // Unregister follower1 candidate for entity2 and verify follower2 becomes owner

        follower1EntityOwnershipService.unregisterCandidate(ENTITY2);
        verifyCandidates(leaderDistributedDataStore, ENTITY2, "member-3");
        verifyOwner(leaderDistributedDataStore, ENTITY2, "member-3");
        verify(follower1MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, true, false, true));
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, false, false, true));

        // Depending on timing, follower2MockListener could get ownershipChanged with "false, false, true" if
        // if the original ownership change with "member-2 is replicated to follower2 after the listener is
        // registered.
        Uninterruptibles.sleepUninterruptibly(500, TimeUnit.MILLISECONDS);
        verify(follower2MockListener, atMost(1)).ownershipChanged(ownershipChange(ENTITY2, false, false, true));
        verify(follower2MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, false, true, true));

        // Register follower1 candidate for entity3 and verify it becomes owner

        follower1EntityOwnershipService.registerCandidate(ENTITY3);
        verifyOwner(leaderDistributedDataStore, ENTITY3, "member-2");
        verify(follower1MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY3, false, true, true));
        verify(follower2MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY3, false, false, true));
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY3, false, false, true));

        // Register follower2 candidate for entity4 and verify it becomes owner

        follower2EntityOwnershipService.registerCandidate(ENTITY4);
        verifyOwner(leaderDistributedDataStore, ENTITY4, "member-3");
        verify(follower2MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY4, false, true, true));
        verify(follower1MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY4, false, false, true));
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY4, false, false, true));
        reset(follower1MockListener, follower2MockListener);

        // Register follower1 candidate for entity4 and verify it gets added but doesn't become owner

        follower1EntityOwnershipService.registerCandidate(ENTITY4);
        verifyCandidates(leaderDistributedDataStore, ENTITY4, "member-3", "member-2");
        verifyOwner(leaderDistributedDataStore, ENTITY4, "member-3");

        // Shutdown follower2 and verify it's owned entities (entity 2 & 4) get re-assigned

        reset(leaderMockListener, follower1MockListener);
        JavaTestKit.shutdownActorSystem(follower2System);

        verify(follower1MockListener, timeout(15000).times(2)).ownershipChanged(or(ownershipChange(ENTITY4, false, true, true),
                ownershipChange(ENTITY2, false, false, false)));
        verify(leaderMockListener, timeout(15000).times(2)).ownershipChanged(or(ownershipChange(ENTITY4, false, false, true),
                ownershipChange(ENTITY2, false, false, false)));
        verifyOwner(leaderDistributedDataStore, ENTITY2, ""); // no other candidate

        // Register leader candidate for entity2 and verify it becomes owner

        leaderEntityOwnershipService.registerCandidate(ENTITY2);
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, false, true, true));
        verifyOwner(leaderDistributedDataStore, ENTITY2, "member-1");

        // Unregister leader candidate for entity2 and verify the owner is cleared

        leaderEntityOwnershipService.unregisterCandidate(ENTITY2);
        verifyOwner(leaderDistributedDataStore, ENTITY2, "");
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, true, false, false));
        verify(follower1MockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY2, false, false, false));
    }

    /**
     * Reproduces bug <a href="https://bugs.opendaylight.org/show_bug.cgi?id=4554">4554</a>
     *
     * @throws CandidateAlreadyRegisteredException
     */
    @Test
    public void testCloseCandidateRegistrationInQuickSuccession() throws CandidateAlreadyRegisteredException {
        startAllSystems();
        initDatastores("testCloseCandidateRegistrationInQuickSuccession");

        leaderEntityOwnershipService.registerListener(ENTITY_TYPE1, leaderMockListener);
        follower1EntityOwnershipService.registerListener(ENTITY_TYPE1, follower1MockListener);
        follower2EntityOwnershipService.registerListener(ENTITY_TYPE1, follower2MockListener);

        final EntityOwnershipCandidateRegistration candidate1 = leaderEntityOwnershipService.registerCandidate(ENTITY1);
        final EntityOwnershipCandidateRegistration candidate2 = follower1EntityOwnershipService.registerCandidate(ENTITY1);
        final EntityOwnershipCandidateRegistration candidate3 = follower2EntityOwnershipService.registerCandidate(ENTITY1);

        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY1, false, true, true));

        Mockito.reset(leaderMockListener);

        candidate1.close();
        candidate2.close();
        candidate3.close();

        ArgumentCaptor<EntityOwnershipChange> leaderChangeCaptor = ArgumentCaptor.forClass(EntityOwnershipChange.class);
        ArgumentCaptor<EntityOwnershipChange> follower1ChangeCaptor = ArgumentCaptor.forClass(EntityOwnershipChange.class);
        ArgumentCaptor<EntityOwnershipChange> follower2ChangeCaptor = ArgumentCaptor.forClass(EntityOwnershipChange.class);
        doNothing().when(leaderMockListener).ownershipChanged(leaderChangeCaptor.capture());
        doNothing().when(follower1MockListener).ownershipChanged(follower1ChangeCaptor.capture());
        doNothing().when(follower2MockListener).ownershipChanged(follower2ChangeCaptor.capture());

        boolean passed = false;
        for(int i=0;i<100;i++) {
            Uninterruptibles.sleepUninterruptibly(50, TimeUnit.MILLISECONDS);
            if(!leaderEntityOwnershipService.getOwnershipState(ENTITY1).get().hasOwner() &&
                    !follower1EntityOwnershipService.getOwnershipState(ENTITY1).get().hasOwner() &&
                    !follower2EntityOwnershipService.getOwnershipState(ENTITY1).get().hasOwner() &&
                    leaderChangeCaptor.getAllValues().size() > 0 && !leaderChangeCaptor.getValue().hasOwner() &&
                    leaderChangeCaptor.getAllValues().size() > 0 && !follower1ChangeCaptor.getValue().hasOwner() &&
                    leaderChangeCaptor.getAllValues().size() > 0 && !follower2ChangeCaptor.getValue().hasOwner()) {
                passed = true;
                break;
            }
        }

        assertTrue("No ownership change message was sent with hasOwner=false", passed);
    }

    /**
     * Tests bootstrapping the entity-ownership shard when there's no shards initially configured for local
     * member. The entity-ownership shard is initially created as inactive (ie remains a follower), requiring
     * an AddShardReplica request to join it to an existing leader.
     */
    @Test
    public void testEntityOwnershipShardBootstrapping() throws Throwable {
        startLeaderSystem();
        startFollower1System();
        String type = "testEntityOwnershipShardBootstrapping";
        initLeaderDatastore(type, MODULE_SHARDS_MEMBER_1_CONFIG);
        initFollower1Datastore(type, MODULE_SHARDS_MEMBER_1_CONFIG);

        leaderDistributedDataStore.waitTillReady();
        follower1DistributedDataStore.waitTillReady();

        startLeaderService();
        startFollower1Service();

        leaderTestKit.waitUntilLeader(leaderDistributedDataStore.getActorContext(), ENTITY_OWNERSHIP_SHARD_NAME);

        leaderEntityOwnershipService.registerListener(ENTITY_TYPE1, leaderMockListener);

        // Register a candidate for follower1 - should get queued since follower1 has no leader
        follower1EntityOwnershipService.registerCandidate(ENTITY1);
        verify(leaderMockListener, timeout(300).never()).ownershipChanged(ownershipChange(ENTITY1));

        // Add replica in follower1
        AddShardReplica addReplica = new AddShardReplica(ENTITY_OWNERSHIP_SHARD_NAME);
        follower1DistributedDataStore.getActorContext().getShardManager().tell(addReplica , follower1TestKit.getRef());
        Object reply = follower1TestKit.expectMsgAnyClassOf(JavaTestKit.duration("5 sec"), Success.class, Failure.class);
        if(reply instanceof Failure) {
            throw ((Failure)reply).cause();
        }

        // The queued candidate registration should proceed
        verify(leaderMockListener, timeout(5000)).ownershipChanged(ownershipChange(ENTITY1));

    }

    private static void verifyGetOwnershipState(DistributedEntityOwnershipService service, Entity entity,
            boolean isOwner, boolean hasOwner) {
        Optional<EntityOwnershipState> state = service.getOwnershipState(entity);
        assertEquals("getOwnershipState present", true, state.isPresent());
        assertEquals("isOwner", isOwner, state.get().isOwner());
        assertEquals("hasOwner", hasOwner, state.get().hasOwner());
    }

    private static void verifyCandidates(DistributedDataStore dataStore, Entity entity, String... expCandidates) throws Exception {
        AssertionError lastError = null;
        Stopwatch sw = Stopwatch.createStarted();
        while(sw.elapsed(TimeUnit.MILLISECONDS) <= 5000) {
            Optional<NormalizedNode<?, ?>> possible = dataStore.newReadOnlyTransaction().read(
                    entityPath(entity.getType(), entity.getId()).node(Candidate.QNAME)).get(5, TimeUnit.SECONDS);
            try {
                assertEquals("Candidates not found for " + entity, true, possible.isPresent());
                Collection<String> actual = new ArrayList<>();
                for(MapEntryNode candidate: ((MapNode)possible.get()).getValue()) {
                    actual.add(candidate.getChild(CANDIDATE_NAME_NODE_ID).get().getValue().toString());
                }

                assertEquals("Candidates for " + entity, Arrays.asList(expCandidates), actual);
                return;
            } catch (AssertionError e) {
                lastError = e;
                Uninterruptibles.sleepUninterruptibly(300, TimeUnit.MILLISECONDS);
            }
        }

        throw lastError;
    }

    private static void verifyOwner(final DistributedDataStore dataStore, Entity entity, String expOwner) {
        AbstractEntityOwnershipTest.verifyOwner(expOwner, entity.getType(), entity.getId(),
                new Function<YangInstanceIdentifier, NormalizedNode<?,?>>() {
                    @Override
                    public NormalizedNode<?, ?> apply(YangInstanceIdentifier path) {
                        try {
                            return dataStore.newReadOnlyTransaction().read(path).get(5, TimeUnit.SECONDS).get();
                        } catch (Exception e) {
                            return null;
                        }
                    }
                });
    }
}
