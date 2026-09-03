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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.acemq.amqp.api.RetryPolicy;
import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.ConsumerGroup;
import org.acemq.amqp.core.ConsumerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts and stops the {@link AceListener} methods, and hands out the running consumers.
 *
 * <p>A {@link SmartLifecycle} rather than an {@code @PostConstruct}, so consumers start after
 * the rest of the context is ready and stop before it is torn down. Starting a consumer while
 * the beans it calls are still being created is how a message arrives at a half-built
 * service, and it happens on exactly the deployments where the queue already has a backlog.
 *
 * <p>Every listener is a {@link ConsumerGroup}, even at concurrency one. One type means one
 * set of counters to read and one shutdown path, and the group of one costs nothing.
 */
public class AceListenerRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AceListenerRegistry.class);

    private final AceMq aceMq;
    private final AceMqProperties.Listener defaults;
    private final List<AceListenerEndpoint> endpoints = new ArrayList<>();
    private final Map<String, ConsumerGroup> running = new ConcurrentHashMap<>();

    private volatile boolean started;

    public AceListenerRegistry(AceMq aceMq, AceMqProperties.Listener defaults) {
        this.aceMq = aceMq;
        this.defaults = defaults;
    }

    /**
     * Registers an endpoint. Called during bean post-processing, before start.
     *
     * @param endpoint the resolved listener
     */
    public void register(AceListenerEndpoint endpoint) {
        for (AceListenerEndpoint existing : endpoints) {
            if (existing.id().equals(endpoint.id())) {
                throw new IllegalStateException("two listeners share the id '" + endpoint.id()
                        + "'; give one of them an explicit id");
            }
        }
        endpoints.add(endpoint);
    }

    /** Every registered listener, started or not. */
    public Collection<AceListenerEndpoint> endpoints() {
        return List.copyOf(endpoints);
    }

    /**
     * The running consumer group for a listener id, for scaling, pausing or reading counters.
     *
     * @param id the listener id
     * @return the group, or empty when that listener is not running
     */
    public Optional<ConsumerGroup> get(String id) {
        return Optional.ofNullable(running.get(id));
    }

    /** The running groups by listener id. */
    public Map<String, ConsumerGroup> running() {
        return Map.copyOf(running);
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        for (AceListenerEndpoint endpoint : endpoints) {
            if (!endpoint.isAutoStartup()) {
                log.debug("listener {} not started: auto-startup is off", endpoint.id());
                continue;
            }
            start(endpoint);
        }
    }

    /**
     * Starts one listener that was registered with auto-startup off.
     *
     * @param id the listener id
     * @return the group that is now running
     */
    public ConsumerGroup start(String id) {
        ConsumerGroup already = running.get(id);
        if (already != null) {
            return already;
        }
        for (AceListenerEndpoint endpoint : endpoints) {
            if (endpoint.id().equals(id)) {
                return start(endpoint);
            }
        }
        throw new IllegalArgumentException("no listener with id '" + id + "'");
    }

    private ConsumerGroup start(AceListenerEndpoint endpoint) {
        ConsumerGroup group = aceMq
                .consumeGroup(endpoint.queue(), endpoint.payloadType(), endpoint.handler())
                .concurrency(endpoint.concurrency())
                .prefetch(endpoint.prefetch())
                .options(options(endpoint))
                .start();
        running.put(endpoint.id(), group);
        log.info("listener {} consuming {} with {} consumer(s), prefetch {}",
                endpoint.id(), endpoint.queue(), endpoint.concurrency(), endpoint.prefetch());
        return group;
    }

    private ConsumerOptions options(AceListenerEndpoint endpoint) {
        ConsumerOptions options = ConsumerOptions.prefetch(endpoint.prefetch());
        if (defaults.isRequeueOnFailure()) {
            options = options.requeueOnFailure();
        }
        AceMqProperties.Listener.Retry retry = defaults.getRetry();
        if (retry.isEnabled()) {
            RetryPolicy policy = RetryPolicy.exponential(
                    retry.getMaxAttempts(),
                    retry.getInitialDelay(),
                    retry.getMultiplier(),
                    retry.getMaxDelay());
            if (retry.getJitter() > 0) {
                policy = policy.withJitter(retry.getJitter());
            }
            if (retry.getGiveUpAfter() != null) {
                policy = policy.giveUpAfter(retry.getGiveUpAfter());
            }
            options = options.withRetry(policy);
        }
        return options;
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        // Drain first, close second, and in that order for every listener before any of
        // them closes. Closing one group while another is still draining would leave the
        // second one's in-flight messages to be redelivered, which is the duplicate burst
        // a rolling deployment produces and nobody attributes to shutdown.
        Map<String, ConsumerGroup> groups = new LinkedHashMap<>(running);
        for (Map.Entry<String, ConsumerGroup> entry : groups.entrySet()) {
            boolean drained = entry.getValue().drain(defaults.getShutdownTimeout());
            if (!drained) {
                log.warn("listener {} still had {} message(s) in flight after {}; "
                                + "they will be redelivered",
                        entry.getKey(), entry.getValue().inFlight(), defaults.getShutdownTimeout());
            }
        }
        for (Map.Entry<String, ConsumerGroup> entry : groups.entrySet()) {
            entry.getValue().close();
            running.remove(entry.getKey());
        }
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * The default phase, which is {@code Integer.MAX_VALUE} and stops first.
     *
     * <p>That is what is wanted: consumers stop taking new messages and drain while the rest
     * of the application is still up and can finish handling them. The web server's own
     * lifecycle sits one below and so is still serving during that drain, which is the same
     * ordering Spring AMQP's containers use.
     *
     * <p>The connection itself is closed later still: {@code AceMq} is a bean whose destroy
     * method is {@code close}, and destruction runs after every lifecycle has stopped.
     */
    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }
}
