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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.acemq.spring.boot.AceMqProperties.Topology.Queue.Kind;

/**
 * How an {@link AceListener} declares the queue it consumes, through
 * {@link AceListener#declare()}.
 *
 * <pre>{@code
 * @AceListener(queue = "orders.new", declare = @AceQueue)
 * @AceListener(queue = "audit", declare = @AceQueue(type = Kind.CLASSIC, arguments = "x-message-ttl=60000"))
 * }</pre>
 *
 * <p>The options are the ones an entry under {@code acemq.topology.queues} has, with the same
 * names and the same defaults, and the queue is declared through the same topology: a durable
 * quorum queue unless {@code type} says classic, arguments for classic queues only, and
 * {@code deadLetter} adding the {@code .dlq} and {@code .parked} queues and the exchange that
 * reaches them. A queue declared both here and under {@code acemq.topology.queues} must be
 * declared the same way in both, or the startup fails naming the difference.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AceQueue {

    /**
     * quorum or classic, as {@code acemq.topology.queues[].type}.
     *
     * @return queue type
     */
    Kind type() default Kind.QUORUM;

    /**
     * Broker arguments as {@code name=value}, as {@code acemq.topology.queues[].arguments}, and
     * for classic queues only. A value that is a whole number is sent as a number and
     * {@code true}/{@code false} as a boolean, which is what the broker expects of
     * {@code x-message-ttl}, {@code x-max-length} and the rest; anything else is a string.
     * Property placeholders are resolved.
     *
     * @return arguments
     */
    String[] arguments() default {};

    /**
     * Dead-letter what the broker rejects, as {@code acemq.topology.queues[].dead-letter}.
     *
     * @return whether to declare the dead-letter queues too
     */
    boolean deadLetter() default false;
}
