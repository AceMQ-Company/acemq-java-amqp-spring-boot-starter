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
package org.acemq.spring.boot.health.boot4;

import org.acemq.amqp.core.AceMq;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Boot 4 health indicator.
 *
 * <p>The condition is on this class rather than on the bean method, and that placement is
 * the whole mechanism. Boot evaluates {@code @ConditionalOnClass} on an auto-configuration
 * candidate from its ASM metadata, before the class is loaded, so on Boot 3 — where
 * {@code org.springframework.boot.health.contributor} does not exist — this class is
 * filtered out and never loaded, and nothing tries to resolve {@link AceMqHealthIndicator}
 * with its missing supertype.
 *
 * <p>Both this module and its Boot 3 twin can sit on the same classpath, which is what the
 * starter does. Exactly one of them ever matches, because the two packages never coexist.
 *
 * <p>The class sits in a {@code .boot4} package, and the Boot 3 twin in {@code .boot3},
 * because both jars ship in the starter. Two classes with the same fully-qualified name on
 * one classpath are not two classes: the first jar wins, its condition is evaluated, and on
 * the other line it evaluates to false -- so the application would silently get no health
 * indicator at all. That is exactly the failure this split was written to prevent, and it
 * was caught by running the starter's own classpath on both lines.
 */
@AutoConfiguration(afterName = "org.acemq.spring.boot.AceMqAutoConfiguration")
@ConditionalOnClass({AceMq.class, org.springframework.boot.health.contributor.HealthIndicator.class})
@ConditionalOnBean(AceMq.class)
@ConditionalOnProperty(prefix = "management.health.acemq", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AceMqHealthAutoConfiguration {

    /**
     * @param aceMq the connection
     * @return the health indicator
     */
    @Bean
    @ConditionalOnMissingBean(name = "aceMqHealthIndicator")
    public AceMqHealthIndicator aceMqHealthIndicator(AceMq aceMq) {
        return new AceMqHealthIndicator(aceMq);
    }
}
