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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.acemq.amqp.api.AceFatalException;
import org.acemq.amqp.core.AceMq;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code acemq.topology.queues[].dead-letter}, end to end over the in-memory transport.
 *
 * <p>The declaration and the ladder are two separate routes to the same queue — the ladder
 * publishes a give-up to {@code .dlq} itself, and the broker sends a rejection there because the
 * source queue carries {@code x-dead-letter-exchange}. Both are asserted here, because a topology
 * that only works when the ladder is on is the one that loses messages on the listener nobody
 * configured retries for.
 */
class DeadLetterFromPropertiesTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private ApplicationContextRunner runner(Class<?> listener, String... extraProperties) {
        String broker = "memory://dead-letters-" + BROKERS.incrementAndGet();
        String[] base = {
            "acemq.url=" + broker,
            // Classic, because the in-memory transport refuses a quorum queue rather than
            // pretending. The dead-letter wiring is identical either way.
            "acemq.topology.queues[0].name=payments.new",
            "acemq.topology.queues[0].type=classic",
            "acemq.topology.queues[0].dead-letter=true",
        };
        String[] all = new String[base.length + extraProperties.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extraProperties, 0, all, base.length, extraProperties.length);

        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
                .withUserConfiguration(listener)
                .withPropertyValues(all);
    }

    /** The four objects the one property declares, as the broker sees them. */
    @Test
    void theDeclarationCreatesTheDeadLetterQueues() {
        runner(NoListener.class).run(context -> {
            AceMq mq = context.getBean(AceMq.class);

            assertThat(mq.messageCount("payments.new")).isZero();
            assertThat(mq.messageCount("payments.new.dlq")).isZero();
            assertThat(mq.messageCount("payments.new.parked")).isZero();
        });
    }

    /**
     * The argument that makes the broker's own route work, asserted on the declaration rather than
     * by observing a rejection.
     *
     * <p>The observation is not possible here: the in-memory transport claims
     * {@code DEAD_LETTER_NATIVE} for queue-level expiry, which is what the retry ladder is built
     * from, and does not route a rejected message to a dead-letter exchange. So the broker-side
     * route is covered by {@code AceMqStarterIT} against a real broker, and what this asserts is
     * that the argument reaches the topology at all — which is the half that can be got wrong
     * here.
     */
    @Test
    void theSourceQueueCarriesTheDeadLetterArguments() {
        runner(NoListener.class).run(context -> {
            org.acemq.amqp.api.Topology topology =
                    AceMqTopologies.from(context.getBean(AceMqProperties.class).getTopology());

            assertThat(topology.queues())
                    .filteredOn(queue -> queue.name().equals("payments.new"))
                    .singleElement()
                    .satisfies(queue -> assertThat(queue.arguments())
                            .containsEntry(
                                    org.acemq.amqp.api.Topology.DEAD_LETTER_EXCHANGE_ARGUMENT,
                                    org.acemq.amqp.api.Topology.DEAD_LETTER_EXCHANGE)
                            .containsEntry(
                                    org.acemq.amqp.api.Topology.DEAD_LETTER_ROUTING_KEY_ARGUMENT,
                                    "payments.new.dlq"));
        });
    }

    /** The ladder exhausting its attempts, which is the route the in-memory transport does run. */
    @Test
    void theLadderGivesUpIntoTheDeadLetterQueue() {
        runner(AlwaysFailing.class,
                        "acemq.listener.retry.enabled=true",
                        "acemq.listener.retry.max-attempts=3",
                        "acemq.listener.retry.initial-delay=50ms",
                        "acemq.listener.retry.jitter=0")
                .run(context -> {
                    AceMq mq = context.getBean(AceMq.class);

                    mq.publisher("", "payments.new", Payment.class).send(new Payment("p-2", 7.50));

                    waitUntil(() -> mq.messageCount("payments.new.dlq") == 1);
                    assertThat(context.getBean(AlwaysFailing.class).attempts()).isEqualTo(3);
                });
    }

    /** A fatal failure skips the ladder rather than occupying it. */
    @Test
    void aFatalFailureIsDeadLetteredOnTheFirstAttempt() {
        runner(AlwaysFatal.class,
                        "acemq.listener.retry.enabled=true",
                        "acemq.listener.retry.max-attempts=5",
                        "acemq.listener.retry.initial-delay=50ms",
                        "acemq.listener.retry.jitter=0")
                .run(context -> {
                    AceMq mq = context.getBean(AceMq.class);

                    mq.publisher("", "payments.new", Payment.class).send(new Payment("p-3", -1.00));

                    waitUntil(() -> mq.messageCount("payments.new.dlq") == 1);
                    assertThat(context.getBean(AlwaysFatal.class).attempts()).isEqualTo(1);
                });
    }

    private static void waitUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for the dead letter to arrive");
            }
            Thread.sleep(25);
        }
    }

    public record Payment(String id, double amount) {}

    @Configuration
    static class NoListener {}

    @Configuration
    static class AlwaysFailing {

        private final AtomicInteger attempts = new AtomicInteger();

        @AceListener(queue = "payments.new", id = "payments")
        void onPayment(Payment payment) {
            attempts.incrementAndGet();
            throw new IllegalStateException("the payment gateway is unreachable");
        }

        int attempts() {
            return attempts.get();
        }
    }

    @Configuration
    static class AlwaysFatal {

        private final AtomicInteger attempts = new AtomicInteger();

        @AceListener(queue = "payments.new", id = "payments")
        void onPayment(Payment payment) {
            attempts.incrementAndGet();
            throw new AceFatalException("a payment cannot be negative: " + payment.amount());
        }

        int attempts() {
            return attempts.get();
        }
    }
}
