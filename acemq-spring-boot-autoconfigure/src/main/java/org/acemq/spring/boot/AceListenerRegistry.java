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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.acemq.amqp.api.ApplyMode;
import org.acemq.amqp.api.RetryPolicy;
import org.acemq.amqp.api.Topology;
import org.acemq.amqp.api.TopologyPlan;
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
    private final ApplyMode mode;
    private final Map<String, Topology.QueueSpec> topologyQueues = new HashMap<>();
    private final List<AceListenerEndpoint> endpoints = new ArrayList<>();
    private final Map<String, ConsumerGroup> running = new ConcurrentHashMap<>();

    private volatile boolean started;

    public AceListenerRegistry(AceMq aceMq, AceMqProperties.Listener defaults) {
        this(aceMq, defaults, Topology.define().build(), ApplyMode.CREATE_ONLY);
    }

    /**
     * @param aceMq the connection
     * @param defaults the {@code acemq.listener.*} configuration
     * @param topology the {@code acemq.topology} the application declares, applied before
     *     listeners start: its queues are not asked about, unless {@code mode} is a dry run, and
     *     a listener that declares one of them has to declare it the same way
     * @param mode {@code acemq.topology.apply}, which {@link AceListener#declare()} follows too
     */
    public AceListenerRegistry(
            AceMq aceMq, AceMqProperties.Listener defaults, Topology topology, ApplyMode mode) {
        this.aceMq = aceMq;
        this.defaults = defaults;
        this.mode = mode;
        for (Topology.QueueSpec queue : topology.queues()) {
            topologyQueues.put(queue.name(), queue);
        }
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
        Topology.QueueSpec declared = declaredQueue(endpoint);
        if (declared != null) {
            checkAgreement(endpoint, declared, topologyQueues.get(endpoint.queue()), "acemq.topology.queues");
            for (AceListenerEndpoint existing : endpoints) {
                if (existing.queue().equals(endpoint.queue())) {
                    checkAgreement(endpoint, declared, declaredQueue(existing), "@AceListener " + existing.id());
                }
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

    /**
     * Starts every listener with auto-startup on, after declaring the queues they declare
     * and checking that the rest exist.
     *
     * <p>The check is here, at lifecycle start, rather than when the method is found: by now
     * the declared topology has been applied and every bean that declares a queue in its own
     * initialisation has run, so a queue still missing is missing for real. Every missing
     * queue is reported at once, so a deployment with three typos fails once, not three times.
     */
    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        List<AceListenerEndpoint> toStart = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (AceListenerEndpoint endpoint : endpoints) {
            if (!endpoint.isAutoStartup()) {
                log.debug("listener {} not started: auto-startup is off", endpoint.id());
            } else if (queueMissing(endpoint)) {
                missing.add(describeMissing(endpoint));
            } else {
                toStart.add(endpoint);
            }
        }
        if (!missing.isEmpty()) {
            if (defaults.getMissingQueue() == AceMqProperties.Listener.MissingQueue.FAIL) {
                throw new IllegalStateException(String.join("\n", missing)
                        + "\nDeclare the queue under acemq.topology.queues or with @AceListener(declare = @AceQueue), "
                        + "create it on the broker, or set "
                        + "acemq.listener.missing-queue=warn to start without these listeners.");
            }
            for (String line : missing) {
                log.warn("{}; the listener stays stopped until it is started by id", line);
            }
        }
        started = true;
        for (AceListenerEndpoint endpoint : toStart) {
            start(endpoint);
        }
    }

    /**
     * Starts one listener: one registered with auto-startup off, one left stopped because its
     * queue was missing, or one stopped with {@link #stop(String)}.
     *
     * @param id the listener id
     * @return the group that is now running
     * @throws IllegalArgumentException when no listener has that id
     * @throws IllegalStateException when the listener's queue does not exist
     */
    public synchronized ConsumerGroup start(String id) {
        ConsumerGroup already = running.get(id);
        if (already != null) {
            return already;
        }
        AceListenerEndpoint endpoint = endpoint(id);
        if (queueMissing(endpoint)) {
            throw new IllegalStateException(describeMissing(endpoint));
        }
        return start(endpoint);
    }

    /**
     * Stops one listener: it stops taking messages, waits up to
     * {@code acemq.listener.shutdown-timeout} for the ones in flight, and closes. The
     * listener stays registered and {@link #start(String)} starts it again.
     *
     * @param id the listener id
     * @return whether everything in flight finished before the timeout; messages that did not
     *     are redelivered
     * @throws IllegalArgumentException when no listener has that id
     */
    public synchronized boolean stop(String id) {
        endpoint(id);
        ConsumerGroup group = running.remove(id);
        if (group == null) {
            return true;
        }
        boolean drained = group.drain(defaults.getShutdownTimeout());
        group.close();
        log.info("listener {} stopped", id);
        return drained;
    }

    private AceListenerEndpoint endpoint(String id) {
        for (AceListenerEndpoint endpoint : endpoints) {
            if (endpoint.id().equals(id)) {
                return endpoint;
            }
        }
        throw new IllegalArgumentException("no listener with id '" + id + "'");
    }

    /** The queue a listener declares for itself, or null when it declares none. */
    private static Topology.QueueSpec declaredQueue(AceListenerEndpoint endpoint) {
        return endpoint.declaration()
                .flatMap(topology -> topology.queues().stream()
                        .filter(queue -> queue.name().equals(endpoint.queue()))
                        .findFirst())
                .orElse(null);
    }

    /**
     * Refuses a queue declared two different ways. The broker would refuse the second
     * declaration with PRECONDITION_FAILED anyway, but only once it is reached, and with a
     * message that names neither declaration.
     */
    private static void checkAgreement(
            AceListenerEndpoint endpoint, Topology.QueueSpec declared, Topology.QueueSpec other, String where) {
        if (other == null) {
            return;
        }
        List<String> differences = new ArrayList<>();
        if (declared.quorum() != other.quorum()) {
            differences.add("type " + type(declared) + " here, " + type(other) + " there");
        }
        Map<String, String> ours = comparable(declared.arguments());
        Map<String, String> theirs = comparable(other.arguments());
        if (!ours.equals(theirs)) {
            differences.add("arguments " + ours + " here, " + theirs + " there");
        }
        if (!differences.isEmpty()) {
            throw new IllegalStateException("@AceListener " + endpoint.id() + " (bean '" + endpoint.beanName()
                    + "', method " + endpoint.method().getName() + ") declares queue '" + endpoint.queue()
                    + "' differently from " + where + ": " + String.join("; ", differences)
                    + ". Declare it the same way in both, or in one place only.");
        }
    }

    private static String type(Topology.QueueSpec queue) {
        return queue.quorum() ? "quorum" : "classic";
    }

    /**
     * Arguments by their written value: 60000 from YAML is an Integer, from an annotation a
     * Long, and from a properties file a String, and all three are the same declaration.
     */
    private static Map<String, String> comparable(Map<String, Object> arguments) {
        Map<String, String> comparable = new java.util.TreeMap<>();
        arguments.forEach((name, value) -> comparable.put(name, Objects.toString(value)));
        return comparable;
    }

    /**
     * Declares what the listener asked for, following {@code acemq.topology.apply}. A queue the
     * application's topology already declares is left alone: it was applied before any listener
     * started, and declared the same way, which {@link #register} made sure of.
     *
     * @return whether the queue is now accounted for, so the missing-queue check can skip it
     */
    private boolean declare(AceListenerEndpoint endpoint) {
        if (topologyQueues.containsKey(endpoint.queue())) {
            return mode != ApplyMode.DRY_RUN;
        }
        if (endpoint.declaration().isEmpty() || mode == ApplyMode.DRY_RUN) {
            return false;
        }
        TopologyPlan plan = aceMq.topology().apply(endpoint.declaration().get(), mode);
        if (plan.hasChanges()) {
            log.info("listener {} declared ({}):\n{}", endpoint.id(), mode, plan.render());
        }
        return true;
    }

    /**
     * Asks the broker whether the queue exists, through the library's topology planner,
     * which asks passively and creates nothing. A queue it would have to create is missing.
     */
    private boolean queueMissing(AceListenerEndpoint endpoint) {
        if (declare(endpoint)) {
            return false;
        }
        TopologyPlan plan = aceMq.topology().plan(Topology.define().queue(endpoint.queue()).build());
        return plan.actions().stream().anyMatch(action -> action.kind() == TopologyPlan.Kind.CREATE);
    }

    private static String describeMissing(AceListenerEndpoint endpoint) {
        return "@AceListener " + endpoint.id() + " (bean '" + endpoint.beanName() + "', method "
                + endpoint.method().getName() + ") listens on queue '" + endpoint.queue()
                + "', which does not exist";
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
    public synchronized void stop() {
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
