/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.base.sink.writer;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.connector.sink.Sink;
import org.apache.flink.api.connector.sink.SinkWriter;
import org.apache.flink.util.Preconditions;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * A generic sink writer that handles the general behaviour of a sink such as batching and flushing,
 * and allows extenders to implement the logic for persisting individual request elements, with
 * allowance for retries.
 *
 * <p>At least once semantics is supported through {@code prepareCommit} as outstanding requests are
 * flushed or completed prior to checkpointing.
 *
 * <p>Designed to be returned at {@code createWriter} time by an {@code AsyncSinkBase}.
 *
 * <p>There are configuration options to customize the buffer size etc.
 */
@PublicEvolving
public abstract class AsyncSinkWriter<InputT, RequestEntryT extends Serializable>
        implements SinkWriter<InputT, Void, Collection<RequestEntryT>> {

    private final MailboxExecutor mailboxExecutor;
    private final Sink.ProcessingTimeService timeService;

    private final int maxBatchSize;
    private final int maxInFlightRequests;
    private final int maxBufferedRequests;

    /**
     * The ElementConverter provides a mapping between for the elements of a stream to request
     * entries that can be sent to the destination.
     *
     * <p>The resulting request entry is buffered by the AsyncSinkWriter and sent to the destination
     * when the {@code submitRequestEntries} method is invoked.
     */
    private final ElementConverter<InputT, RequestEntryT> elementConverter;

    /**
     * Buffer to hold request entries that should be persisted into the destination.
     *
     * <p>A request entry contain all relevant details to make a call to the destination. Eg, for
     * Kinesis Data Streams a request entry contains the payload and partition key.
     *
     * <p>It seems more natural to buffer InputT, ie, the events that should be persisted, rather
     * than RequestEntryT. However, in practice, the response of a failed request call can make it
     * very hard, if not impossible, to reconstruct the original event. It is much easier, to just
     * construct a new (retry) request entry from the response and add that back to the queue for
     * later retry.
     */
    private final Deque<RequestEntryT> bufferedRequestEntries = new ArrayDeque<>();

    /**
     * Tracks all pending async calls that have been executed since the last checkpoint. Calls that
     * completed (successfully or unsuccessfully) are automatically decrementing the counter. Any
     * request entry that was not successfully persisted needs to be handled and retried by the
     * logic in {@code submitRequestsToApi}.
     *
     * <p>There is a limit on the number of concurrent (async) requests that can be handled by the
     * client library. This limit is enforced by checking the queue size before accepting a new
     * element into the queue.
     *
     * <p>To complete a checkpoint, we need to make sure that no requests are in flight, as they may
     * fail, which could then lead to data loss.
     */
    private int inFlightRequestsCount;

    /**
     * This method specifies how to persist buffered request entries into the destination. It is
     * implemented when support for a new destination is added.
     *
     * <p>The method is invoked with a set of request entries according to the buffering hints (and
     * the valid limits of the destination). The logic then needs to create and execute the request
     * against the destination (ideally by batching together multiple request entries to increase
     * efficiency). The logic also needs to identify individual request entries that were not
     * persisted successfully and resubmit them using the {@code requeueFailedRequestEntry} method.
     *
     * <p>During checkpointing, the sink needs to ensure that there are no outstanding in-flight
     * requests.
     *
     * @param requestEntries a set of request entries that should be sent to the destination
     * @param requestResult the {@code accept} method should be called on this Consumer once the
     *     processing of the {@code requestEntries} are complete. Any entries that encountered
     *     difficulties in persisting should be re-queued through {@code requestResult} by including
     *     that element in the collection of {@code RequestEntryT}s passed to the {@code accept}
     *     method. All other elements are assumed to have been successfully persisted.
     */
    protected abstract void submitRequestEntries(
            List<RequestEntryT> requestEntries, Consumer<Collection<RequestEntryT>> requestResult);

    public AsyncSinkWriter(
            ElementConverter<InputT, RequestEntryT> elementConverter,
            Sink.InitContext context,
            int maxBatchSize,
            int maxInFlightRequests,
            int maxBufferedRequests) {
        this.elementConverter = elementConverter;
        this.mailboxExecutor = context.getMailboxExecutor();
        this.timeService = context.getProcessingTimeService();

        Preconditions.checkNotNull(elementConverter);
        Preconditions.checkArgument(maxBatchSize > 0);
        Preconditions.checkArgument(maxBufferedRequests > 0);
        Preconditions.checkArgument(maxInFlightRequests > 0);
        Preconditions.checkArgument(
                maxBufferedRequests > maxBatchSize,
                "The maximum number of requests that may be buffered should be strictly"
                        + " greater than the maximum number of requests per batch.");
        this.maxBatchSize = maxBatchSize;
        this.maxInFlightRequests = maxInFlightRequests;
        this.maxBufferedRequests = maxBufferedRequests;
    }

    @Override
    public void write(InputT element, Context context) throws IOException, InterruptedException {
        while (bufferedRequestEntries.size() >= maxBufferedRequests) {
            mailboxExecutor.yield();
        }

        bufferedRequestEntries.add(elementConverter.apply(element, context));

        flushIfFull();
    }

    private void flushIfFull() throws InterruptedException {
        while (bufferedRequestEntries.size() >= maxBatchSize) {
            flush();
        }
    }

    /**
     * Persists buffered RequestsEntries into the destination by invoking {@code
     * submitRequestEntries} with batches according to the user specified buffering hints.
     *
     * <p>The method blocks if too many async requests are in flight.
     */
    private void flush() throws InterruptedException {
        while (inFlightRequestsCount >= maxInFlightRequests) {
            mailboxExecutor.yield();
        }

        List<RequestEntryT> batch = new ArrayList<>(maxBatchSize);

        int batchSize = Math.min(maxBatchSize, bufferedRequestEntries.size());
        for (int i = 0; i < batchSize; i++) {
            batch.add(bufferedRequestEntries.remove());
        }

        if (batch.size() == 0) {
            return;
        }

        Consumer<Collection<RequestEntryT>> requestResult =
                failedRequestEntries ->
                        mailboxExecutor.execute(
                                () -> completeRequest(failedRequestEntries),
                                "Mark in-flight request as completed and requeue %d request entries",
                                failedRequestEntries.size());

        inFlightRequestsCount++;
        submitRequestEntries(batch, requestResult);
    }

    /**
     * Marks an in-flight request as completed and prepends failed requestEntries back to the
     * internal requestEntry buffer for later retry.
     *
     * @param failedRequestEntries requestEntries that need to be retried
     */
    private void completeRequest(Collection<RequestEntryT> failedRequestEntries) {
        inFlightRequestsCount--;
        failedRequestEntries.forEach(bufferedRequestEntries::addFirst);
    }

    /**
     * In flight requests will be retried if the sink is still healthy. But if in-flight requests
     * fail after a checkpoint has been triggered and Flink needs to recover from the checkpoint,
     * the (failed) in-flight requests are gone and cannot be retried. Hence, there cannot be any
     * outstanding in-flight requests when a commit is initialized.
     *
     * <p>To this end, all in-flight requests need to completed before proceeding with the commit.
     */
    @Override
    public List<Void> prepareCommit(boolean flush) throws InterruptedException {
        while (inFlightRequestsCount > 0 || bufferedRequestEntries.size() > 0) {
            mailboxExecutor.yield();
            if (flush) {
                flush();
            }
        }

        return Collections.emptyList();
    }

    /**
     * All in-flight requests that are relevant for the snapshot have been completed, but there may
     * still be request entries in the internal buffers that are yet to be sent to the endpoint.
     * These request entries are stored in the snapshot state so that they don't get lost in case of
     * a failure/restart of the application.
     */
    @Override
    public List<Collection<RequestEntryT>> snapshotState() {
        return Arrays.asList(bufferedRequestEntries);
    }

    @Override
    public void close() {}
}
