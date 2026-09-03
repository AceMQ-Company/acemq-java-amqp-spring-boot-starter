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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.acemq.amqp.api.Message;
import org.acemq.amqp.core.AceMq;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Annotated listeners, end to end over the in-memory transport. */
class AceListenerTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private ApplicationContextRunner runner(Class<?> listeners) {
        String broker = "memory://listeners-" + BROKERS.incrementAndGet();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
                .withUserConfiguration(listeners)
                .withPropertyValues(
                        "acemq.url=" + broker,
                        "acemq.topology.exchanges[0].name=orders",
                        "acemq.topology.exchanges[0].type=topic",
                        "acemq.topology.queues[0].name=orders.new",
                        "acemq.topology.queues[0].type=classic",
                        "acemq.topology.bindings[0].queue=orders.new",
                        "acemq.topology.bindings[0].exchange=orders",
                        "acemq.topology.bindings[0].routing-key=order.created");
    }

    @Test
    void receivesTheDecodedPayload() {
        runner(PayloadListener.class).run(context -> {
            PayloadListener listener = context.getBean(PayloadListener.class);

            context.getBean(AceMq.class)
                    .publisher("orders", "order.created", Order.class)
                    .send(new Order("A-1", 3));

            assertThat(listener.received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.order.get().id()).isEqualTo("A-1");
            assertThat(listener.order.get().quantity()).isEqualTo(3);
        });
    }

    @Test
    void receivesTheWholeMessageWhenItAsksFor() {
        runner(MessageListener.class).run(context -> {
            MessageListener listener = context.getBean(MessageListener.class);

            context.getBean(AceMq.class)
                    .publisher("orders", "order.created", Order.class)
                    .send(new Order("A-2", 1));

            assertThat(listener.received.await(5, TimeUnit.SECONDS)).isTrue();
            Message<Order> message = listener.message.get();
            assertThat(message.payload().id()).isEqualTo("A-2");
            assertThat(message.queue()).isEqualTo("orders.new");
            assertThat(message.attempt()).isEqualTo(1);
        });
    }

    @Test
    void takesItsSettingsFromTheAnnotationAndThenTheProperties() {
        runner(PayloadListener.class)
                .withPropertyValues("acemq.listener.prefetch=64", "acemq.listener.concurrency=3")
                .run(context -> {
                    AceListenerRegistry registry = context.getBean(AceListenerRegistry.class);
                    AceListenerEndpoint endpoint = registry.endpoints().iterator().next();

                    assertThat(endpoint.prefetch()).isEqualTo(64);
                    assertThat(endpoint.concurrency()).isEqualTo(3);
                    assertThat(endpoint.payloadType()).isEqualTo(Order.class);
                    assertThat(endpoint.id()).endsWith("#onOrder");
                    assertThat(registry.get(endpoint.id())).isPresent();
                    assertThat(registry.get(endpoint.id()).get().size()).isEqualTo(3);
                });
    }

    @Test
    void annotationAttributesWinOverProperties() {
        runner(ExplicitListener.class)
                .withPropertyValues("acemq.listener.prefetch=64", "acemq.listener.concurrency=3")
                .run(context -> {
                    AceListenerEndpoint endpoint = context.getBean(AceListenerRegistry.class)
                            .endpoints().iterator().next();

                    assertThat(endpoint.prefetch()).isEqualTo(5);
                    assertThat(endpoint.concurrency()).isEqualTo(1);
                    assertThat(endpoint.id()).isEqualTo("orders");
                });
    }

    @Test
    void aListenerWithAutoStartupOffIsRegisteredAndNotRunning() {
        runner(ManualListener.class).run(context -> {
            AceListenerRegistry registry = context.getBean(AceListenerRegistry.class);

            assertThat(registry.endpoints()).hasSize(1);
            assertThat(registry.running()).isEmpty();

            registry.start("manual");
            assertThat(registry.running()).containsKey("manual");
        });
    }

    @Test
    void queueNamesResolvePropertyPlaceholders() {
        runner(PlaceholderListener.class)
                .withPropertyValues("orders.queue=orders.new")
                .run(context -> assertThat(context.getBean(AceListenerRegistry.class)
                                .endpoints().iterator().next().queue())
                        .isEqualTo("orders.new"));
    }

    @Test
    void twoListenersCannotShareAnId() {
        runner(DuplicateIds.class).run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void aMethodWithTwoParametersIsRejected() {
        runner(TwoParameters.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("exactly one parameter");
        });
    }

    @Test
    void aRawMessageIsRejected() {
        runner(RawMessage.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("give it a type parameter");
        });
    }

    /**
     * Shutdown finishes what is in a handler and loses nothing.
     *
     * <p>The claim being tested is narrow on purpose. Stopping does not drain the
     * <em>queue</em> -- a listener stopped with a backlog leaves the backlog -- it drains
     * what is in flight, and every message that was not handled is still on the queue
     * afterwards. Handled plus remaining is the whole batch, and that sum is the assertion
     * worth making: it fails if a message is dropped, and it does not depend on how many of
     * the five a 50ms handler happened to reach.
     */
    @Test
    void stoppingFinishesWhatIsInFlightAndLosesNothing() {
        runner(SlowListener.class).run(context -> {
            AceListenerRegistry registry = context.getBean(AceListenerRegistry.class);
            SlowListener listener = context.getBean(SlowListener.class);
            AceMq mq = context.getBean(AceMq.class);
            var publisher = mq.publisher("orders", "order.created", Order.class);
            for (int i = 0; i < 5; i++) {
                publisher.send(new Order("A-" + i, 1));
            }
            assertThat(listener.first.await(5, TimeUnit.SECONDS)).isTrue();

            registry.stop();

            assertThat(listener.handled.get()).isPositive();
            assertThat(listener.handled.get() + mq.messageCount("orders.new")).isEqualTo(5);
            assertThat(registry.running()).isEmpty();
        });
    }

    record Order(String id, int quantity) {}

    @Configuration(proxyBeanMethods = false)
    static class PayloadListener {

        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<Order> order = new AtomicReference<>();

        @AceListener(queue = "orders.new")
        void onOrder(Order order) {
            this.order.set(order);
            received.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MessageListener {

        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<Message<Order>> message = new AtomicReference<>();

        @AceListener(queue = "orders.new")
        void onOrder(Message<Order> message) {
            this.message.set(message);
            received.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExplicitListener {

        @AceListener(queue = "orders.new", id = "orders", prefetch = 5, concurrency = 1)
        void onOrder(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class ManualListener {

        @AceListener(queue = "orders.new", id = "manual", autoStartup = "false")
        void onOrder(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class PlaceholderListener {

        @AceListener(queue = "${orders.queue}")
        void onOrder(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateIds {

        @AceListener(queue = "orders.new", id = "orders")
        void one(Order order) {}

        @AceListener(queue = "orders.new", id = "orders")
        void two(Order order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoParameters {

        @AceListener(queue = "orders.new")
        void onOrder(Order order, String extra) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class RawMessage {

        @SuppressWarnings("rawtypes")
        @AceListener(queue = "orders.new")
        void onOrder(Message message) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class SlowListener {

        final CountDownLatch first = new CountDownLatch(1);
        final AtomicInteger handled = new AtomicInteger();

        @AceListener(queue = "orders.new", id = "slow", prefetch = 10)
        void onOrder(Order order) throws InterruptedException {
            first.countDown();
            Thread.sleep(50);
            handled.incrementAndGet();
        }
    }
}
