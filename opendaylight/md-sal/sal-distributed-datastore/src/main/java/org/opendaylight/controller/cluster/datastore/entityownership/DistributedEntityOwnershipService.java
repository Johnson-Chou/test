/*
 * Copyright (c) 2015 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.datastore.entityownership;

import static org.opendaylight.controller.cluster.datastore.entityownership.EntityOwnersModel.ENTITY_OWNER_NODE_ID;
import static org.opendaylight.controller.cluster.datastore.entityownership.EntityOwnersModel.entityPath;
import akka.actor.ActorRef;
import akka.dispatch.OnComplete;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.opendaylight.controller.cluster.datastore.DistributedDataStore;
import org.opendaylight.controller.cluster.datastore.config.Configuration;
import org.opendaylight.controller.cluster.datastore.config.ModuleShardConfiguration;
import org.opendaylight.controller.cluster.datastore.entityownership.messages.RegisterCandidateLocal;
import org.opendaylight.controller.cluster.datastore.entityownership.messages.RegisterListenerLocal;
import org.opendaylight.controller.cluster.datastore.entityownership.messages.UnregisterCandidateLocal;
import org.opendaylight.controller.cluster.datastore.entityownership.messages.UnregisterListenerLocal;
import org.opendaylight.controller.cluster.datastore.entityownership.selectionstrategy.EntityOwnerSelectionStrategyConfig;
import org.opendaylight.controller.cluster.datastore.messages.CreateShard;
import org.opendaylight.controller.cluster.datastore.messages.GetShardDataTree;
import org.opendaylight.controller.cluster.datastore.shardstrategy.ModuleShardStrategy;
import org.opendaylight.controller.md.sal.common.api.clustering.CandidateAlreadyRegisteredException;
import org.opendaylight.controller.md.sal.common.api.clustering.Entity;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipCandidateRegistration;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipListener;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipListenerRegistration;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipService;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipState;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.md.sal.clustering.entity.owners.rev150804.EntityOwners;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

/**
 * The distributed implementation of the EntityOwnershipService.
 *
 * @author Thomas Pantelis
 */
