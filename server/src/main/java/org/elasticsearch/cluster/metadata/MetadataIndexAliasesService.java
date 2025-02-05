/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesClusterStateUpdateRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.AliasAction.NewAliasValidator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.indices.IndicesService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.indices.cluster.IndicesClusterStateService.AllocatedIndices.IndexRemovalReason.NO_LONGER_ASSIGNED;

/**
 * Service responsible for submitting add and remove aliases requests
 */
public class MetadataIndexAliasesService {

    private final ClusterService clusterService;

    private final IndicesService indicesService;

    private final AliasValidator aliasValidator;

    private final MetadataDeleteIndexService deleteIndexService;

    private final NamedXContentRegistry xContentRegistry;

    @Inject
    public MetadataIndexAliasesService(ClusterService clusterService, IndicesService indicesService,
            AliasValidator aliasValidator, MetadataDeleteIndexService deleteIndexService, NamedXContentRegistry xContentRegistry) {
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.aliasValidator = aliasValidator;
        this.deleteIndexService = deleteIndexService;
        this.xContentRegistry = xContentRegistry;
    }

    public void indicesAliases(final IndicesAliasesClusterStateUpdateRequest request,
                               final ActionListener<AcknowledgedResponse> listener) {
        clusterService.submitStateUpdateTask("index-aliases",
            new AckedClusterStateUpdateTask(Priority.URGENT, request, listener) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return applyAliasActions(currentState, request.actions());
                }
            });
    }

    /**
     * Handles the cluster state transition to a version that reflects the provided {@link AliasAction}s.
     */
     public ClusterState applyAliasActions(ClusterState currentState, Iterable<AliasAction> actions) {
        List<Index> indicesToClose = new ArrayList<>();
        Map<String, IndexService> indices = new HashMap<>();
        try {
            boolean changed = false;
            // Gather all the indexes that must be removed first so:
            // 1. We don't cause error when attempting to replace an index with a alias of the same name.
            // 2. We don't allow removal of aliases from indexes that we're just going to delete anyway. That'd be silly.
            Set<Index> indicesToDelete = new HashSet<>();
            for (AliasAction action : actions) {
                if (action.removeIndex()) {
                    IndexMetadata index = currentState.metadata().getIndices().get(action.getIndex());
                    if (index == null) {
                        throw new IndexNotFoundException(action.getIndex());
                    }
                    validateAliasTargetIsNotDSBackingIndex(currentState, action);
                    indicesToDelete.add(index.getIndex());
                    changed = true;
                }
            }
            // Remove the indexes if there are any to remove
            if (changed) {
                currentState = deleteIndexService.deleteIndices(currentState, indicesToDelete);
            }
            Metadata.Builder metadata = Metadata.builder(currentState.metadata());
            // Run the remaining alias actions
            final Set<String> maybeModifiedIndices = new HashSet<>();
            for (AliasAction action : actions) {
                if (action.removeIndex()) {
                    // Handled above
                    continue;
                }

                /* It is important that we look up the index using the metadata builder we are modifying so we can remove an
                 * index and replace it with an alias. */
                Function<String, String> lookup = name -> {
                    IndexMetadata imd = metadata.get(name);
                    if (imd != null) {
                        return imd.getIndex().getName();
                    }
                    DataStream dataStream = metadata.dataStream(name);
                    if (dataStream != null) {
                        return dataStream.getName();
                    }
                    return null;
                };

                // Handle the actions that do data streams aliases separately:
                DataStream dataStream = metadata.dataStream(action.getIndex());
                if (dataStream != null) {
                    NewAliasValidator newAliasValidator = (alias, indexRouting, filter, writeIndex) -> {
                        aliasValidator.validateAlias(alias, action.getIndex(), indexRouting, lookup);
                        if (Strings.hasLength(filter)) {
                            for (Index index : dataStream.getIndices()) {
                                IndexMetadata imd = metadata.get(index.getName());
                                if (imd == null) {
                                    throw new IndexNotFoundException(action.getIndex());
                                }
                                validateFilter(indicesToClose, indices, action, imd, alias, filter);
                            }
                        }
                    };
                    if (action.apply(newAliasValidator, metadata, null)) {
                        changed = true;
                    }
                    continue;
                }

                IndexMetadata index = metadata.get(action.getIndex());
                if (index == null) {
                    throw new IndexNotFoundException(action.getIndex());
                }
                validateAliasTargetIsNotDSBackingIndex(currentState, action);
                NewAliasValidator newAliasValidator = (alias, indexRouting, filter, writeIndex) -> {
                    aliasValidator.validateAlias(alias, action.getIndex(), indexRouting, lookup);
                    if (Strings.hasLength(filter)) {
                        validateFilter(indicesToClose, indices, action, index, alias, filter);
                    }
                };
                if (action.apply(newAliasValidator, metadata, index)) {
                    changed = true;
                    maybeModifiedIndices.add(index.getIndex().getName());
                }
            }

            for (final String maybeModifiedIndex : maybeModifiedIndices) {
                final IndexMetadata currentIndexMetadata = currentState.metadata().index(maybeModifiedIndex);
                final IndexMetadata newIndexMetadata = metadata.get(maybeModifiedIndex);
                // only increment the aliases version if the aliases actually changed for this index
                if (currentIndexMetadata.getAliases().equals(newIndexMetadata.getAliases()) == false) {
                    assert currentIndexMetadata.getAliasesVersion() == newIndexMetadata.getAliasesVersion();
                    metadata.put(new IndexMetadata.Builder(newIndexMetadata).aliasesVersion(1 + currentIndexMetadata.getAliasesVersion()));
                }
            }

            if (changed) {
                ClusterState updatedState = ClusterState.builder(currentState).metadata(metadata).build();
                // even though changes happened, they resulted in 0 actual changes to metadata
                // i.e. remove and add the same alias to the same index
                if (updatedState.metadata().equalsAliases(currentState.metadata()) == false) {
                    return updatedState;
                }
            }
            return currentState;
        } finally {
            for (Index index : indicesToClose) {
                indicesService.removeIndex(index, NO_LONGER_ASSIGNED, "created for alias processing");
            }
        }
    }

    private void validateFilter(List<Index> indicesToClose,
                                Map<String, IndexService> indices,
                                AliasAction action,
                                IndexMetadata index,
                                String alias,
                                String filter) {
        IndexService indexService = indices.get(index.getIndex().getName());
        if (indexService == null) {
            indexService = indicesService.indexService(index.getIndex());
            if (indexService == null) {
                // temporarily create the index and add mappings so we can parse the filter
                try {
                    indexService = indicesService.createIndex(index, emptyList(), false);
                    indicesToClose.add(index.getIndex());
                } catch (IOException e) {
                    throw new ElasticsearchException("Failed to create temporary index for parsing the alias", e);
                }
                indexService.mapperService().merge(index, MapperService.MergeReason.MAPPING_RECOVERY);
            }
            indices.put(action.getIndex(), indexService);
        }
        // the context is only used for validation so it's fine to pass fake values for the shard id,
        // but the current timestamp should be set to real value as we may use `now` in a filtered alias
        aliasValidator.validateAliasFilter(alias, filter, indexService.newSearchExecutionContext(0, 0,
                null, System::currentTimeMillis, null, emptyMap()), xContentRegistry);
    }

    private void validateAliasTargetIsNotDSBackingIndex(ClusterState currentState, AliasAction action) {
        IndexAbstraction indexAbstraction = currentState.metadata().getIndicesLookup().get(action.getIndex());
        assert indexAbstraction != null : "invalid cluster metadata. index [" + action.getIndex() + "] was not found";
        if (indexAbstraction.getParentDataStream() != null) {
            throw new IllegalArgumentException("The provided index [" + action.getIndex()
                + "] is a backing index belonging to data stream [" + indexAbstraction.getParentDataStream().getName()
                + "]. Data streams and their backing indices don't support alias operations.");
        }
    }
}
