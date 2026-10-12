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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.acemq.amqp.core.AceMq;
import org.acemq.spring.boot.AceListener;
import org.acemq.spring.boot.AceMqConnectionDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A {@code RabbitMQContainer} annotated {@code @ServiceConnection} is all a test needs: no
 * {@code acemq.url}, no {@code @DynamicPropertySource}. The credentials are not the image's
 * {@code guest}, so the test also proves they travel with the address.
 *
 * <p>In a package of its own so the {@code @SpringBootApplication} next door, which scans,
 * does not pick this application's beans up.
 */
@SpringBootTest(
        classes = ServiceConnectionIT.App.class,
        properties = {
            "acemq.topology.exchanges[0].name=orders",
            "acemq.topology.exchanges[0].type=topic",
            "acemq.topology.queues[0].name=orders.service-connection",
            "acemq.topology.bindings[0].queue=orders.service-connection",
            "acemq.topology.bindings[0].exchange=orders",
            "acemq.topology.bindings[0].routing-key=order.created"
        })
@Testcontainers
class ServiceConnectionIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4-management")
            .withAdminUser("acemq")
            .withAdminPassword("service-connection");

    @Autowired AceMqConnectionDetails details;
    @Autowired AceMq mq;
    @Autowired Orders orders;

    @Test
    void theContainerSuppliesTheAddressAndTheCredentials() {
        assertThat(details.getUrl()).isEqualTo("amqp://" + BROKER.getHost() + ":" + BROKER.getAmqpPort());
        assertThat(details.getUsername()).isEqualTo("acemq");
        assertThat(details.getPassword()).isEqualTo("service-connection");
        assertThat(mq.transportName()).isEqualTo("rabbitmq");
    }

    @Test
    void aMessageGoesRoundTheContainer() throws Exception {
        mq.publisher("orders", "order.created", Order.class).send(new Order("SC-1"));

        assertThat(orders.received.await(10, TimeUnit.SECONDS)).isTrue();
    }

    record Order(String id) {}

    static class Orders {

        final CountDownLatch received = new CountDownLatch(1);

        @AceListener(queue = "orders.service-connection")
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