public class DistributedEntityOwnershipService implements EntityOwnershipService, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DistributedEntityOwnershipService.class);
    static final String ENTITY_OWNERSHIP_SHARD_NAME = "entity-ownership";
    private static final Timeout MESSAGE_TIMEOUT = new Timeout(1, TimeUnit.MINUTES);

    private final DistributedDataStore datastore;
    private final EntityOwnerSelectionStrategyConfig strategyConfig;
    private final ConcurrentMap<Entity, Entity> registeredEntities = new ConcurrentHashMap<>();
    private volatile ActorRef localEntityOwnershipShard;
    private volatile DataTree localEntityOwnershipShardDataTree;

    public DistributedEntityOwnershipService(DistributedDataStore datastore, EntityOwnerSelectionStrategyConfig strategyConfig) {
        this.datastore = Preconditions.checkNotNull(datastore);
        this.strategyConfig = Preconditions.checkNotNull(strategyConfig);
    }

    public void start() {
        ActorRef shardManagerActor = datastore.getActorContext().getShardManager();

        Configuration configuration = datastore.getActorContext().getConfiguration();
        Collection<String> entityOwnersMemberNames = configuration.getUniqueMemberNamesForAllShards();
        CreateShard createShard = new CreateShard(new ModuleShardConfiguration(EntityOwners.QNAME.getNamespace(),
                "entity-owners", ENTITY_OWNERSHIP_SHARD_NAME, ModuleShardStrategy.NAME, entityOwnersMemberNames),
                        newShardBuilder(), null);

        Future<Object> createFuture = datastore.getActorContext().executeOperationAsync(shardManagerActor,
                createShard, MESSAGE_TIMEOUT);

        createFuture.onComplete(new OnComplete<Object>() {
            @Override
            public void onComplete(Throwable failure, Object response) {
                if(failure != null) {
                    LOG.error("Failed to create {} shard", ENTITY_OWNERSHIP_SHARD_NAME);
                } else {
                    LOG.info("Successfully created {} shard", ENTITY_OWNERSHIP_SHARD_NAME);
                }
            }
        }, datastore.getActorContext().getClientDispatcher());
    }

    private void executeEntityOwnershipShardOperation(final ActorRef shardActor, final Object message) {
        Future<Object> future = datastore.getActorContext().executeOperationAsync(shardActor, message, MESSAGE_TIMEOUT);
        future.onComplete(new OnComplete<Object>() {
            @Override
            public void onComplete(Throwable failure, Object response) {
                if(failure != null) {
                    LOG.debug("Error sending message {} to {}", message, shardActor, failure);
                } else {
                    LOG.debug("{} message to {} succeeded", message, shardActor, failure);
                }
            }
        }, datastore.getActorContext().getClientDispatcher());
    }

    private void executeLocalEntityOwnershipShardOperation(final Object message) {
        if(localEntityOwnershipShard == null) {
            Future<ActorRef> future = datastore.getActorContext().findLocalShardAsync(ENTITY_OWNERSHIP_SHARD_NAME);
            future.onComplete(new OnComplete<ActorRef>() {
                @Override
                public void onComplete(Throwable failure, ActorRef shardActor) {
                    if(failure != null) {
                        LOG.error("Failed to find local {} shard", ENTITY_OWNERSHIP_SHARD_NAME, failure);
                    } else {
                        localEntityOwnershipShard = shardActor;
                        executeEntityOwnershipShardOperation(localEntityOwnershipShard, message);
                    }
                }
            }, datastore.getActorContext().getClientDispatcher());

        } else {
            executeEntityOwnershipShardOperation(localEntityOwnershipShard, message);
        }
    }

    @Override
    public EntityOwnershipCandidateRegistration registerCandidate(Entity entity)
            throws CandidateAlreadyRegisteredException {
        Preconditions.checkNotNull(entity, "entity cannot be null");

        if(registeredEntities.putIfAbsent(entity, entity) != null) {
            throw new CandidateAlreadyRegisteredException(entity);
        }

        RegisterCandidateLocal registerCandidate = new RegisterCandidateLocal(entity);

        LOG.debug("Registering candidate with message: {}", registerCandidate);

        executeLocalEntityOwnershipShardOperation(registerCandidate);
        return new DistributedEntityOwnershipCandidateRegistration(entity, this);
    }

    void unregisterCandidate(Entity entity) {
        LOG.debug("Unregistering candidate for {}", entity);

        executeLocalEntityOwnershipShardOperation(new UnregisterCandidateLocal(entity));
        registeredEntities.remove(entity);
    }

    @Override
    public EntityOwnershipListenerRegistration registerListener(String entityType, EntityOwnershipListener listener) {
        Preconditions.checkNotNull(entityType, "entityType cannot be null");
        Preconditions.checkNotNull(listener, "listener cannot be null");

        RegisterListenerLocal registerListener = new RegisterListenerLocal(listener, entityType);

        LOG.debug("Registering listener with message: {}", registerListener);

        executeLocalEntityOwnershipShardOperation(registerListener);
        return new DistributedEntityOwnershipListenerRegistration(listener, entityType, this);
    }

    @Override
    public Optional<EntityOwnershipState> getOwnershipState(Entity forEntity) {
        Preconditions.checkNotNull(forEntity, "forEntity cannot be null");

        DataTree dataTree = getLocalEntityOwnershipShardDataTree();
        if(dataTree == null) {
            return Optional.absent();
        }

        Optional<NormalizedNode<?, ?>> entityNode = dataTree.takeSnapshot().readNode(
                entityPath(forEntity.getType(), forEntity.getId()));
        if(!entityNode.isPresent()) {
            return Optional.absent();
        }

        String localMemberName = datastore.getActorContext().getCurrentMemberName();
        Optional<DataContainerChild<? extends PathArgument, ?>> ownerLeaf = ((MapEntryNode)entityNode.get()).
                getChild(ENTITY_OWNER_NODE_ID);
        String owner = ownerLeaf.isPresent() ? ownerLeaf.get().getValue().toString() : null;
        boolean hasOwner = !Strings.isNullOrEmpty(owner);
        boolean isOwner = hasOwner && localMemberName.equals(owner);

        return Optional.of(new EntityOwnershipState(isOwner, hasOwner));
    }

    @Override
    public boolean isCandidateRegistered(@Nonnull Entity entity) {
        return registeredEntities.get(entity) != null;
    }

    private DataTree getLocalEntityOwnershipShardDataTree() {
        if(localEntityOwnershipShardDataTree == null) {
            try {
                if(localEntityOwnershipShard == null) {
                    localEntityOwnershipShard = Await.result(datastore.getActorContext().findLocalShardAsync(
                            ENTITY_OWNERSHIP_SHARD_NAME), Duration.Inf());
                }

                localEntityOwnershipShardDataTree = (DataTree) Await.result(Patterns.ask(localEntityOwnershipShard,
                        GetShardDataTree.INSTANCE, MESSAGE_TIMEOUT), Duration.Inf());
            } catch (Exception e) {
                LOG.error("Failed to find local {} shard", ENTITY_OWNERSHIP_SHARD_NAME, e);
            }
        }

        return localEntityOwnershipShardDataTree;
    }

    void unregisterListener(String entityType, EntityOwnershipListener listener) {
        LOG.debug("Unregistering listener {} for entity type {}", listener, entityType);

        executeLocalEntityOwnershipShardOperation(new UnregisterListenerLocal(listener, entityType));
    }

    @Override
    public void close() {
    }

    protected EntityOwnershipShard.Builder newShardBuilder() {
        return EntityOwnershipShard.newBuilder().localMemberName(datastore.getActorContext().getCurrentMemberName())
                .ownerSelectionStrategyConfig(this.strategyConfig);
    }

    @VisibleForTesting
    ActorRef getLocalEntityOwnershipShard() {
        return localEntityOwnershipShard;
    }
}
