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

    @Test
    void reportsHealthAgainstARealConnection() {
        runner().run(context -> assertThat(
                        context.getBean(AceMqHealthIndicator.class).health().getDetails())
                .containsEntry("open", true)
                .containsEntry("transport", "rabbitmq"));
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

    record Order(String id, int quantity) {}

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
