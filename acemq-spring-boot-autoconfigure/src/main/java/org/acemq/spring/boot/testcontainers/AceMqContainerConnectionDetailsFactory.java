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
package org.acemq.spring.boot.testcontainers;

import java.util.Map;
import org.acemq.spring.boot.AceMqConnectionDetails;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;
import org.testcontainers.containers.Container;

/**
 * {@link AceMqConnectionDetails} for a RabbitMQ container annotated with
 * {@code @ServiceConnection}, so a test points the application at the container without
 * a {@code @DynamicPropertySource}.
 *
 * <p>Typed on {@link Container} and matched by the connection name {@code rabbitmq} -- the
 * image name, or {@code @ServiceConnection("rabbitmq")} -- rather than on
 * {@code RabbitMQContainer}. That class is in a different package in Testcontainers 1 and 2,
 * which Boot 3 and Boot 4 use respectively; a plain {@code GenericContainer} running the
 * image works too. Credentials are read from the container's {@code RABBITMQ_DEFAULT_USER}
 * and {@code RABBITMQ_DEFAULT_PASS}, which is where {@code RabbitMQContainer} puts its admin
 * user, and default to the image's {@code guest}.
 */
class AceMqContainerConnectionDetailsFactory
        extends ContainerConnectionDetailsFactory<Container<?>, AceMqConnectionDetails> {

    private static final int AMQP_PORT = 5672;

    AceMqContainerConnectionDetailsFactory() {
        super("rabbitmq");
    }

    @Override
    protected AceMqConnectionDetails getContainerConnectionDetails(ContainerConnectionSource<Container<?>> source) {
        return new Details(source);
    }

    static final class Details extends ContainerConnectionDetails<Container<?>> implements AceMqConnectionDetails {

        Details(ContainerConnectionSource<Container<?>> source) {
            super(source);
        }

        // Read when asked rather than in the constructor: the details exist before the
        // container has started, and only a started container has a mapped port.
        @Override
        public String getUrl() {
            Container<?> container = getContainer();
            return "amqp://" + container.getHost() + ":" + container.getMappedPort(AMQP_PORT);
        }

        @Override
        public String getUsername() {
            return env().getOrDefault("RABBITMQ_DEFAULT_USER", "guest");
        }

        @Override
        public String getPassword() {
            return env().getOrDefault("RABBITMQ_DEFAULT_PASS", "guest");
        }

        @Override
        public String getVirtualHost() {
            return env().getOrDefault("RABBITMQ_DEFAULT_VHOST", "/");
        }

        private Map<String, String> env() {
            return getContainer().getEnvMap();
        }
    }
}
