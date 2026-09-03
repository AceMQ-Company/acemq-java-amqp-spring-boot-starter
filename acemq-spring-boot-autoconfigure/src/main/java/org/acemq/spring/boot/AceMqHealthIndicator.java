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

import org.acemq.amqp.core.AceMq;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Reports the connection under {@code /actuator/health}.
 *
 * <p>Down means the connection is not open. A broker applying back pressure is reported as
 * <em>up, with the reason</em>, and that is a deliberate choice: a blocked connection is the
 * broker protecting itself, usually from disk or memory pressure, and an application that
 * fails its own health check for it is an application that gets restarted by an orchestrator
 * into the same blocked broker, having thrown away whatever it was holding.
 *
 * <p>The details are the four facts worth having in an incident: whether it is open, whether
 * it is blocked and why, how many publishes are in flight, and which transport this is.
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
        builder.status(open ? org.springframework.boot.actuate.health.Status.UP
                        : org.springframework.boot.actuate.health.Status.DOWN)
                .withDetail("transport", aceMq.transportName())
                .withDetail("open", open)
                .withDetail("blocked", aceMq.isBlocked())
                .withDetail("inFlight", aceMq.inFlight());
        aceMq.blockedReason().ifPresent(reason -> builder.withDetail("blockedReason", reason));
    }
}
