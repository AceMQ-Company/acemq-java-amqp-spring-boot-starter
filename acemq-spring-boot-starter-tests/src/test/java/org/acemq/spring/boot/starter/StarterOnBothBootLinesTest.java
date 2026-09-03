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
package org.acemq.spring.boot.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.acemq.amqp.core.AceMq;
import org.acemq.spring.boot.AceListener;
import org.acemq.spring.boot.AceListenerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Everything the starter puts on a classpath, in one real application context, on whichever
 * Spring Boot line the build is using.
 *
 * <p>The question this module exists for: the starter ships two health modules, one compiled
 * against Boot 3's {@code org.springframework.boot.actuate.health} and one against Boot 4's
 * {@code org.springframework.boot.health.contributor}. Only one line's API is ever present,
 * so one of those jars is always referencing classes that are not there. If Boot loaded
 * either auto-configuration before evaluating its {@code @ConditionalOnClass}, an
 * application would fail to start with a {@code NoClassDefFoundError} naming a Spring class
 * — and no test that imports the auto-configurations explicitly would ever see it, because
 * importing them is what loads them.
 *
 * <p>This is a full {@code @SpringBootTest}: auto-configuration goes through the real
 * imports files and the ASM-based class-condition filter, which is the mechanism being
 * relied on.
 *
 * <p>Run twice by CI — once per line — through {@code -Dspring.boot.version}.
 */
@SpringBootTest(properties = {
        "acemq.url=memory://starter-tests",
        "acemq.topology.exchanges[0].name=orders",
        "acemq.topology.exchanges[0].type=topic",
        "acemq.topology.queues[0].name=orders.new",
        "acemq.topology.queues[0].type=classic",
        "acemq.topology.bindings[0].queue=orders.new",
        "acemq.topology.bindings[0].exchange=orders",
        "acemq.topology.bindings[0].routing-key=order.created"
})
class StarterOnBothBootLinesTest {

    @Autowired ApplicationContext context;
    @Autowired AceMq mq;
    @Autowired Orders orders;
    @Autowired AceListenerRegistry listeners;

    @Test
    void theContextStartsWithBothHealthModulesOnTheClasspath() {
        // Reaching this assertion is most of the test: a context that had loaded the
        // wrong line's auto-configuration would have failed before it.
        assertThat(mq.isOpen()).isTrue();
    }

    @Test
    void exactlyOneHealthIndicatorIsRegistered() {
        // By name rather than by type: the two indicator classes cannot both be named
        // in one compilation unit, since one of them will not load on this line.
        assertThat(context.containsBean("aceMqHealthIndicator")).isTrue();
        assertThat(context.getBeanNamesForType(Object.class, false, false))
                .filteredOn(name -> name.equals("aceMqHealthIndicator"))
                .hasSize(1);
    }

    @Test
    void theListenerReceivesAMessage() throws Exception {
        mq.publisher("orders", "order.created", Order.class).send(new Order("A-1", 3));

        assertThat(orders.received.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listeners.running()).isNotEmpty();
    }

    record Order(String id, int quantity) {}

    @Component
    static class Orders {

        final CountDownLatch received = new CountDownLatch(1);

        @AceListener(queue = "orders.new", id = "orders")
        void onOrder(Order order) {
            received.countDown();
        }
    }

    @SpringBootApplication
    static class TestApplication {

        @Bean
        Orders orders() {
            return new Orders();
        }
    }
}
