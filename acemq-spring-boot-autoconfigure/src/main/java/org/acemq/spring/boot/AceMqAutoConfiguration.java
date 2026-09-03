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

import org.acemq.amqp.api.Codec;
import org.acemq.amqp.api.Telemetry;
import org.acemq.amqp.core.AceMq;
import org.acemq.amqp.core.Codecs;
import org.acemq.amqp.core.Telemetries;
import org.acemq.amqp.transport.ConnectionConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires AceMQ from {@code acemq.*}.
 *
 * <p>What an application gets: an {@link AceMq} connection, the codec named by
 * {@code acemq.format}, telemetry through Micrometer when a registry is present, the
 * declared topology applied once, and {@link AceListener} methods consuming.
 *
 * <p>The health indicator is not here. It lives in {@code acemq-spring-boot-health-boot3}
 * and {@code acemq-spring-boot-health-boot4}, one per Spring Boot line, because Boot 4
 * moved the health contributor API to a different package in a different artifact. Both are
 * on the classpath when the starter is used, and exactly one of them ever matches. This
 * module itself compiles and runs unchanged on both lines.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}. Defining your own {@code AceMq} bean
 * -- two connections, a transport this starter does not know about, a wrapper -- replaces
 * the one here and keeps the listeners, the topology and the health indicator, because those
 * take the connection as a dependency rather than creating it.
 */
@AutoConfiguration
@ConditionalOnClass(AceMq.class)
@ConditionalOnProperty(prefix = "acemq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AceMqProperties.class)
public class AceMqAutoConfiguration {

    /**
     * The connection configuration, separately from the connection, so an application can
     * see and override it without taking over connecting.
     *
     * @param properties the {@code acemq.*} configuration
     * @param environment used only for {@code spring.application.name}
     * @return the configuration
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionConfig aceMqConnectionConfig(AceMqProperties properties, Environment environment) {
        if (properties.getClientName() == null) {
            // The broker's management UI lists connections by this name. Defaulting it
            // to the application's name costs nothing and is the difference between
            // finding the noisy service in one glance and grepping process lists.
            properties.setClientName(environment.getProperty("spring.application.name", "spring-boot"));
        }
        return AceMqConnections.from(properties);
    }

    /**
     * The default codec.
     *
     * @param properties the {@code acemq.*} configuration
     * @return the codec named by {@code acemq.format}
     */
    @Bean
    @ConditionalOnMissingBean
    public Codec aceMqCodec(AceMqProperties properties) {
        return Codecs.byName(properties.getFormat());
    }

    /**
     * The connection.
     *
     * <p>{@code destroyMethod} closes it: the context owns the connection, and an
     * application that has to remember to close it will forget in exactly the tests that
     * create a context per class.
     *
     * @param config the connection configuration
     * @param telemetry telemetry
     * @param codec the default codec
     * @return the connection
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public AceMq aceMq(ConnectionConfig config, Telemetry telemetry, Codec codec) {
        return AceMq.connect(config, telemetry, codec);
    }

    /**
     * Applies the declared topology. Does nothing when nothing is declared.
     *
     * <p>The bean exists either way rather than hiding behind a condition on the properties:
     * a list under {@code acemq.topology.queues} is bound as indexed keys, which
     * {@code @ConditionalOnProperty} cannot see, so the condition would be wrong in the one
     * direction that matters -- silently skipping a topology somebody declared.
     *
     * @param aceMq the connection
     * @param properties the {@code acemq.*} configuration
     * @return the initializer
     */
    @Bean
    @ConditionalOnMissingBean
    public AceMqTopologyInitializer aceMqTopologyInitializer(AceMq aceMq, AceMqProperties properties) {
        return new AceMqTopologyInitializer(
                aceMq,
                AceMqTopologies.from(properties.getTopology()),
                AceMqTopologies.mode(properties.getTopology().getApply()),
                properties.getTopology().isFailOnDrift());
    }

    /**
     * The listener registry, which starts and stops the annotated methods.
     *
     * @param aceMq the connection
     * @param properties the {@code acemq.*} configuration
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean
    public AceListenerRegistry aceListenerRegistry(AceMq aceMq, AceMqProperties properties) {
        return new AceListenerRegistry(aceMq, properties.getListener());
    }

    /**
     * The post-processor that finds {@link AceListener} methods.
     *
     * <p>Static, which Spring requires of a {@code BeanPostProcessor} factory method: a
     * non-static one would instantiate this configuration class -- and with it the
     * connection -- before post-processing began.
     *
     * @param registry the registry to register endpoints with, resolved lazily
     * @param properties the {@code acemq.*} configuration
     * @return the post-processor
     */
    @Bean
    @ConditionalOnMissingBean
    public static AceListenerAnnotationBeanPostProcessor aceListenerAnnotationBeanPostProcessor(
            ObjectProvider<AceListenerRegistry> registry, AceMqProperties properties) {
        return new AceListenerAnnotationBeanPostProcessor(registry, properties.getListener());
    }

    static String transportOf(String url) {
        int scheme = url.indexOf("://");
        return scheme < 0 ? url : url.substring(0, scheme);
    }

    /**
     * Telemetry through Micrometer, which is how the library's metrics reach whatever the
     * application already exports to.
     *
     * <p>Its own class, conditional on the Micrometer type, because a method signature
     * mentioning {@code MeterRegistry} is resolved when the class is loaded -- long before
     * any condition on the method is evaluated. Splitting it is the only way an optional
     * dependency stays optional.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    public static class Micrometer {

        /**
         * @param registry the application's meter registry, if it has one
         * @param properties the {@code acemq.*} configuration
         * @return telemetry backed by the registry, or the library's auto-detection when
         *     Micrometer is on the classpath but no registry is in the context
         */
        @Bean
        @ConditionalOnMissingBean(Telemetry.class)
        public Telemetry aceMqTelemetry(
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry,
                AceMqProperties properties) {
            io.micrometer.core.instrument.MeterRegistry meterRegistry = registry.getIfAvailable();
            String transport = transportOf(properties.getUrl());
            return meterRegistry == null
                    ? Telemetries.autoDetect(transport)
                    : org.acemq.amqp.core.MicrometerSupport.telemetry(meterRegistry, transport);
        }
    }

    /**
     * Telemetry without Micrometer: whatever the library can detect, which finds
     * OpenTelemetry if it is there and is otherwise a no-op.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    public static class NoMicrometer {

        /**
         * @param properties the {@code acemq.*} configuration
         * @return the detected telemetry
         */
        @Bean
        @ConditionalOnMissingBean(Telemetry.class)
        public Telemetry aceMqTelemetry(AceMqProperties properties) {
            return Telemetries.autoDetect(transportOf(properties.getUrl()));
        }
    }

}
