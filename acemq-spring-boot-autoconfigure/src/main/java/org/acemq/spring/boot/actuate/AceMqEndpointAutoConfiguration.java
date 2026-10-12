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
package org.acemq.spring.boot.actuate;

import org.acemq.spring.boot.AceListenerRegistry;
import org.acemq.spring.boot.AceMqAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link AceMqEndpoint} when the actuator is on the classpath and the endpoint
 * is exposed.
 *
 * <p>Here, in the module shared by both Spring Boot lines, rather than in the per-line health
 * modules: unlike the health contributor API, the endpoint annotations and
 * {@code @ConditionalOnAvailableEndpoint} kept their packages in Boot 4.
 */
@AutoConfiguration(after = AceMqAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, ConditionalOnAvailableEndpoint.class})
public class AceMqEndpointAutoConfiguration {

    /**
     * @param registry the listener registry
     * @return the endpoint
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AceListenerRegistry.class)
    @ConditionalOnAvailableEndpoint
    public AceMqEndpoint aceMqEndpoint(AceListenerRegistry registry) {
        return new AceMqEndpoint(registry);
    }
}
