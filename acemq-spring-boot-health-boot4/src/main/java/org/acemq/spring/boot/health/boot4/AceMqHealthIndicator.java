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
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports the connection under {@code /actuator/health}, on Spring Boot 4.
 *
 * <p>Identical in behaviour to the Boot 3 twin in
 * {@code acemq-spring-boot-health-boot3}, and identical in text apart from three imports:
 * Boot 4 moved the health contributor API from {@code spring-boot-actuator}'s
 * {@code org.springframework.boot.actuate.health} to {@code spring-boot-health}'s
 * {@code org.springframework.boot.health.contributor}. The two {@code AbstractHealthIndicator}
 * classes have no common ancestor, so one class cannot extend both and the duplication is
 * the honest option — the alternative is reflection, which turns a compile error into a
 * runtime one.
 *
 * <p>Down means the connection is not open. A broker applying back pressure is reported as
 * <em>up, with the reason</em>: a blocked connection is the broker protecting itself, and an
 * application that fails its own health check for it gets restarted by an orchestrator into
 * the same blocked broker, having thrown away whatever it was holding.
 */
public class AceMqHealthIndicator extends AbstractHealthIndicator {

    private final AceMq aceMq;

    public AceMqHealthIndicator(AceMq aceMq) {
        super("AceMQ health check failed");
        this.aceMq = aceMq;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean open = aceMq.isOpen();
        builder.status(open ? Status.UP : Status.DOWN)
                .withDetail("transport", aceMq.transportName())
                .withDetail("open", open)
                .withDetail("blocked", aceMq.isBlocked())
                .withDetail("inFlight", aceMq.inFlight());
        aceMq.blockedReason().ifPresent(reason -> builder.withDetail("blockedReason", reason));
    }
}
