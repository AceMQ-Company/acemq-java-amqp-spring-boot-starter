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
package org.acemq.spring.boot.serviceconnection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.acemq.amqp.core.AceMq;
import org.acemq.spring.boot.AceListener;
import org.acemq.spring.boot.AceMqConnectionDetails;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * A {@code rabbitmq} service in a Compose file, started by Spring Boot's Docker Compose
 * support, is what the application connects to -- without an {@code acemq.url}.
 *
 * <p>A real {@code SpringApplication} rather than a {@code @SpringBootTest}: Docker Compose
 * support runs from an application listener and is off in tests by default, so the test turns
 * it back on and starts the application the way {@code main} would. The Compose file publishes
 * the broker's port on an ephemeral host port and names its own project, so nothing it starts
 * collides with a broker already running, and {@code stop.command=down} removes it afterwards.
 */
class DockerComposeIT {

    @Test
    void theComposeServiceSuppliesTheAddressAndTheCredentials() throws Exception {
        String compose = new File("src/test/resources/compose.yaml").getAbsolutePath();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.docker.compose.file=" + compose,
                        "spring.docker.compose.skip.in-tests=false",
                        "spring.docker.compose.lifecycle-management=start-and-stop",
                        "spring.docker.compose.stop.command=down",
                        "spring.docker.compose.readiness.timeout=3m",
                        "acemq.topology.exchanges[0].name=orders",
                        "acemq.topology.exchanges[0].type=topic",
                        "acemq.topology.queues[0].name=orders.compose",
                        "acemq.topology.bindings[0].queue=orders.compose",
                        "acemq.topology.bindings[0].exchange=orders",
                        "acemq.topology.bindings[0].routing-key=order.created")
                .run()) {

            AceMqConnectionDetails details = context.getBean(AceMqConnectionDetails.class);
            assertThat(details.getUrl()).startsWith("amqp://").doesNotEndWith(":5672");
            assertThat(details.getUsername()).isEqualTo("acemq");
            assertThat(details.getPassword()).isEqualTo("compose");

            AceMq mq = context.getBean(AceMq.class);
            assertThat(mq.transportName()).isEqualTo("rabbitmq");
            mq.publisher("orders", "order.created", Order.class).send(new Order("DC-1"));
            assertThat(context.getBean(Orders.class).received.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    record Order(String id) {}

    static class Orders {

        final CountDownLatch received = new CountDownLatch(1);

        @AceListener(queue = "orders.compose")
        void onOrder(Order order) {
            received.countDown();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        Orders orders() {
            return new Orders();
        }
    }
}
