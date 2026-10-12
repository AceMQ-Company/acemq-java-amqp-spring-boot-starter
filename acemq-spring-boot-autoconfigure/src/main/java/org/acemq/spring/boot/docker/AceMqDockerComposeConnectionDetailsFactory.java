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
package org.acemq.spring.boot.docker;

import java.util.Map;
import org.acemq.spring.boot.AceMqConnectionDetails;
import org.springframework.boot.docker.compose.core.RunningService;
import org.springframework.boot.docker.compose.service.connection.DockerComposeConnectionDetailsFactory;
import org.springframework.boot.docker.compose.service.connection.DockerComposeConnectionSource;

/**
 * {@link AceMqConnectionDetails} for a {@code rabbitmq} service in a Docker Compose file, so
 * an application with {@code spring-boot-docker-compose} on its classpath connects to the
 * broker Boot started without any {@code acemq.url}.
 *
 * <p>A service matches when its image is {@code rabbitmq} (any tag), or when it carries the
 * label {@code org.springframework.boot.service-connection: rabbitmq}. Credentials and the
 * virtual host come from {@code RABBITMQ_DEFAULT_USER}, {@code RABBITMQ_DEFAULT_PASS} and
 * {@code RABBITMQ_DEFAULT_VHOST}, with the image's own defaults when they are not set.
 *
 * <p>Compiled against Spring Boot 3.5 and loaded unchanged on Boot 4: the Docker Compose
 * service-connection API kept its packages and signatures across the two lines. Registered in
 * {@code META-INF/spring.factories}; on a classpath without Docker Compose support it is never
 * loaded, because only that support reads the entry.
 */
class AceMqDockerComposeConnectionDetailsFactory
        extends DockerComposeConnectionDetailsFactory<AceMqConnectionDetails> {

    private static final int AMQP_PORT = 5672;

    AceMqDockerComposeConnectionDetailsFactory() {
        super("rabbitmq");
    }

    @Override
    protected AceMqConnectionDetails getDockerComposeConnectionDetails(DockerComposeConnectionSource source) {
        return new Details(source.getRunningService());
    }

    static final class Details extends DockerComposeConnectionDetails implements AceMqConnectionDetails {

        private final String url;
        private final String username;
        private final String password;
        private final String virtualHost;

        Details(RunningService service) {
            super(service);
            Map<String, String> env = service.env();
            this.url = "amqp://" + service.host() + ":" + service.ports().get(AMQP_PORT);
            this.username = env.getOrDefault("RABBITMQ_DEFAULT_USER", "guest");
            this.password = env.getOrDefault("RABBITMQ_DEFAULT_PASS", "guest");
            this.virtualHost = env.getOrDefault("RABBITMQ_DEFAULT_VHOST", "/");
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getVirtualHost() {
            return virtualHost;
        }
    }
}
