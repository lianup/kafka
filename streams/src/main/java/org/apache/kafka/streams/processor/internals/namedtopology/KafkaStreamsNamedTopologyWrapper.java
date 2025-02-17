/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals.namedtopology;

import org.apache.kafka.clients.admin.DeleteConsumerGroupOffsetsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.annotation.InterfaceStability.Unstable;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.errors.GroupSubscribedToTopicException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.LagInfo;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.TaskMetadata;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.TopologyException;
import org.apache.kafka.streams.errors.UnknownStateStoreException;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier;
import org.apache.kafka.streams.processor.internals.InternalTopologyBuilder;
import org.apache.kafka.streams.processor.internals.Task;
import org.apache.kafka.streams.processor.internals.TopologyMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;



/**
 * This is currently an internal and experimental feature for enabling certain kinds of topology upgrades. Use at
 * your own risk.
 *
 * Status: additive upgrades possible, removal of NamedTopologies not yet supported
 *
 * Note: some standard features of Kafka Streams are not yet supported with NamedTopologies. These include:
 *       - global state stores
 *       - interactive queries (IQ)
 *       - TopologyTestDriver (TTD)
 */
@Unstable
public class KafkaStreamsNamedTopologyWrapper extends KafkaStreams {

    private final Logger log = LoggerFactory.getLogger(KafkaStreamsNamedTopologyWrapper.class);

    /**
     * An empty Kafka Streams application that allows NamedTopologies to be added at a later point
     */
    public KafkaStreamsNamedTopologyWrapper(final Properties props) {
        this(new StreamsConfig(props), new DefaultKafkaClientSupplier());
    }

    public KafkaStreamsNamedTopologyWrapper(final Properties props, final KafkaClientSupplier clientSupplier) {
        this(new StreamsConfig(props), clientSupplier);
    }

    private KafkaStreamsNamedTopologyWrapper(final StreamsConfig config, final KafkaClientSupplier clientSupplier) {
        super(new TopologyMetadata(new ConcurrentSkipListMap<>(), config), config, clientSupplier);
    }

    /**
     * Start up Streams with a single initial NamedTopology
     */
    public void start(final NamedTopology initialTopology) {
        start(Collections.singleton(initialTopology));
    }

    /**
     * Start up Streams with a collection of initial NamedTopologies
     */
    public void start(final Collection<NamedTopology> initialTopologies) {
        for (final NamedTopology topology : initialTopologies) {
            addNamedTopology(topology);
        }
        super.start();
    }

    /**
     * Provides a high-level DSL for specifying the processing logic of your application and building it into an
     * independent topology that can be executed by this {@link KafkaStreams}.
     *
     * @param topologyName              The name for this topology
     * @param topologyOverrides         The properties and any config overrides for this topology
     *
     * @throws IllegalArgumentException if the name contains the character sequence "__"
     */
    public NamedTopologyBuilder newNamedTopologyBuilder(final String topologyName, final Properties topologyOverrides) {
        if (topologyName.contains(TaskId.NAMED_TOPOLOGY_DELIMITER)) {
            throw new IllegalArgumentException("The character sequence '__' is not allowed in a NamedTopology, please select a new name");
        }
        return new NamedTopologyBuilder(topologyName, applicationConfigs, topologyOverrides);
    }

    /**
     * Provides a high-level DSL for specifying the processing logic of your application and building it into an
     * independent topology that can be executed by this {@link KafkaStreams}. This method will use the global
     * application {@link StreamsConfig} passed in to the constructor for all topology-level configs. To override
     * any of these for this specific Topology, use {@link #newNamedTopologyBuilder(String, Properties)}.
     * @param topologyName              The name for this topology
     *
     * @throws IllegalArgumentException if the name contains the character sequence "__"
     */
    public NamedTopologyBuilder newNamedTopologyBuilder(final String topologyName) {
        return newNamedTopologyBuilder(topologyName, new Properties());
    }

    /**
     * @return the NamedTopology for the specific name, or Optional.empty() if the application has no NamedTopology of that name
     */
    public Optional<NamedTopology> getTopologyByName(final String name) {
        return Optional.ofNullable(topologyMetadata.lookupBuilderForNamedTopology(name)).map(InternalTopologyBuilder::namedTopology);
    }

