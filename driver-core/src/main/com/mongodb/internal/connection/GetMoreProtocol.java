/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.connection;

import com.mongodb.MongoCursorNotFoundException;
import com.mongodb.MongoNamespace;
import com.mongodb.RequestContext;
import com.mongodb.internal.async.SingleResultCallback;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.diagnostics.logging.Logger;
import com.mongodb.diagnostics.logging.Loggers;
import com.mongodb.event.CommandListener;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.Decoder;

import java.util.Collections;
import java.util.List;

import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.internal.connection.ProtocolHelper.getQueryFailureException;
import static com.mongodb.internal.connection.ProtocolHelper.sendCommandFailedEvent;
import static com.mongodb.internal.connection.ProtocolHelper.sendCommandStartedEvent;
import static com.mongodb.internal.connection.ProtocolHelper.sendCommandSucceededEvent;
import static java.lang.String.format;

/**
 * An implementation of the OP_GET_MORE protocol.
 *
 * @param <T> the type of document to decode query results to
 * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
 */
class GetMoreProtocol<T> implements LegacyProtocol<QueryResult<T>> {

    public static final Logger LOGGER = Loggers.getLogger("protocol.getmore");
    private static final String COMMAND_NAME = "getMore";

    private final Decoder<T> resultDecoder;
    private final RequestContext requestContext;
    private final MongoNamespace namespace;
    private final long cursorId;
    private final int numberToReturn;
    private CommandListener commandListener;

    GetMoreProtocol(final MongoNamespace namespace, final long cursorId, final int numberToReturn, final Decoder<T> resultDecoder,
            final RequestContext requestContext) {
        this.namespace = namespace;
        this.cursorId = cursorId;
        this.numberToReturn = numberToReturn;
        this.resultDecoder = resultDecoder;
        this.requestContext = notNull("requestContext", requestContext);
    }

