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

import org.acemq.amqp.api.ApplyMode;
import org.acemq.amqp.api.Topology;
import org.acemq.amqp.api.TopologyPlan;
import org.acemq.amqp.core.AceMq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Applies the declared topology once, at startup.
 *
 * <p>The plan is logged whatever the mode, because "the queue was already there" and "the
 * queue was created just now" are different facts about a deployment and both are worth
 * having in the log when a message goes missing an hour later.
 *
 * <p>Drift is reported and, with {@code acemq.topology.fail-on-drift}, fatal. It is not
 * corrected. A starter that repaired drift would be a starter that changed a queue's
 * arguments during a rolling deployment, and the broker's answer to that is to refuse the
 * declaration on every instance that has not been restarted yet.
 */
public class AceMqTopologyInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AceMqTopologyInitializer.class);

    private final AceMq aceMq;
    private final Topology topology;
    private final ApplyMode mode;
    private final boolean failOnDrift;

    public AceMqTopologyInitializer(AceMq aceMq, Topology topology, ApplyMode mode, boolean failOnDrift) {
        this.aceMq = aceMq;
        this.topology = topology;
        this.mode = mode;
        this.failOnDrift = failOnDrift;
    }

    @Override
    public void afterPropertiesSet() {
        if (topology.exchanges().isEmpty() && topology.queues().isEmpty() && topology.bindings().isEmpty()) {
            // Nothing declared. Applying an empty topology is harmless and still costs a
            // round trip per instance at startup, which on a service that scales to
            // fifty is fifty questions nobody asked the broker.
            return;
        }
        TopologyPlan plan = aceMq.topology().apply(topology, mode);
        if (plan.hasChanges() || plan.hasDrift()) {
            log.info("topology ({}):\n{}", mode, plan.render());
        } else {
            log.debug("topology ({}): nothing to do", mode);
        }
        if (plan.hasDrift() && failOnDrift) {
            throw new IllegalStateException(
                    "the broker's topology differs from the declared one, and "
                            + "acemq.topology.fail-on-drift is set:\n" + plan.render());
        }
    }
}
