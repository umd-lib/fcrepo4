/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.persistence.ocfl.impl;

import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.models.ResourceHeaders;
import org.fcrepo.kernel.api.operations.CreateResourceOperation;
import org.fcrepo.kernel.api.operations.RdfSourceOperation;
import org.fcrepo.kernel.api.operations.ResourceOperation;
import org.fcrepo.kernel.api.operations.ResourceOperationType;
import org.fcrepo.persistence.api.WriteOutcome;
import org.fcrepo.persistence.api.exceptions.PersistentStorageException;
import org.fcrepo.persistence.common.ResourceHeadersImpl;
import org.fcrepo.persistence.ocfl.api.FedoraToOcflObjectIndex;
import org.fcrepo.persistence.ocfl.api.OcflObjectSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.fcrepo.kernel.api.RdfLexicon.NON_RDF_SOURCE;
import static org.fcrepo.kernel.api.operations.ResourceOperationType.CREATE;
import static org.fcrepo.persistence.common.ResourceHeaderUtils.newResourceHeaders;
import static org.fcrepo.persistence.common.ResourceHeaderUtils.touchCreationHeaders;
import static org.fcrepo.persistence.common.ResourceHeaderUtils.touchModificationHeaders;
import static org.fcrepo.persistence.ocfl.impl.OcflPersistentStorageUtils.writeRDF;

/**
 * This class implements the persistence of a new RDFSource
 *
 * @author dbernstein
 * @since 6.0.0
 */
abstract class AbstractRdfSourcePersister extends AbstractPersister {

    private static final Logger log = LoggerFactory.getLogger(AbstractRdfSourcePersister.class);

    /**
     * Constructor
     */
    protected AbstractRdfSourcePersister(final Class<? extends ResourceOperation> resourceOperation,
                                         final ResourceOperationType resourceOperationType,
                                         final FedoraToOcflObjectIndex index) {
        super(resourceOperation, resourceOperationType, index);
    }

    /**
     * Persists the RDF using the specified operation and session.
     * @param session The session.
     * @param operation The operation
     * @param rootId The fedora root object identifier tha maps to the OCFL object root.
     * @throws PersistentStorageException
     */
    protected void persistRDF(final OcflObjectSession session, final ResourceOperation operation,
                              final FedoraId rootId) throws PersistentStorageException {

        final RdfSourceOperation rdfSourceOp = (RdfSourceOperation)operation;
        log.debug("persisting RDFSource ({}) to OCFL", operation.getResourceId());

        final var contentPath = resolveRdfContentPath(session, rootId, operation.getResourceId());
        final var headerPath = PersistencePaths.headerPath(rootId, operation.getResourceId());

        //write user triples
        final var outcome = writeRDF(session, rdfSourceOp.getTriples(), contentPath);

        // Write resource headers
        final var headers = populateHeaders(session, headerPath, rdfSourceOp, outcome,
                operation.getResourceId().equals(rootId), contentPath);
        writeHeaders(session, headers, headerPath);
    }

    /**
     * Constructs a ResourceHeaders object populated with the properties provided by the
     * operation, and merged with existing properties if appropriate.
     *
     * @param objSession the object session
     * @param headerPath the headerPath of the file
     * @param operation the operation being persisted
     * @param outcome outcome of persisting the RDF file
     * @param objectRoot indicates this is the object root
     * @return populated resource headers
     * @throws PersistentStorageException if unexpectedly unable to retrieve existing object headers
     */
    private ResourceHeaders populateHeaders(final OcflObjectSession objSession, final String headerPath,
                                            final RdfSourceOperation operation, final WriteOutcome outcome,
                                            final boolean objectRoot, final String contentPath)
            throws PersistentStorageException {

        final ResourceHeadersImpl headers;
        final var timeWritten = outcome.getTimeWritten();
        if (CREATE.equals(operation.getType())) {
            final var createOperation = (CreateResourceOperation) operation;
            headers = newResourceHeaders(createOperation.getParentId(),
                    operation.getResourceId(),
                    createOperation.getInteractionModel());
            touchCreationHeaders(headers, operation.getUserPrincipal(), timeWritten);
            headers.setArchivalGroup(createOperation.isArchivalGroup());
            headers.setObjectRoot(objectRoot);
            headers.setContentPath(contentPath);
        } else {
            headers = (ResourceHeadersImpl) readHeaders(objSession, headerPath);
        }
        touchModificationHeaders(headers, operation.getUserPrincipal(), timeWritten);

        overrideRelaxedProperties(headers, operation);
        return headers;
    }

    /**
     * Overrides generated creation and modification headers with the values
     * provided in the operation if they are present. They should only be present
     * if the server is in relaxed mode for handling server managed triples
     *
     * @param headers the resource headers
     * @param operation the operation
     */
    private void overrideRelaxedProperties(final ResourceHeadersImpl headers, final RdfSourceOperation operation) {
        // Override relaxed properties if provided
        if (operation.getLastModifiedBy() != null) {
            headers.setLastModifiedBy(operation.getLastModifiedBy());
        }
        if (operation.getLastModifiedDate() != null) {
            headers.setLastModifiedDate(operation.getLastModifiedDate());
        }
        if (operation.getCreatedBy() != null) {
            headers.setCreatedBy(operation.getCreatedBy());
        }
        if (operation.getCreatedDate() != null) {
            headers.setCreatedDate(operation.getCreatedDate());
        }
    }

    private String resolveRdfContentPath(final OcflObjectSession session,
                                         final FedoraId rootId,
                                         final FedoraId resourceId) throws PersistentStorageException {
        final String contentPath;

        if (resourceId.isAcl()) {
            final var parentHeaders = readHeaders(session,
                    PersistencePaths.headerPath(rootId, resourceId.asBaseId()));
            final var isRdf = !NON_RDF_SOURCE.getURI().equals(parentHeaders.getInteractionModel());
            contentPath = PersistencePaths.aclContentPath(isRdf, rootId, resourceId);
        } else {
            contentPath = PersistencePaths.rdfContentPath(rootId, resourceId);
        }

        return contentPath;
    }

}