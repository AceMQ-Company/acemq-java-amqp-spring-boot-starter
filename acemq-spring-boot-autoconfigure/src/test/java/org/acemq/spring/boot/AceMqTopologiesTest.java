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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.acemq.amqp.api.ApplyMode;
import org.acemq.amqp.api.Topology;
import org.junit.jupiter.api.Test;

/** Declared topology, before it reaches a broker. */
class AceMqTopologiesTest {

    @Test
    void queuesAreQuorumUnlessAskedOtherwise() {
        AceMqProperties.Topology properties = new AceMqProperties.Topology();
        properties.getQueues().add(queue("orders.new", AceMqProperties.Topology.Queue.Kind.QUORUM));
        properties.getQueues().add(queue("orders.audit", AceMqProperties.Topology.Queue.Kind.CLASSIC));

        Topology topology = AceMqTopologies.from(properties);

        assertThat(topology.queues()).extracting(Topology.QueueSpec::name, Topology.QueueSpec::quorum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("orders.new", true),
                        org.assertj.core.groups.Tuple.tuple("orders.audit", false));
    }

    @Test
    void classicQueuesKeepTheirArguments() {
        AceMqProperties.Topology properties = new AceMqProperties.Topology();
        AceMqProperties.Topology.Queue queue = queue("orders.retry", AceMqProperties.Topology.Queue.Kind.CLASSIC);
        queue.setArguments(Map.of("x-message-ttl", 5000));
        properties.getQueues().add(queue);

        Topology topology = AceMqTopologies.from(properties);

        assertThat(topology.queues().get(0).arguments()).containsEntry("x-message-ttl", 5000);
    }

    @Test
    void exchangesAndBindingsAreCarried() {
        AceMqProperties.Topology properties = new AceMqProperties.Topology();
        AceMqProperties.Topology.Exchange exchange = new AceMqProperties.Topology.Exchange();
        exchange.setName("orders");
        exchange.setType("topic");
        properties.getExchanges().add(exchange);
        AceMqProperties.Topology.Binding binding = new AceMqProperties.Topology.Binding();
        binding.setQueue("orders.new");
        binding.setExchange("orders");
        binding.setRoutingKey("order.created");
        properties.getBindings().add(binding);

        Topology topology = AceMqTopologies.from(properties);

        assertThat(topology.exchanges()).singleElement()
                .satisfies(spec -> {
                    assertThat(spec.name()).isEqualTo("orders");
                    assertThat(spec.type()).isEqualTo("topic");
                });
        assertThat(topology.bindings()).singleElement()
                .satisfies(spec -> {
                    assertThat(spec.queue()).isEqualTo("orders.new");
                    assertThat(spec.exchange()).isEqualTo("orders");
                    assertThat(spec.routingKey()).isEqualTo("order.created");
                });
    }

    /**
     * A queue entry with no name is a YAML indentation mistake, and it is worth failing at
     * startup with the property path rather than at the broker with "queue name cannot be
     * empty", which does not say which of forty entries it was.
     */
    @Test
    void aNamelessQueueIsRejectedWithItsPropertyPath() {
        AceMqProperties.Topology properties = new AceMqProperties.Topology();
        properties.getQueues().add(new AceMqProperties.Topology.Queue());

        assertThatThrownBy(() -> AceMqTopologies.from(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acemq.topology.queues[].name");
    }

    @Test
    void applyModesMap() {
        assertThat(AceMqTopologies.mode(AceMqProperties.Topology.Apply.CREATE_ONLY))
                .isEqualTo(ApplyMode.CREATE_ONLY);
        assertThat(AceMqTopologies.mode(AceMqProperties.Topology.Apply.VALIDATE))
                .isEqualTo(ApplyMode.VALIDATE);
        assertThat(AceMqTopologies.mode(AceMqProperties.Topology.Apply.DRY_RUN))
                .isEqualTo(ApplyMode.DRY_RUN);
    }

    private static AceMqProperties.Topology.Queue queue(String name, AceMqProperties.Topology.Queue.Kind kind) {
        AceMqProperties.Topology.Queue queue = new AceMqProperties.Topology.Queue();
        queue.setName(name);
        queue.setType(kind);
        return queue;
    }
}
