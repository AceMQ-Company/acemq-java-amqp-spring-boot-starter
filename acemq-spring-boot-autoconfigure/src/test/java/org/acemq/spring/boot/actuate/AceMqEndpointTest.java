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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.acemq.amqp.core.AceMq;
import org.acemq.spring.boot.AceListener;
import org.acemq.spring.boot.AceMqAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The acemq actuator endpoint over the in-memory transport. */
class AceMqEndpointTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AceMqAutoConfiguration.class,
                        AceMqEndpointAutoConfiguration.class))
                .withUserConfiguration(Listeners.class)
                .withPropertyValues(
                        "acemq.url=memory://endpoint-" + BROKERS.incrementAndGet(),
                        "acemq.topology.exchanges[0].name=orders",
                        "acemq.topology.exchanges[0].type=topic",
                        "acemq.topology.queues[0].name=orders.new",
                        "acemq.topology.queues[0].type=classic",
                        "acemq.topology.bindings[0].queue=orders.new",
                        "acemq.topology.bindings[0].exchange=orders",
                        "acemq.topology.bindings[0].routing-key=order.created");
    }

    private ApplicationContextRunner exposed() {
        return runner().withPropertyValues("management.endpoints.web.exposure.include=acemq");
    }

    @Test
    void isNotRegisteredUnlessExposed() {
        runner().run(context -> assertThat(context).doesNotHaveBean(AceMqEndpoint.class));
    }

    @Test
    void describesEveryListenerWithItsCounters() {
        exposed().run(context -> {
            AceMqEndpoint endpoint = context.getBean(AceMqEndpoint.class);
            Listeners listeners = context.getBean(Listeners.class);

            context.getBean(AceMq.class).publisher("orders", "order.created", Order.class).send(new Order("A-1"));
            assertThat(listeners.received.await(5, TimeUnit.SECONDS)).isTrue();

            AceMqEndpoint.ListenersDescriptor all = endpoint.listeners();
            assertThat(all.listeners()).containsOnlyKeys("orders", "manual");

            AceMqEndpoint.ListenerDescriptor orders = all.listeners().get("orders");
            assertThat(orders.queue()).isEqualTo("orders.new");
            assertThat(orders.bean()).isEqualTo("aceMqEndpointTest.Listeners");
            assertThat(orders.state()).isEqualTo("running");
            assertThat(orders.concurrency()).isEqualTo(1);
            // The handler returns before the acknowledgement is counted, so wait for it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (endpoint.listener("orders").counters().acknowledged() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(endpoint.listener("orders").counters().acknowledged()).isEqualTo(1);

            AceMqEndpoint.ListenerDescriptor manual = all.listeners().get("manual");
            assertThat(manual.state()).isEqualTo("stopped");
            assertThat(manual.autoStartup()).isFalse();
            assertThat(manual.counters()).isNull();

            assertThat(endpoint.listener("nope")).isNull();
        });
    }

    @Test
    void stopsAndStartsAListener() {
        exposed().run(context -> {
            AceMqEndpoint endpoint = context.getBean(AceMqEndpoint.class);

            assertThat(endpoint.setState("orders", "stopped").state()).isEqualTo("stopped");
            assertThat(endpoint.setState("orders", "running").state()).isEqualTo("running");
            assertThat(endpoint.setState("manual", "running").state()).isEqualTo("running");
            assertThat(endpoint.setState("nope", "running")).isNull();
            assertThatThrownBy(() -> endpoint.setState("orders", "paused"))
                    .isInstanceOf(InvalidEndpointRequestException.class);
        });
    }

    @Test
    void refusesToStartAListenerWhoseQueueIsMissing() {
        exposed().withUserConfiguration(Missing.class)
                .withPropertyValues("acemq.listener.missing-queue=warn")
                .run(context -> {
                    AceMqEndpoint endpoint = context.getBean(AceMqEndpoint.class);
                    assertThat(endpoint.listener("missing").state()).isEqualTo("stopped");
                    assertThatThrownBy(() -> endpoint.setState("missing", "running"))
                            .isInstanceOf(InvalidEndpointRequestException.class)
                            .hasMessageContaining("'nowhere'");
                });
    }

    record Order(String id) {}

    @Configuration(proxyBeanMethods = false)
    static class Listeners {

        final CountDownLatch received = new CountDownLatch(1);

        @AceListener(queue = "orders.new", id = "orders")
        void onOrder(Order order) {
            received.countDown();
        }

        @AceListener(queue = "orders.new", id = "manual", autoStartup = "false")
        void later(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class Missing {

        @AceListener(queue = "nowhere", id = "missing")
        void onOrder(Order order) {}
    }
}
