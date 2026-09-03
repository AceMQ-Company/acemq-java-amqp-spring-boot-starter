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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.acemq.amqp.api.Codec;
import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.transport.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The wiring, against the in-memory transport.
 *
 * <p>A real {@code AceMq} bean rather than a mock. Half of what an auto-configuration can get
 * wrong -- a bean that is never created, a codec that is not on the classpath, a connection
 * that is not closed -- is invisible to a test that mocks the thing being configured.
 */
class AceMqAutoConfigurationTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
            .withPropertyValues("acemq.url=memory://" + BROKERS.incrementAndGet());

    @Test
    void connects() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AceMq.class);
            assertThat(context.getBean(AceMq.class).isOpen()).isTrue();
            assertThat(context.getBean(AceMq.class).transportName()).isEqualTo("in-memory");
        });
    }

    @Test
    void closesTheConnectionWithTheContext() {
        AceMq[] captured = new AceMq[1];
        runner.run(context -> captured[0] = context.getBean(AceMq.class));

        assertThat(captured[0].isOpen()).isFalse();
    }

    @Test
    void appliesTheProperties() {
        runner.withPropertyValues(
                        "acemq.client-name=orders-service",
                        "acemq.confirm-timeout=4s",
                        "acemq.publisher-confirms=false")
                .run(context -> {
                    ConnectionConfig config = context.getBean(ConnectionConfig.class);
                    assertThat(config.clientName()).isEqualTo("orders-service");
                    assertThat(config.confirmTimeout()).hasSeconds(4);
                    assertThat(config.publisherConfirms()).isFalse();
                });
    }

    @Test
    void namesTheConnectionAfterTheApplication() {
        runner.withPropertyValues("spring.application.name=orders")
                .run(context -> assertThat(context.getBean(ConnectionConfig.class).clientName())
                        .isEqualTo("orders"));
    }

    @Test
    void takesTheCodecFromTheFormat() {
        runner.withPropertyValues("acemq.format=text")
                .run(context -> assertThat(context.getBean(Codec.class).getClass().getSimpleName())
                        .isEqualTo("StringCodec"));
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwn() {
        runner.withUserConfiguration(OwnConnection.class).run(context -> {
            assertThat(context).hasSingleBean(AceMq.class);
            assertThat(context.getBean(AceMq.class)).isSameAs(OwnConnection.INSTANCE);
        });
    }

    @Test
    void configuresNothingWhenDisabled() {
        runner.withPropertyValues("acemq.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(AceMq.class);
            assertThat(context).doesNotHaveBean(AceListenerRegistry.class);
        });
    }

    @Test
    void sendsMetricsToTheApplicationsRegistry() {
        runner.withUserConfiguration(Metrics.class).run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            AceMq mq = context.getBean(AceMq.class);
            mq.declareExchange("orders", "topic");
            mq.declareQueue("orders.new", org.acemq.amqp.transport.QueueType.CLASSIC, java.util.Map.of());
            mq.bind("orders.new", "orders", "order.created");

            mq.publisher("orders", "order.created", String.class).send("{}");

            // Any meter at all is the assertion that matters: it can only exist if the
            // telemetry bean is the Micrometer one and the connection was given it.
            assertThat(registry.getMeters()).isNotEmpty();
        });
    }

    @Test
    void reportsHealth() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AceMqHealthIndicator.class);
            assertThat(context.getBean(AceMqHealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.UP);
            assertThat(context.getBean(AceMqHealthIndicator.class).health().getDetails())
                    .containsEntry("open", true)
                    .containsEntry("blocked", false)
                    .containsKey("transport");
        });
    }

    @Test
    void healthCanBeTurnedOff() {
        runner.withPropertyValues("management.health.acemq.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AceMqHealthIndicator.class));
    }

    @Test
    void declaresTheTopology() {
        runner.withPropertyValues(
                        "acemq.topology.exchanges[0].name=orders",
                        "acemq.topology.exchanges[0].type=topic",
                        // Classic, because the in-memory transport does not claim quorum
                        // queues and refuses one rather than pretending. The same
                        // declaration against RabbitMQ would be quorum by default.
                        "acemq.topology.queues[0].name=orders.new",
                        "acemq.topology.queues[0].type=classic",
                        "acemq.topology.bindings[0].queue=orders.new",
                        "acemq.topology.bindings[0].exchange=orders",
                        "acemq.topology.bindings[0].routing-key=order.created")
                .run(context -> {
                    AceMq mq = context.getBean(AceMq.class);
                    mq.publisher("orders", "order.created", String.class).send("{}");
                    assertThat(mq.messageCount("orders.new")).isEqualTo(1);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnConnection {

        static final AceMq INSTANCE = AceMq.connect("memory://own");

        @Bean
        AceMq aceMq() {
            return INSTANCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Metrics {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
