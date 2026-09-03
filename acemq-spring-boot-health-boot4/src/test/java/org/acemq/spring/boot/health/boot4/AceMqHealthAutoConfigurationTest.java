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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.acemq.amqp.core.AceMq;
import org.acemq.spring.boot.AceMqAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The Boot 4 health indicator, against a real connection over the in-memory transport. */
class AceMqHealthAutoConfigurationTest {

    private static final AtomicInteger BROKERS = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AceMqAutoConfiguration.class, AceMqHealthAutoConfiguration.class))
            .withPropertyValues("acemq.url=memory://health-boot4-" + BROKERS.incrementAndGet());

    @Test
    void reportsAnOpenConnectionUp() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AceMqHealthIndicator.class);

            Health health = context.getBean(AceMqHealthIndicator.class).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("open", true)
                    .containsEntry("blocked", false)
                    .containsEntry("transport", "in-memory")
                    .containsKey("inFlight");
        });
    }

    @Test
    void reportsAClosedConnectionDown() {
        runner.run(context -> {
            AceMq mq = context.getBean(AceMq.class);
            AceMqHealthIndicator indicator = context.getBean(AceMqHealthIndicator.class);
            mq.close();

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("open", false);
        });
    }

    @Test
    void canBeTurnedOff() {
        runner.withPropertyValues("management.health.acemq.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AceMqHealthIndicator.class));
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwn() {
        runner.withBean("aceMqHealthIndicator", AceMqHealthIndicator.class,
                        () -> new AceMqHealthIndicator(AceMq.connect("memory://health-boot4-own")))
                .run(context -> assertThat(context).hasSingleBean(AceMqHealthIndicator.class));
    }
}
