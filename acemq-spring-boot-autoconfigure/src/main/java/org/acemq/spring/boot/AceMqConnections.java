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

import org.acemq.amqp.security.Security;
import org.acemq.amqp.transport.ConnectionConfig;

/**
 * Turns {@link AceMqProperties} into a {@link ConnectionConfig}.
 *
 * <p>Separate from the auto-configuration, and static, so the mapping can be tested without
 * a broker. Every other part of this starter needs a connection to prove anything; this part
 * is pure, and it is the part where a misspelled property silently becomes a default.
 */
public final class AceMqConnections {

    private AceMqConnections() {}

    /**
     * Builds the connection configuration.
     *
     * @param properties the {@code acemq.*} configuration
     * @return the configuration the library will connect with
     */
    public static ConnectionConfig from(AceMqProperties properties) {
        ConnectionConfig.Builder builder = ConnectionConfig.url(properties.getUrl())
                .clientName(properties.getClientName() == null ? "spring-boot" : properties.getClientName())
                .connectionTimeout(properties.getConnectionTimeout())
                .confirmTimeout(properties.getConfirmTimeout())
                .blockedTimeout(properties.getBlockedTimeout())
                .maxOutstandingPublishes(properties.getMaxOutstandingPublishes())
                .security(security(properties.getTls()));

        // Only when set. Passing nulls through would overwrite credentials that
        // arrived in the URL, so an application that puts them in amqp://user:pass@host
        // would find itself connecting as a guest.
        if (properties.getUsername() != null || properties.getPassword() != null) {
            builder.credentials(properties.getUsername(), properties.getPassword());
        }
        if (properties.getVirtualHost() != null) {
            builder.virtualHost(properties.getVirtualHost());
        }
        if (!properties.isPublisherConfirms()) {
            builder.withoutPublisherConfirms();
        }
        return builder.build();
    }

    private static Security security(AceMqProperties.Tls tls) {
        switch (tls.getMode()) {
            case DISABLED:
                return Security.disabled();
            case INSECURE:
                return Security.insecure();
            case REQUIRED:
                Security security = tls.getKeystore() == null
                        ? Security.required()
                        : Security.fromKeystore(tls.getKeystore());
                if (tls.getKeystorePassword() != null) {
                    security = security.keystorePassword(tls.getKeystorePassword());
                }
                if (tls.isAllowDevelopmentCertificates()) {
                    security = security.allowDevelopmentCertificates();
                }
                return security;
            default:
                throw new IllegalStateException("unknown TLS mode: " + tls.getMode());
        }
    }
}
