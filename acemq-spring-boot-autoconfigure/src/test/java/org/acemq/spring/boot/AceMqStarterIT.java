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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.acemq.amqp.api.Topology;
import org.acemq.amqp.api.TopologyPlan;
import org.acemq.amqp.core.AceMq;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same wiring against a real broker.
 *
 * <p>What only this can prove: that the declared quorum queue is one a broker will actually
 * create -- the in-memory transport refuses quorum queues rather than pretending, so every
 * unit test here declares classic ones and none of them exercises the default -- and that
 * VALIDATE refuses to create rather than quietly creating. Everything else is covered
 * without Docker.
 */
@Testcontainers
class AceMqStarterIT {

    @Container
    static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4-management");

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
                .withPropertyValues(
                        "acemq.url=" + BROKER.getAmqpUrl(),
                        "acemq.username=" + BROKER.getAdminUsername(),
                        "acemq.password=" + BROKER.getAdminPassword(),
                        "acemq.client-name=starter-it");
    }

    private ApplicationContextRunner withTopology(String queue) {
        return runner().withPropertyValues(
                "acemq.topology.exchanges[0].name=orders",
                "acemq.topology.exchanges[0].type=topic",
                // No type: the default is quorum, which is the declaration this test
                // exists to put in front of a real broker.
                "acemq.topology.queues[0].name=" + queue,
                "acemq.topology.bindings[0].queue=" + queue,
                "acemq.topology.bindings[0].exchange=orders",
                "acemq.topology.bindings[0].routing-key=order.created");
    }

    @Test
    void declaresAQuorumQueueAndRoutesToIt() {
        withTopology("orders.declared").run(context -> {
            assertThat(context).hasNotFailed();
            AceMq mq = context.getBean(AceMq.class);
            assertThat(mq.transportName()).isEqualTo("rabbitmq");

            mq.publisher("orders", "order.created", Order.class).send(new Order("A-1", 2));

            assertThat(mq.messageCount("orders.declared")).isEqualTo(1);
        });
    }

    @Test
    void listensOnADeclaredQueue() {
        withTopology("orders.listen").withUserConfiguration(Listener.class).run(context -> {
            assertThat(context).hasNotFailed();
            Listener listener = context.getBean(Listener.class);

            context.getBean(AceMq.class)
                    .publisher("orders", "order.created", Order.class)
                    .send(new Order("A-2", 1));

            assertThat(listener.received.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.order.get().id()).isEqualTo("A-2");
            assertThat(listener.order.get().quantity()).isEqualTo(1);
        });
    }

    /**
     * VALIDATE refuses to create. An application configured this way against a broker that
     * has not been provisioned fails at startup with the plan, which is the point: the
     * alternative is an application that starts, consumes nothing, and looks healthy.
     */
    @Test
    void validateFailsWhenTheQueueIsMissing() {
        runner().withPropertyValues(
                        "acemq.topology.apply=validate",
                        "acemq.topology.queues[0].name=orders.never-declared")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * A listener on a queue nobody created fails the startup with the listener and the queue
     * named, rather than starting and failing at the broker's first consume.
     */
    @Test
    void aListenerOnAMissingQueueFailsTheStartup() {
        runner().withUserConfiguration(MissingQueueListener.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("@AceListener missing")
                    .hasMessageContaining("queue 'orders.nobody-made-this'");
        });
    }

    /**
     * A queue that already exists on the broker -- here a quorum queue another application
     * declared -- is found by the passive check, and the listener starts.
     */
    @Test
    void aListenerOnAQueueThatAlreadyExistsStarts() {
        withTopology("orders.preexisting").run(context -> assertThat(context).hasNotFailed());

        runner().withUserConfiguration(PreexistingQueueListener.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AceListenerRegistry.class).running()).containsKey("preexisting");
        });
    }

    /**
     * A listener that declares its own queue gets a durable quorum queue by default, with
     * its dead-letter queues when it asks for them, and consumes from it -- the declaration
     * the in-memory transport cannot make.
     */
    @Test
    void aListenerDeclaresItsOwnQuorumQueueAndConsumes() {
        runner().withUserConfiguration(SelfDeclaringListener.class).run(context -> {
            assertThat(context).hasNotFailed();
            AceMq mq = context.getBean(AceMq.class);
            // Every queue the declaration asked for is on the broker, as asked: a quorum
            // queue dead-lettering to orders.self.dlq, and the two classic queues behind it.
            TopologyPlan plan = mq.topology().plan(Topology.define().queueWithDeadLetter("orders.self").build());
            assertThat(plan.actions())
                    .filteredOn(action -> action.description().startsWith("queue "))
                    .hasSize(3)
                    .allMatch(action -> action.kind() == TopologyPlan.Kind.PRESENT);

            mq.publisher("", "orders.self", Order.class).send(new Order("A-3", 4));

            SelfDeclaringListener listener = context.getBean(SelfDeclaringListener.class);
            assertThat(listener.received.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mq.messageCount("orders.self.dlq")).isZero();
            assertThat(mq.messageCount("orders.self.parked")).isZero();
        });
    }

    /**
     * A listener whose declaration disagrees with the queue on the broker fails the startup
     * naming the difference, before the broker's PRECONDITION_FAILED would have closed the
     * channel.
     */
    @Test
    void aListenerDeclaringAnExistingQueueDifferentlyFailsTheStartup() {
        runner().withPropertyValues(
                        "acemq.topology.queues[0].name=orders.classic-already",
                        "acemq.topology.queues[0].type=classic")
                .run(context -> assertThat(context).hasNotFailed());

        runner().withUserConfiguration(DisagreeingListener.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("orders.classic-already")
                    .hasMessageContaining("do not match");
        });
    }

    record Order(String id, int quantity) {}

    @Configuration(proxyBeanMethods = false)
    static class SelfDeclaringListener {

        final CountDownLatch received = new CountDownLatch(1);

        @AceListener(queue = "orders.self", declare = @AceQueue(deadLetter = true))
        void onOrder(Order order) {
            received.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DisagreeingListener {

        @AceListener(queue = "orders.classic-already", declare = @AceQueue)
        void onOrder(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingQueueListener {

        @AceListener(queue = "orders.nobody-made-this", id = "missing")
        void onOrder(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class PreexistingQueueListener {

        @AceListener(queue = "orders.preexisting", id = "preexisting")
        void onOrder(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class Listener {

        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<Order> order = new AtomicReference<>();

        @AceListener(queue = "orders.listen")
        void onOrder(Order order) {
            this.order.set(order);
            received.countDown();
        }
    }
}
