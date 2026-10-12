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
import org.acemq.amqp.api.Topology;
import org.acemq.amqp.api.TopologyPlan;
import org.acemq.amqp.core.AceMq;
import org.acemq.spring.boot.AceMqProperties.Topology.Queue.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** @AceListener(declare = @AceQueue): a listener that declares the queue it consumes. */
class ListenerDeclaresQueueTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private ApplicationContextRunner runner(Class<?>... configuration) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
                .withUserConfiguration(configuration)
                .withPropertyValues("acemq.url=memory://declare-" + BROKERS.incrementAndGet());
    }

    /** What the planner says about one classic queue with these arguments. */
    private static TopologyPlan.Kind classic(AceMq mq, String queue, Map<String, Object> arguments) {
        return mq.topology().plan(Topology.define().classicQueue(queue, arguments).build())
                .actions().get(0).kind();
    }

    @Test
    void declaresTheQueueAndStarts() {
        runner(Declares.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AceListenerRegistry.class).running()).containsKey("audit");
            assertThat(classic(context.getBean(AceMq.class), "audit.log", Map.of("x-message-ttl", 60000L)))
                    .isEqualTo(TopologyPlan.Kind.PRESENT);
        });
    }

    @Test
    void deadLetterDeclaresTheDeadLetterQueuesToo() {
        runner(DeclaresWithDeadLetter.class).run(context -> {
            assertThat(context).hasNotFailed();
            AceMq mq = context.getBean(AceMq.class);
            assertThat(classic(mq, "payments.dlq", Map.of())).isEqualTo(TopologyPlan.Kind.PRESENT);
            assertThat(classic(mq, "payments.parked", Map.of())).isEqualTo(TopologyPlan.Kind.PRESENT);
            assertThat(classic(mq, "payments", Map.of(
                            Topology.DEAD_LETTER_EXCHANGE_ARGUMENT, Topology.DEAD_LETTER_EXCHANGE,
                            Topology.DEAD_LETTER_ROUTING_KEY_ARGUMENT, "payments.dlq")))
                    .isEqualTo(TopologyPlan.Kind.PRESENT);
        });
    }

    @Test
    void theDefaultIsADurableQuorumQueue() {
        runner(DeclaresByDefault.class).run(context -> {
            AceListenerEndpoint endpoint = context.getBean(AceListenerRegistry.class).endpoints().iterator().next();
            Topology.QueueSpec queue = endpoint.declaration().orElseThrow().queues().get(0);
            assertThat(queue.name()).isEqualTo("orders.new");
            assertThat(queue.quorum()).isTrue();
            assertThat(queue.durable()).isTrue();
            assertThat(queue.arguments()).isEmpty();
        });
    }

    @Test
    void aDryRunDeclaresNothingSoTheQueueIsStillChecked() {
        runner(Declares.class).withPropertyValues("acemq.topology.apply=dry-run").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("'audit.log', which does not exist");
        });
    }

    @Test
    void validateChecksTheQueueButDoesNotCreateIt() {
        runner(Declares.class).withPropertyValues("acemq.topology.apply=validate").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("validate mode")
                    .hasMessageContaining("audit.log");
        });
    }

    @Test
    void aListenerStartedByHandDeclaresWhenItStarts() {
        runner(DeclaresLater.class).run(context -> {
            assertThat(context).hasNotFailed();
            AceMq mq = context.getBean(AceMq.class);
            assertThat(classic(mq, "later", Map.of())).isEqualTo(TopologyPlan.Kind.CREATE);

            context.getBean(AceListenerRegistry.class).start("later");
            assertThat(classic(mq, "later", Map.of())).isEqualTo(TopologyPlan.Kind.PRESENT);
        });
    }

    @Test
    void theSameDeclarationInTheTopologyIsAccepted() {
        runner(Declares.class)
                .withPropertyValues(
                        "acemq.topology.queues[0].name=audit.log",
                        "acemq.topology.queues[0].type=classic",
                        "acemq.topology.queues[0].arguments[x-message-ttl]=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AceListenerRegistry.class).running()).containsKey("audit");
                });
    }

    @Test
    void aDifferentDeclarationInTheTopologyFailsTheStartup() {
        runner(Declares.class)
                .withPropertyValues(
                        "acemq.topology.queues[0].name=audit.log",
                        "acemq.topology.queues[0].type=classic",
                        "acemq.topology.queues[0].dead-letter=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("@AceListener audit")
                            .hasMessageContaining("declares queue 'audit.log' differently from acemq.topology.queues")
                            .hasMessageContaining("x-dead-letter-exchange");
                });
    }

    @Test
    void aDifferentTypeInTheTopologyFailsTheStartup() {
        runner(DeclaresByDefault.class)
                .withPropertyValues(
                        "acemq.topology.queues[0].name=orders.new",
                        "acemq.topology.queues[0].type=classic")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("type quorum here, classic there"));
    }

    @Test
    void twoListenersDeclaringOneQueueDifferentlyFailTheStartup() {
        runner(Declares.class, DeclaresAuditDifferently.class).run(context -> assertThat(context.getStartupFailure())
                .rootCause()
                .hasMessageContaining("declares queue 'audit.log' differently from @AceListener"));
    }

    @Test
    void argumentsOnAQuorumQueueAreRefused() {
        runner(QuorumWithArguments.class).run(context -> assertThat(context.getStartupFailure())
                .rootCause()
                .hasMessageContaining("not accepted for a quorum queue"));
    }

    @Test
    void anArgumentThatIsNotNameEqualsValueIsRefused() {
        runner(BadArgument.class).run(context -> assertThat(context.getStartupFailure())
                .rootCause()
                .hasMessageContaining("which is not name=value"));
    }

    @Test
    void theRegistryRefusesAConflictWithoutSpring() {
        AceMqProperties.Topology properties = new AceMqProperties.Topology();
        AceMqProperties.Topology.Queue queue = new AceMqProperties.Topology.Queue();
        queue.setName("q");
        properties.getQueues().add(queue);
        AceListenerRegistry registry = new AceListenerRegistry(
                null, new AceMqProperties.Listener(), AceMqTopologies.from(properties),
                AceMqTopologies.mode(properties.getApply()));
        Topology classic = Topology.define().classicQueue("q", Map.of()).build();
        AceListenerEndpoint endpoint = new AceListenerEndpoint("id", "q", 1, 1, true, String.class, false,
                this, "bean", ListenerDeclaresQueueTest.class.getDeclaredMethods()[0], classic);
        assertThatThrownBy(() -> registry.register(endpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type classic here, quorum there");
    }

    @Configuration(proxyBeanMethods = false)
    static class Declares {

        @AceListener(queue = "audit.log", id = "audit",
                declare = @AceQueue(type = Kind.CLASSIC, arguments = "x-message-ttl=${audit.ttl:60000}"))
        void onAudit(String line) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaresAuditDifferently {

        @AceListener(queue = "audit.log", id = "audit2", declare = @AceQueue(type = Kind.CLASSIC))
        void onAudit(String line) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaresWithDeadLetter {

        @AceListener(queue = "payments", declare = @AceQueue(type = Kind.CLASSIC, deadLetter = true))
        void onPayment(String payment) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaresByDefault {

        @AceListener(queue = "orders.new", autoStartup = "false", declare = @AceQueue)
        void onOrder(String order) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaresLater {

        @AceListener(queue = "later", id = "later", autoStartup = "false", declare = @AceQueue(type = Kind.CLASSIC))
        void onLater(String later) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class QuorumWithArguments {

        @AceListener(queue = "q", declare = @AceQueue(arguments = "x-max-priority=10"))
        void on(String s) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class BadArgument {

        @AceListener(queue = "q", declare = @AceQueue(type = Kind.CLASSIC, arguments = "x-message-ttl"))
        void on(String s) {}
    }
}
