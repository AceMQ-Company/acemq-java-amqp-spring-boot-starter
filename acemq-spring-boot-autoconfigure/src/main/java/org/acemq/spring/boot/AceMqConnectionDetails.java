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

import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;

/**
 * Where the broker is: the part of {@code acemq.*} that a service connection can supply.
 *
 * <p>By default it is read from {@code acemq.url}, {@code acemq.username},
 * {@code acemq.password} and {@code acemq.virtual-host}. Spring Boot's Docker Compose support
 * and a Testcontainers {@code @ServiceConnection} replace it with the address of the
 * {@code rabbitmq} service or container they started, and an application can define its own
 * bean of this type to take the address from anywhere else. Everything not here -- TLS,
 * timeouts, the codec -- still comes from {@code acemq.*}.
 *
 * <p>Its own type rather than Spring Boot's {@code RabbitConnectionDetails}: that one belongs to
 * Spring AMQP's auto-configuration, lives in a different package on each Boot line, and is
 * bound to {@code spring.rabbitmq.*}, so reusing it would have made {@code acemq.url} lose to
 * a property this starter never documented.
 */
public interface AceMqConnectionDetails extends ConnectionDetails {

    /**
     * The broker URL, such as {@code amqp://localhost:5672}. Credentials and a virtual host
     * may be in it; the getters below override them when they return a value.
     *
     * @return the URL
     */
    String getUrl();

    /** @return the user, or {@code null} to use the URL's */
    default String getUsername() {
        return null;
    }

    /** @return the password, or {@code null} to use the URL's */
    default String getPassword() {
        return null;
    }

    /** @return the virtual host, or {@code null} to use the URL's */
    default String getVirtualHost() {
        return null;
    }
}
