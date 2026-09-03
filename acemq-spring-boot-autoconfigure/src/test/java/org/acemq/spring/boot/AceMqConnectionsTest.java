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
import org.acemq.amqp.security.Security;
import org.acemq.amqp.transport.ConnectionConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The properties-to-configuration mapping, which is the part a broker cannot verify. */
class AceMqConnectionsTest {

    @Test
    void carriesEveryTimeout() {
        AceMqProperties properties = new AceMqProperties();
        properties.setUrl("amqp://broker:5672");
        properties.setConnectionTimeout(Duration.ofSeconds(3));
        properties.setConfirmTimeout(Duration.ofSeconds(7));
        properties.setBlockedTimeout(Duration.ofSeconds(11));
        properties.setMaxOutstandingPublishes(42);
        properties.setClientName("orders");

        ConnectionConfig config = AceMqConnections.from(properties);

        assertThat(config.url()).isEqualTo("amqp://broker:5672");
        assertThat(config.connectionTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.confirmTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(config.blockedTimeout()).isEqualTo(Duration.ofSeconds(11));
        assertThat(config.maxOutstandingPublishes()).isEqualTo(42);
        assertThat(config.clientName()).isEqualTo("orders");
        assertThat(config.publisherConfirms()).isTrue();
    }

    @Test
    void confirmsAreOnUnlessTurnedOff() {
        AceMqProperties properties = new AceMqProperties();
        properties.setPublisherConfirms(false);

        assertThat(AceMqConnections.from(properties).publisherConfirms()).isFalse();
    }

    @Nested
    class Credentials {

        @Test
        void areSetWhenConfigured() {
            AceMqProperties properties = new AceMqProperties();
            properties.setUsername("orders");
            properties.setPassword("secret");

            ConnectionConfig config = AceMqConnections.from(properties);

            assertThat(config.username()).isEqualTo("orders");
            assertThat(config.password()).isEqualTo("secret");
        }

        /**
         * The regression this exists for: credentials in the URL, none in the properties, and
         * a mapper that passes two nulls through anyway connects as a guest. The failure is
         * an authentication error against a broker whose configuration is correct.
         */
        @Test
        void inTheUrlSurviveUnsetProperties() {
            AceMqProperties properties = new AceMqProperties();
            properties.setUrl("amqp://orders:secret@broker:5672");

            ConnectionConfig config = AceMqConnections.from(properties);

            assertThat(config.username()).isNull();
            assertThat(config.password()).isNull();
            assertThat(config.url()).isEqualTo("amqp://orders:secret@broker:5672");
        }
    }

    @Nested
    class Tls {

        @Test
        void isDisabledByDefault() {
            assertThat(AceMqConnections.from(new AceMqProperties()).security().mode())
                    .isEqualTo(Security.Mode.DISABLED);
        }

        @Test
        void requiredVerifiesTheHostname() {
            AceMqProperties properties = new AceMqProperties();
            properties.getTls().setMode(AceMqProperties.Tls.Mode.REQUIRED);

            ConnectionConfig config = AceMqConnections.from(properties);

            assertThat(config.security().mode()).isEqualTo(Security.Mode.REQUIRED);
            assertThat(config.security().verifiesHostname()).isTrue();
        }

        @Test
        void insecureDoesNot() {
            AceMqProperties properties = new AceMqProperties();
            properties.getTls().setMode(AceMqProperties.Tls.Mode.INSECURE);

            ConnectionConfig config = AceMqConnections.from(properties);

            assertThat(config.security().mode()).isEqualTo(Security.Mode.INSECURE);
            assertThat(config.security().verifiesHostname()).isFalse();
        }
    }
}
