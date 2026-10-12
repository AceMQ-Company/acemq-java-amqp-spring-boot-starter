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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.transport.QueueType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** acemq.listener.missing-queue: a listener on a queue nobody created, caught at startup. */
class MissingQueueTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private ApplicationContextRunner runner(Class<?>... configuration) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
                .withUserConfiguration(configuration)
                .withPropertyValues("acemq.url=memory://missing-" + BROKERS.incrementAndGet());
    }

    @Test
    void failsTheStartupNamingTheListenerTheBeanAndTheQueue() {
        runner(Listeners.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("@AceListener invoices")
                    .hasMessageContaining("bean 'missingQueueTest.Listeners'")
                    .hasMessageContaining("method onInvoice")
                    .hasMessageContaining("queue 'invoices.new'")
                    .hasMessageContaining("acemq.listener.missing-queue=warn");
        });
    }

    @Test
    void reportsEveryMissingQueueAtOnce() {
        runner(Listeners.class, MoreListeners.class).run(context -> assertThat(context.getStartupFailure())
                .rootCause()
                .hasMessageContaining("'invoices.new'")
                .hasMessageContaining("'refunds.new'"));
    }

    @Test
    void aQueueDeclaredInTheTopologyIsNotAskedAbout() {
        runner(Listeners.class)
                .withPropertyValues(
                        "acemq.topology.queues[0].name=invoices.new",
                        "acemq.topology.queues[0].type=classic")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AceListenerRegistry.class).running()).containsKey("invoices");
                });
    }

    @Test
    void aQueueDeclaredByTheApplicationBeforeListenersStartIsFound() {
        runner(Listeners.class, DeclaresItsOwnQueue.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AceListenerRegistry.class).running()).containsKey("invoices");
        });
    }

    @Test
    void aDryRunCreatesNothingSoTheDeclaredQueueIsStillChecked() {
        runner(Listeners.class)
                .withPropertyValues(
                        "acemq.topology.apply=dry-run",
                        "acemq.topology.queues[0].name=invoices.new",
                        "acemq.topology.queues[0].type=classic")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void warnStartsTheApplicationAndLeavesTheListenerStopped() {
        runner(Listeners.class)
                .withPropertyValues("acemq.listener.missing-queue=warn")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AceListenerRegistry registry = context.getBean(AceListenerRegistry.class);
                    assertThat(registry.running()).isEmpty();

                    // Starting it by hand is still refused while the queue is missing...
                    assertThatThrownBy(() -> registry.start("invoices"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("'invoices.new'");

                    // ...and works once it exists.
                    context.getBean(AceMq.class).declareQueue("invoices.new", QueueType.CLASSIC, Map.of());
                    registry.start("invoices");
                    assertThat(registry.running()).containsKey("invoices");
                });
    }

    @Test
    void aListenerThatDoesNotStartWithTheApplicationIsNotChecked() {
        runner(ManualListener.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void stopAndStartByIdRoundTrip() {
        runner(Listeners.class)
                .withPropertyValues(
                        "acemq.topology.queues[0].name=invoices.new",
                        "acemq.topology.queues[0].type=classic")
                .run(context -> {
                    AceListenerRegistry registry = context.getBean(AceListenerRegistry.class);

                    assertThat(registry.stop("invoices")).isTrue();
                    assertThat(registry.running()).isEmpty();
                    assertThat(registry.stop("invoices")).isTrue();

                    registry.start("invoices");
                    assertThat(registry.running()).containsKey("invoices");
                    assertThatThrownBy(() -> registry.stop("nope")).isInstanceOf(IllegalArgumentException.class);
                });
    }

    record Invoice(String id) {}

    @Configuration(proxyBeanMethods = false)
    static class Listeners {

        @AceListener(queue = "invoices.new", id = "invoices")
        void onInvoice(Invoice invoice) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class MoreListeners {

        @AceListener(queue = "refunds.new", id = "refunds")
        void onRefund(Invoice invoice) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class ManualListener {

        @AceListener(queue = "invoices.later", id = "later", autoStartup = "false")
        void onInvoice(Invoice invoice) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaresItsOwnQueue {

        @Bean
        InitializingBean declareInvoices(AceMq mq) {
            return () -> mq.declareQueue("invoices.new", QueueType.CLASSIC, Map.of());
        }
    }
}
