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
package org.acemq.spring.boot.actuate;

import java.util.LinkedHashMap;
import java.util.Map;
import org.acemq.amqp.core.ConsumerGroup;
import org.acemq.spring.boot.AceListenerEndpoint;
import org.acemq.spring.boot.AceListenerRegistry;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * The {@code acemq} actuator endpoint: every {@link org.acemq.spring.boot.AceListener}, its
 * queue, whether it is running, and the counters its consumers keep.
 *
 * <ul>
 *   <li>{@code GET /actuator/acemq} -- every listener, by id.</li>
 *   <li>{@code GET /actuator/acemq/{id}} -- one listener.</li>
 *   <li>{@code POST /actuator/acemq/{id}} with {@code {"state": "stopped"}} or
 *       {@code {"state": "running"}} -- stop or start it. Stopping drains: it waits up to
 *       {@code acemq.listener.shutdown-timeout} for the messages in flight, so the request
 *       can take that long. Starting checks that the queue exists first.</li>
 * </ul>
 *
 * <p>Write access is what Spring Boot grants any endpoint unless told otherwise;
 * {@code management.endpoint.acemq.access=read-only} keeps the reads and refuses the writes.
 * The endpoint is not exposed over HTTP until {@code management.endpoints.web.exposure.include}
 * names it.
 */
@Endpoint(id = "acemq")
public class AceMqEndpoint {

    private final AceListenerRegistry registry;

    public AceMqEndpoint(AceListenerRegistry registry) {
        this.registry = registry;
    }

    /** @return every listener, by id */
    @ReadOperation
    public ListenersDescriptor listeners() {
        Map<String, ListenerDescriptor> listeners = new LinkedHashMap<>();
        for (AceListenerEndpoint endpoint : registry.endpoints()) {
            listeners.put(endpoint.id(), describe(endpoint));
        }
        return new ListenersDescriptor(listeners);
    }

    /**
     * @param id the listener id
     * @return the listener, or {@code null}, which the endpoint answers with 404
     */
    @ReadOperation
    public ListenerDescriptor listener(@Selector String id) {
        for (AceListenerEndpoint endpoint : registry.endpoints()) {
            if (endpoint.id().equals(id)) {
                return describe(endpoint);
            }
        }
        return null;
    }

    /**
     * Starts or stops a listener.
     *
     * @param id the listener id
     * @param state {@code running} or {@code stopped}
     * @return the listener as it is now, or {@code null} (404) when there is no such listener
     */
    @WriteOperation
    public ListenerDescriptor setState(@Selector String id, String state) {
        if (listener(id) == null) {
            return null;
        }
        if ("running".equalsIgnoreCase(state)) {
            try {
                registry.start(id);
            } catch (IllegalStateException missingQueue) {
                // A 400 naming the queue, not a 500: the request was fine, the broker is not
                // ready for it, and the caller is the one who can fix that.
                throw new InvalidEndpointRequestException(missingQueue.getMessage(), missingQueue.getMessage());
            }
        } else if ("stopped".equalsIgnoreCase(state)) {
            registry.stop(id);
        } else {
            String message = "state must be 'running' or 'stopped', was '" + state + "'";
            throw new InvalidEndpointRequestException(message, message);
        }
        return listener(id);
    }

    private ListenerDescriptor describe(AceListenerEndpoint endpoint) {
        ConsumerGroup group = registry.get(endpoint.id()).orElse(null);
        return new ListenerDescriptor(
                endpoint.queue(),
                endpoint.beanName(),
                group == null ? "stopped" : "running",
                endpoint.isAutoStartup(),
                group == null ? endpoint.concurrency() : group.size(),
                group == null ? endpoint.prefetch() : group.prefetch(),
                group == null ? null : new Counters(
                        group.inFlight(), group.acknowledged(), group.rejected(), group.retried()));
    }

    /**
     * Every listener.
     *
     * @param listeners by id
     */
    public record ListenersDescriptor(Map<String, ListenerDescriptor> listeners) {}

    /**
     * One listener.
     *
     * @param queue the queue it consumes
     * @param bean the bean that declares it
     * @param state {@code running} or {@code stopped}
     * @param autoStartup whether it starts with the application
     * @param concurrency consumers, as running or as configured when stopped
     * @param prefetch per consumer, as running or as configured when stopped
     * @param counters what its consumers have done since it last started; {@code null} when
     *     it is stopped
     */
    public record ListenerDescriptor(
            String queue,
            String bean,
            String state,
            boolean autoStartup,
            int concurrency,
            int prefetch,
            Counters counters) {}

    /**
     * A running listener's counters, from its consumer group.
     *
     * @param inFlight being handled now
     * @param acknowledged handled and acknowledged
     * @param rejected deliveries rejected
     * @param retried deliveries retried
     */
    public record Counters(long inFlight, long acknowledged, long rejected, long retried) {}
}
