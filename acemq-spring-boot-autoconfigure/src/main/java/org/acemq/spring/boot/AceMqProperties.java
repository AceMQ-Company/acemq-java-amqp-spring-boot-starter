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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code acemq.*} in an application's configuration.
 *
 * <p>The shape follows the library's own vocabulary rather than Spring AMQP's, because a
 * property that is called something different from the method it configures is a property
 * somebody has to look up twice. {@code acemq.publisher-confirms} sets
 * {@code ConnectionConfig.Builder.withoutPublisherConfirms()} when false, and the two are
 * named the same thing on purpose.
 *
 * <p>Nothing here has a default that costs money or safety. Confirms are on, TLS
 * verification is on when TLS is on, and the topology is not applied unless something is
 * declared. The one opinionated default is the URL: {@code amqp://localhost:5672}, so a
 * developer with a broker in Docker needs no configuration at all.
 */
@ConfigurationProperties(prefix = "acemq")
public class AceMqProperties {

    /**
     * Whether to configure AceMQ at all. Off, no connection is made and no listener runs,
     * which is what a test slice or a batch instance of the same application wants.
     */
    private boolean enabled = true;

    /** Broker URL. The scheme selects the transport, so amqps:// is how TLS is asked for. */
    private String url = "amqp://localhost:5672";

    /** Username. Left unset, the credentials in the URL are used, if any. */
    private String username;

    /** Password. */
    private String password;

    /** Virtual host. Unset means the transport's default. */
    private String virtualHost;

    /**
     * Connection name shown in the broker's management UI. Defaults to
     * {@code spring.application.name} when that is set, because "unnamed connection" on a
     * page of forty is the same as no name at all.
     */
    private String clientName;

    /** How long to wait for the TCP and protocol handshake. */
    private Duration connectionTimeout = Duration.ofSeconds(10);

    /** How long a publish waits for its confirm before failing. */
    private Duration confirmTimeout = Duration.ofSeconds(30);

    /** How long publishing blocks when the broker has applied back pressure. */
    private Duration blockedTimeout = Duration.ofSeconds(30);

    /**
     * Publisher confirms. On, and turning them off means a successful send no longer means
     * the broker has the message.
     */
    private boolean publisherConfirms = true;

    /** Upper bound on unconfirmed publishes in flight. */
    private int maxOutstandingPublishes = 10_000;

    /** Default serialisation format: json, xml, yaml, toml, avro, protobuf, text, bytes. */
    private String format = "json";

    private final Tls tls = new Tls();
    private final Topology topology = new Topology();
    private final Listener listener = new Listener();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public Duration getBlockedTimeout() {
        return blockedTimeout;
    }

    public void setBlockedTimeout(Duration blockedTimeout) {
        this.blockedTimeout = blockedTimeout;
    }

    public boolean isPublisherConfirms() {
        return publisherConfirms;
    }

    public void setPublisherConfirms(boolean publisherConfirms) {
        this.publisherConfirms = publisherConfirms;
    }

    public int getMaxOutstandingPublishes() {
        return maxOutstandingPublishes;
    }

    public void setMaxOutstandingPublishes(int maxOutstandingPublishes) {
        this.maxOutstandingPublishes = maxOutstandingPublishes;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Tls getTls() {
        return tls;
    }

    public Topology getTopology() {
        return topology;
    }

    public Listener getListener() {
        return listener;
    }

    /**
     * TLS.
     *
     * <p>There is no {@code verify-hostname: false} property, and there will not be one. The
     * library expresses that as {@link org.acemq.amqp.security.Security#insecure()}, whose name
     * survives a code review; a boolean in a properties file does not, because the line that
     * disabled verification for one afternoon in 2024 reads exactly like the lines around it.
     */
    public static class Tls {

        /**
         * REQUIRED verifies the certificate chain and the hostname. INSECURE verifies
         * neither and is for a development broker with a self-signed certificate. DISABLED
         * is plain AMQP.
         */
        private Mode mode = Mode.DISABLED;

        /** Directory holding {@code keystore.p12} and {@code truststore.p12}. */
        private Path keystore;

        /** Password for both stores. */
        private String keystorePassword;

        /**
         * Accept certificates carrying AceMQ's development marker. A production broker's
         * certificate does not carry it, so this cannot silently weaken a real deployment --
         * it only allows the ones that announce themselves as untrustworthy.
         */
        private boolean allowDevelopmentCertificates;

        public enum Mode {
            /** Plain AMQP. */
            DISABLED,
            /** TLS, chain and hostname verified. */
            REQUIRED,
            /** TLS, nothing verified. Development only. */
            INSECURE
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public Path getKeystore() {
            return keystore;
        }

        public void setKeystore(Path keystore) {
            this.keystore = keystore;
        }

        public String getKeystorePassword() {
            return keystorePassword;
        }

        public void setKeystorePassword(String keystorePassword) {
            this.keystorePassword = keystorePassword;
        }

        public boolean isAllowDevelopmentCertificates() {
            return allowDevelopmentCertificates;
        }

        public void setAllowDevelopmentCertificates(boolean allowDevelopmentCertificates) {
            this.allowDevelopmentCertificates = allowDevelopmentCertificates;
        }
    }

    /**
     * Exchanges, queues and bindings the application expects to exist.
     *
     * <p>Declared here, they are applied once at startup. Nothing is deleted and nothing is
     * modified: {@code CREATE_ONLY} creates what is missing and reports what differs, which
     * is the only apply mode that is safe to run on every deployment of every instance.
     */
    public static class Topology {

        /**
         * CREATE_ONLY creates what is missing. VALIDATE fails startup when something is
         * missing rather than creating it, which is what a production environment usually
         * wants once provisioning is somebody else's job. DRY_RUN logs the plan and changes
         * nothing.
         */
        private Apply apply = Apply.CREATE_ONLY;

        /** Fail startup when the broker's topology differs from the declared one. */
        private boolean failOnDrift;

        private List<Exchange> exchanges = new ArrayList<>();
        private List<Queue> queues = new ArrayList<>();
        private List<Binding> bindings = new ArrayList<>();

        public enum Apply {
            CREATE_ONLY,
            VALIDATE,
            DRY_RUN
        }

        public Apply getApply() {
            return apply;
        }

        public void setApply(Apply apply) {
            this.apply = apply;
        }

        public boolean isFailOnDrift() {
            return failOnDrift;
        }

        public void setFailOnDrift(boolean failOnDrift) {
            this.failOnDrift = failOnDrift;
        }

        public List<Exchange> getExchanges() {
            return exchanges;
        }

        public void setExchanges(List<Exchange> exchanges) {
            this.exchanges = exchanges;
        }

        public List<Queue> getQueues() {
            return queues;
        }

        public void setQueues(List<Queue> queues) {
            this.queues = queues;
        }

        public List<Binding> getBindings() {
            return bindings;
        }

        public void setBindings(List<Binding> bindings) {
            this.bindings = bindings;
        }

        /** Nothing declared, nothing to apply. */
        public boolean isEmpty() {
            return exchanges.isEmpty() && queues.isEmpty() && bindings.isEmpty();
        }

        public static class Exchange {

            /** Exchange name. */
            private String name;

            /** direct, topic, fanout or headers. */
            private String type = "topic";

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }
        }

        public static class Queue {

            /** Queue name. */
            private String name;

            /**
             * quorum or classic. Quorum is the library's default and the right answer for
             * anything whose loss would be noticed.
             */
            private Kind type = Kind.QUORUM;

            /** Broker-specific arguments, x-message-ttl and the rest. */
            private Map<String, Object> arguments = new LinkedHashMap<>();

            public enum Kind {
                QUORUM,
                CLASSIC
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public Kind getType() {
                return type;
            }

            public void setType(Kind type) {
                this.type = type;
            }

            public Map<String, Object> getArguments() {
                return arguments;
            }

            public void setArguments(Map<String, Object> arguments) {
                this.arguments = arguments;
            }
        }

        public static class Binding {

            /** Queue to bind. */
            private String queue;

            /** Exchange to bind it to. */
            private String exchange;

            /** Routing key, or the pattern for a topic exchange. */
            private String routingKey = "";

            public String getQueue() {
                return queue;
            }

            public void setQueue(String queue) {
                this.queue = queue;
            }

            public String getExchange() {
                return exchange;
            }

            public void setExchange(String exchange) {
                this.exchange = exchange;
            }

            public String getRoutingKey() {
                return routingKey;
            }

            public void setRoutingKey(String routingKey) {
                this.routingKey = routingKey;
            }
        }
    }

    /** Defaults for every {@link AceListener}, each of which can override them. */
    public static class Listener {

        /**
         * Unacknowledged messages allowed per consumer. 100 is a starting point, not an
         * answer: the right number is a function of handler time and message size, and the
         * only way to find it is to measure.
         */
        private int prefetch = 100;

        /** Consumers per listener. More than one means messages are no longer ordered. */
        private int concurrency = 1;

        /** Start listeners when the application context starts. */
        private boolean autoStartup = true;

        /**
         * How long shutdown waits for in-flight handlers to finish before closing the
         * connection underneath them. A message still being handled when the connection goes
         * is redelivered, so this is the difference between a clean redeploy and a burst of
         * duplicates.
         */
        private Duration shutdownTimeout = Duration.ofSeconds(30);

        /** Requeue a message whose handler threw, rather than dead-lettering it. */
        private boolean requeueOnFailure;

        private final Retry retry = new Retry();

        public int getPrefetch() {
            return prefetch;
        }

        public void setPrefetch(int prefetch) {
            this.prefetch = prefetch;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public boolean isAutoStartup() {
            return autoStartup;
        }

        public void setAutoStartup(boolean autoStartup) {
            this.autoStartup = autoStartup;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }

        public boolean isRequeueOnFailure() {
            return requeueOnFailure;
        }

        public void setRequeueOnFailure(boolean requeueOnFailure) {
            this.requeueOnFailure = requeueOnFailure;
        }

        public Retry getRetry() {
            return retry;
        }

        /**
         * The retry ladder applied to a handler that throws a retryable exception.
         *
         * <p>Off by default. A retry that is on by default is a retry nobody chose, and the
         * library's ladder republishes with a delay rather than sleeping in the handler --
         * which is the whole point, and worth knowing you have asked for.
         */
        public static class Retry {

            /** Enable the ladder. */
            private boolean enabled;

            /** Total attempts, the first one included. */
            private int maxAttempts = 3;

            /** Delay before the second attempt. */
            private Duration initialDelay = Duration.ofSeconds(1);

            /** Ceiling for the delay however many attempts have passed. */
            private Duration maxDelay = Duration.ofMinutes(1);

            /** Each delay is the previous one times this. */
            private double multiplier = 2.0;

            /** Random spread applied to each delay, 0 to 1, to break up retry storms. */
            private double jitter;

            /** Give up on a message older than this, whatever attempt it is on. */
            private Duration giveUpAfter;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getMaxAttempts() {
                return maxAttempts;
            }

            public void setMaxAttempts(int maxAttempts) {
                this.maxAttempts = maxAttempts;
            }

            public Duration getInitialDelay() {
                return initialDelay;
            }

            public void setInitialDelay(Duration initialDelay) {
                this.initialDelay = initialDelay;
            }

            public Duration getMaxDelay() {
                return maxDelay;
            }

            public void setMaxDelay(Duration maxDelay) {
                this.maxDelay = maxDelay;
            }

            public double getMultiplier() {
                return multiplier;
            }

            public void setMultiplier(double multiplier) {
                this.multiplier = multiplier;
            }

            public double getJitter() {
                return jitter;
            }

            public void setJitter(double jitter) {
                this.jitter = jitter;
            }

            public Duration getGiveUpAfter() {
                return giveUpAfter;
            }

            public void setGiveUpAfter(Duration giveUpAfter) {
                this.giveUpAfter = giveUpAfter;
            }
        }
    }
}
