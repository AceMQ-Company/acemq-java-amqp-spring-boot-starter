/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.spring.boot;

import org.acemq.amqp.api.ApplyMode;
import org.acemq.amqp.api.Topology;

/**
 * Turns declared {@code acemq.topology.*} properties into a {@link Topology}.
 *
 * <p>Pure, and tested without a broker, for the same reason {@link AceMqConnections} is: the
 * failure this guards against is a queue declared as classic when the file said quorum, and
 * that is visible in the built object long before it is visible on a broker.
 */
public final class AceMqTopologies {

    private AceMqTopologies() {}

    /**
     * Builds the topology.
     *
     * @param properties the {@code acemq.topology} block
     * @return what the application expects to exist
     * @throws IllegalArgumentException when a name is missing, because a queue with no name
     *     is a configuration mistake that would otherwise reach the broker as one, or when a
     *     quorum queue carries arguments the library cannot pass on
     */
    public static Topology from(AceMqProperties.Topology properties) {
        Topology.Builder builder = Topology.define();
        for (AceMqProperties.Topology.Exchange exchange : properties.getExchanges()) {
            require(exchange.getName(), "acemq.topology.exchanges[].name");
            builder.exchange(exchange.getName(), exchange.getType());
        }
        for (AceMqProperties.Topology.Queue queue : properties.getQueues()) {
            require(queue.getName(), "acemq.topology.queues[].name");
            boolean classic = queue.getType() == AceMqProperties.Topology.Queue.Kind.CLASSIC;
            // Topology.queue() is a durable quorum queue, which is the library's default,
            // and it takes no arguments. The quorum-queue arguments people reach for first
            // (x-max-priority, x-message-ttl on the queue) are the ones RabbitMQ refuses on
            // a quorum queue anyway, so there is nothing to pass through -- but dropping
            // them without a word is worse than not offering them. A file that asks for
            // both is refused, with the queue named, rather than started with half of what
            // it wrote down.
            if (!classic && !queue.getArguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "acemq.topology.queues[].arguments is not accepted for a quorum queue,"
                                + " and '" + queue.getName() + "' has "
                                + queue.getArguments().keySet()
                                + ". Declare it with type: classic, or drop the arguments.");
            }
            if (classic && queue.isDeadLetter()) {
                builder.classicQueueWithDeadLetter(queue.getName(), queue.getArguments());
            } else if (classic) {
                builder.classicQueue(queue.getName(), queue.getArguments());
            } else if (queue.isDeadLetter()) {
                builder.queueWithDeadLetter(queue.getName());
            } else {
                builder.queue(queue.getName());
            }
        }
        for (AceMqProperties.Topology.Binding binding : properties.getBindings()) {
            require(binding.getQueue(), "acemq.topology.bindings[].queue");
            require(binding.getExchange(), "acemq.topology.bindings[].exchange");
            builder.bind(binding.getQueue(), binding.getExchange(), binding.getRoutingKey());
        }
        return builder.build();
    }

    /**
     * Maps the property to the library's apply mode.
     *
     * @param apply what the properties asked for
     * @return the library's equivalent
     */
    public static ApplyMode mode(AceMqProperties.Topology.Apply apply) {
        switch (apply) {
            case CREATE_ONLY:
                return ApplyMode.CREATE_ONLY;
            case VALIDATE:
                return ApplyMode.VALIDATE;
            case DRY_RUN:
                return ApplyMode.DRY_RUN;
            default:
                throw new IllegalStateException("unknown apply mode: " + apply);
        }
    }

    private static void require(String value, String property) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(property + " is required");
        }
    }
}