    /**
     * Add a new NamedTopology to a running Kafka Streams app. If multiple instances of the application are running,
     * you should inform all of them by calling {@code #addNamedTopology(NamedTopology)} on each client in order for
     * it to begin processing the new topology.
     * This method is not purely Async.
     *
     * @throws IllegalArgumentException if this topology name is already in use
     * @throws IllegalStateException    if streams has not been started or has already shut down
     * @throws TopologyException        if this topology subscribes to any input topics or pattern already in use
     */
    public AddNamedTopologyResult addNamedTopology(final NamedTopology newTopology) {
        log.info("Adding topology: {}", newTopology.name());
        if (hasStartedOrFinishedShuttingDown()) {
            throw new IllegalStateException("Cannot add a NamedTopology while the state is " + super.state);
        } else if (getTopologyByName(newTopology.name()).isPresent()) {
            throw new IllegalArgumentException("Unable to add the new NamedTopology " + newTopology.name() +
                                                   " as another of the same name already exists");
        }
        return new AddNamedTopologyResult(
            topologyMetadata.registerAndBuildNewTopology(newTopology.internalTopologyBuilder())
        );
    }

    /**
     * Remove an existing NamedTopology from a running Kafka Streams app. If multiple instances of the application are
     * running, you should inform all of them by calling {@code #removeNamedTopology(String)} on each client to ensure
     * it stops processing the old topology.
     * This method is not purely Async.
     *
     * @param topologyToRemove          name of the topology to be removed
     * @param resetOffsets              whether to reset the committed offsets for any source topics
     *
     * @throws IllegalArgumentException if this topology name cannot be found
     * @throws IllegalStateException    if streams has not been started or has already shut down
     * @throws TopologyException        if this topology subscribes to any input topics or pattern already in use
     */
    public RemoveNamedTopologyResult removeNamedTopology(final String topologyToRemove, final boolean resetOffsets) {
        log.info("Removing topology: {}", topologyToRemove);
        if (!isRunningOrRebalancing()) {
            throw new IllegalStateException("Cannot remove a NamedTopology while the state is " + super.state);
        } else if (!getTopologyByName(topologyToRemove).isPresent()) {
            throw new IllegalArgumentException("Unable to locate for removal a NamedTopology called " + topologyToRemove);
        }
        final Set<TopicPartition> partitionsToReset = metadataForLocalThreads()
            .stream()
            .flatMap(t -> {
                final Set<TaskMetadata> tasks = new HashSet<>(t.activeTasks());
                return tasks.stream();
            })
            .flatMap(t -> t.topicPartitions().stream())
            .filter(t -> topologyMetadata.sourceTopicsForTopology(topologyToRemove).contains(t.topic()))
            .collect(Collectors.toSet());

        final KafkaFuture<Void> removeTopologyFuture = topologyMetadata.unregisterTopology(topologyToRemove);

        if (resetOffsets) {
            final KafkaFutureImpl<Void> future = new KafkaFutureImpl<>();
            log.info("Resetting offsets for the following partitions of NamedTopology {}: {}", topologyToRemove, partitionsToReset);
            if (!partitionsToReset.isEmpty()) {
                removeTopologyFuture.whenComplete((v, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                    }
                    DeleteConsumerGroupOffsetsResult deleteOffsetsResult = null;
                    while (deleteOffsetsResult == null) {
                        try {
                            deleteOffsetsResult = adminClient.deleteConsumerGroupOffsets(
                                applicationConfigs.getString(StreamsConfig.APPLICATION_ID_CONFIG), partitionsToReset);
                            deleteOffsetsResult.all().get();
                        } catch (final InterruptedException ex) {
                            ex.printStackTrace();
                            break;
                        } catch (final ExecutionException ex) {
                            if (ex.getCause() != null &&
                                ex.getCause() instanceof GroupSubscribedToTopicException &&
                                ex.getCause()
                                    .getMessage()
                                    .equals("Deleting offsets of a topic is forbidden while the consumer group is actively subscribed to it.")) {
                                ex.printStackTrace();
                            } else if (ex.getCause() != null &&
                                ex.getCause() instanceof GroupIdNotFoundException) {
                                log.debug("The offsets have been reset by another client or the group has been deleted, no need to retry further.");
                                break;
                            } else {
                                future.completeExceptionally(ex);
                            }
                            deleteOffsetsResult = null;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (final InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                    future.complete(null);
                });
                return new RemoveNamedTopologyResult(removeTopologyFuture, future);
            }
        }
        return new RemoveNamedTopologyResult(removeTopologyFuture);
    }

    /**
     * Remove an existing NamedTopology from a running Kafka Streams app. If multiple instances of the application are
     * running, you should inform all of them by calling {@code #removeNamedTopology(String)} on each client to ensure
     * it stops processing the old topology.
     * This method is not purely Async.
     *
     * @param topologyToRemove          name of the topology to be removed
     *
     * @throws IllegalArgumentException if this topology name cannot be found
     * @throws IllegalStateException    if streams has not been started or has already shut down
     * @throws TopologyException        if this topology subscribes to any input topics or pattern already in use
     */
    public RemoveNamedTopologyResult removeNamedTopology(final String topologyToRemove) {
        return removeNamedTopology(topologyToRemove, false);
    }

    /**
     * Do a clean up of the local state directory for this NamedTopology by deleting all data with regard to the
     * @link StreamsConfig#APPLICATION_ID_CONFIG application ID} in the ({@link StreamsConfig#STATE_DIR_CONFIG})
     * <p>
     * May be called while the Streams is in any state, but only on a {@link NamedTopology} that has already been
     * removed via {@link #removeNamedTopology(String)}.
     * <p>
     * Calling this method triggers a restore of local {@link StateStore}s for this {@link NamedTopology} if it is
     * ever re-added via {@link #addNamedTopology(NamedTopology)}.
     *
     * @throws IllegalStateException if this {@code NamedTopology} hasn't been removed
     * @throws StreamsException if cleanup failed
     */
    public void cleanUpNamedTopology(final String name) {
        if (getTopologyByName(name).isPresent()) {
            throw new IllegalStateException("Can't clean up local state for an active NamedTopology: " + name);
        }
        stateDirectory.clearLocalStateForNamedTopology(name);
    }

    public String getFullTopologyDescription() {
        return topologyMetadata.topologyDescriptionString();
    }

    private void verifyTopologyStateStore(final String topologyName, final String storeName) {
        final InternalTopologyBuilder builder = topologyMetadata.lookupBuilderForNamedTopology(topologyName);
        if (builder == null) {
            throw new UnknownStateStoreException(
                "Cannot get state store " + storeName + " from NamedTopology " + topologyName +
                    " because no such topology exists."
            );
        } else if (!builder.hasStore(storeName)) {
            throw new UnknownStateStoreException(
                "Cannot get state store " + storeName + " from NamedTopology " + topologyName +
                    " because no such state store exists in this topology."
            );
        }
    }

    /**
     * See {@link KafkaStreams#store(StoreQueryParameters)}
     */
    public <T> T store(final NamedTopologyStoreQueryParameters<T> storeQueryParameters) {
        final String topologyName = storeQueryParameters.topologyName;
        final String storeName = storeQueryParameters.storeName();
        verifyTopologyStateStore(topologyName, storeName);
        return super.store(storeQueryParameters);
    }

    /**
     * See {@link KafkaStreams#streamsMetadataForStore(String)}
     */
    public Collection<StreamsMetadata> streamsMetadataForStore(final String storeName, final String topologyName) {
        verifyTopologyStateStore(topologyName, storeName);
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getAllMetadataForStore(storeName, topologyName);
    }

    /**
     * See {@link KafkaStreams#queryMetadataForKey(String, Object, Serializer)}
     */
    public <K> KeyQueryMetadata queryMetadataForKey(final String storeName,
                                                    final K key,
                                                    final Serializer<K> keySerializer,
                                                    final String topologyName) {
        verifyTopologyStateStore(topologyName, storeName);
        validateIsRunningOrRebalancing();
        return streamsMetadataState.getKeyQueryMetadataForKey(storeName, key, keySerializer, topologyName);
    }

    /**
     * See {@link KafkaStreams#allLocalStorePartitionLags()}
     */
    public Map<String, Map<Integer, LagInfo>> allLocalStorePartitionLagsForTopology(final String topologyName) {
        final List<Task> allTopologyTasks = new ArrayList<>();
        processStreamThread(thread -> allTopologyTasks.addAll(
            thread.allTasks().values().stream()
                .filter(t -> topologyName.equals(t.id().topologyName()))
                .collect(Collectors.toList())));
        return allLocalStorePartitionLags(allTopologyTasks);
    }
}