    @Override
    public QueryResult<T> execute(final InternalConnection connection) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(format("Getting more documents from namespace %s with cursor %d on connection [%s] to server %s",
                                namespace, cursorId, connection.getDescription().getConnectionId(),
                                connection.getDescription().getServerAddress()));
        }
        long startTimeNanos = System.nanoTime();
        GetMoreMessage message = new GetMoreMessage(namespace.getFullName(), cursorId, numberToReturn);
        QueryResult<T> result = null;
        try {
            sendMessage(message, connection);
            ResponseBuffers responseBuffers = connection.receiveMessage(message.getId());
            try {
                if (responseBuffers.getReplyHeader().isCursorNotFound()) {
                    throw new MongoCursorNotFoundException(message.getCursorId(), connection.getDescription().getServerAddress());
                }

                if (responseBuffers.getReplyHeader().isQueryFailure()) {
                    BsonDocument errorDocument = new ReplyMessage<BsonDocument>(responseBuffers, new BsonDocumentCodec(),
                                                                                message.getId()).getDocuments().get(0);
                    throw getQueryFailureException(errorDocument, connection.getDescription().getServerAddress());
                }


                ReplyMessage<T> replyMessage = new ReplyMessage<T>(responseBuffers, resultDecoder, message.getId());
                result = new QueryResult<T>(namespace, replyMessage.getDocuments(),
                        replyMessage.getReplyHeader().getCursorId(), connection.getDescription().getServerAddress());

                if (commandListener != null) {
                    sendCommandSucceededEvent(message, COMMAND_NAME,
                                              asGetMoreCommandResponseDocument(result, responseBuffers), connection.getDescription(),
                                              System.nanoTime() - startTimeNanos, commandListener, requestContext);
                }
            } finally {
                responseBuffers.close();
            }
            LOGGER.debug("Get-more completed");
            return result;
        } catch (RuntimeException e) {
            if (commandListener != null) {
                sendCommandFailedEvent(message, COMMAND_NAME, connection.getDescription(), System.nanoTime() - startTimeNanos, e,
                        commandListener, requestContext);
            }
            throw e;
        }
    }

    @Override
    public void executeAsync(final InternalConnection connection, final SingleResultCallback<QueryResult<T>> callback) {
        long startTimeNanos = System.nanoTime();
        GetMoreMessage message = new GetMoreMessage(namespace.getFullName(), cursorId, numberToReturn);
        boolean sentStartedEvent = false;
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(format("Asynchronously getting more documents from namespace %s with cursor %d on connection [%s] to server "
                                    + "%s", namespace, cursorId, connection.getDescription().getConnectionId(),
                                    connection.getDescription().getServerAddress()));
            }

            ByteBufferBsonOutput bsonOutput = new ByteBufferBsonOutput(connection);

            if (commandListener != null) {
                sendCommandStartedEvent(message, namespace.getDatabaseName(), COMMAND_NAME, asGetMoreCommandDocument(),
                        connection.getDescription(), commandListener, requestContext);
                sentStartedEvent = true;
            }

            ProtocolHelper.encodeMessage(message, bsonOutput);
            SingleResultCallback<ResponseBuffers> receiveCallback = new GetMoreResultCallback(callback,
                                                                                              cursorId,
                                                                                              message,
                                                                                              connection.getDescription(),
                                                                                              commandListener, startTimeNanos);
            connection.sendMessageAsync(bsonOutput.getByteBuffers(), message.getId(),
                                        new SendMessageCallback<QueryResult<T>>(connection, bsonOutput, message, COMMAND_NAME,
                                                startTimeNanos, commandListener, requestContext, callback, receiveCallback));
        } catch (Throwable t) {
            if (sentStartedEvent) {
                sendCommandFailedEvent(message, COMMAND_NAME, connection.getDescription(), System.nanoTime() - startTimeNanos, t,
                        commandListener, requestContext);
            }
            callback.onResult(null, t);
        }
    }

    @Override
    public void setCommandListener(final CommandListener commandListener) {
        this.commandListener = commandListener;
    }

    private void sendMessage(final GetMoreMessage message, final InternalConnection connection) {
        ByteBufferBsonOutput bsonOutput = new ByteBufferBsonOutput(connection);
        try {
            if (commandListener != null) {
                sendCommandStartedEvent(message, namespace.getDatabaseName(), COMMAND_NAME, asGetMoreCommandDocument(),
                                        connection.getDescription(), commandListener, requestContext);
            }
            message.encode(bsonOutput, NoOpSessionContext.INSTANCE);
            connection.sendMessage(bsonOutput.getByteBuffers(), message.getId());
        } finally {
            bsonOutput.close();
        }
    }

    private BsonDocument asGetMoreCommandDocument() {
        return new BsonDocument(COMMAND_NAME, new BsonInt64(cursorId))
               .append("collection", new BsonString(namespace.getCollectionName()))
               .append("batchSize", new BsonInt32(numberToReturn));
    }


    private BsonDocument asGetMoreCommandResponseDocument(final QueryResult<T> queryResult, final ResponseBuffers responseBuffers) {
        List<ByteBufBsonDocument> rawResultDocuments = Collections.emptyList();
        if (responseBuffers.getReplyHeader().getNumberReturned() != 0) {
            responseBuffers.reset();
            rawResultDocuments = ByteBufBsonDocument.createList(responseBuffers);
        }

        BsonDocument cursorDocument = new BsonDocument("id",
                                                       queryResult.getCursor() == null
                                                       ? new BsonInt64(0) : new BsonInt64(queryResult.getCursor().getId()))
                                      .append("ns", new BsonString(namespace.getFullName()))
                                      .append("nextBatch", new BsonArray(rawResultDocuments));

        return new BsonDocument("cursor", cursorDocument)
               .append("ok", new BsonDouble(1));
    }

    class GetMoreResultCallback extends ResponseCallback {
        private final SingleResultCallback<QueryResult<T>> callback;
        private final long cursorId;
        private final GetMoreMessage message;
        private final ConnectionDescription connectionDescription;
        private final CommandListener commandListener;
        private final long startTimeNanos;

        GetMoreResultCallback(final SingleResultCallback<QueryResult<T>> callback, final long cursorId, final GetMoreMessage message,
                              final ConnectionDescription connectionDescription, final CommandListener commandListener,
                              final long startTimeNanos) {
            super(message.getId(), connectionDescription.getServerAddress());
            this.callback = callback;
            this.cursorId = cursorId;
            this.message = message;
            this.connectionDescription = connectionDescription;
            this.commandListener = commandListener;
            this.startTimeNanos = startTimeNanos;
        }

        @Override
        protected void callCallback(final ResponseBuffers responseBuffers, final Throwable throwableFromCallback) {
            try {
                if (throwableFromCallback != null) {
                    throw throwableFromCallback;
                } else if (responseBuffers.getReplyHeader().isCursorNotFound()) {
                    throw new MongoCursorNotFoundException(cursorId, getServerAddress());
                } else if (responseBuffers.getReplyHeader().isQueryFailure()) {
                    BsonDocument errorDocument = new ReplyMessage<BsonDocument>(responseBuffers, new BsonDocumentCodec(),
                            message.getId()).getDocuments().get(0);
                    throw getQueryFailureException(errorDocument, connectionDescription.getServerAddress());
                } else {
                    ReplyMessage<T> replyMessage = new ReplyMessage<T>(responseBuffers, resultDecoder, getRequestId());
                    QueryResult<T> result = new QueryResult<T>(namespace, replyMessage.getDocuments(),
                            replyMessage.getReplyHeader().getCursorId(), getServerAddress());
                    if (commandListener != null) {
                        sendCommandSucceededEvent(message, COMMAND_NAME,
                                asGetMoreCommandResponseDocument(result, responseBuffers), connectionDescription,
                                System.nanoTime() - startTimeNanos, commandListener, requestContext);
                    }

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(format("GetMore results received %s documents with cursor %s",
                                            result.getResults().size(),
                                            result.getCursor()));
                    }
                    callback.onResult(result, null);
                }
            } catch (Throwable t) {
                if (commandListener != null) {
                    sendCommandFailedEvent(message, COMMAND_NAME, connectionDescription, System.nanoTime() - startTimeNanos, t,
                            commandListener, requestContext);
                }
                callback.onResult(null, t);
            } finally {
                try {
                    if (responseBuffers != null) {
                        responseBuffers.close();
                    }
                } catch (Throwable t1) {
                    LOGGER.debug("GetMore ResponseBuffer close exception", t1);
                }
            }
        }
    }
}
