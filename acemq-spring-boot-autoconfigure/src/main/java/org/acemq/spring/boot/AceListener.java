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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Consumes a queue with the annotated method.
 *
 * <pre>{@code
 * @AceListener(queue = "orders.new")
 * public void onOrder(Order order) {
 *     ...
 * }
 * }</pre>
 *
 * <p>The method takes exactly one parameter. If it is {@link org.acemq.amqp.api.Message},
 * the handler receives the envelope, the headers and the attempt count as well as the
 * payload; anything else is taken as the payload type and decoded with the configured codec.
 *
 * <p>Throwing from the method rejects the message. What happens next is the library's
 * behaviour, not this starter's: with {@code acemq.listener.retry.enabled} the message is
 * republished on the retry ladder with a delay, and without it the message is dead-lettered,
 * or requeued if {@code acemq.listener.requeue-on-failure} is set. There is deliberately no
 * "return an ack" form. The library has an ack-aware handler interface that nothing in it
 * currently uses, and wiring an annotation to an unused code path is how a starter grows a
 * feature its own library does not have.
 *
 * <p>Every attribute defaults to the value under {@code acemq.listener}, so the common case
 * carries a queue name and nothing else.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AceListener {

    /**
     * The queue to consume. Property placeholders are resolved, so
     * {@code queue = "${orders.queue}"} works.
     *
     * @return queue name
     */
    String queue();

    /**
     * A name for this listener, used in logs and by
     * {@link AceListenerRegistry#get(String)}. Defaults to {@code beanName#methodName}.
     *
     * @return listener id
     */
    String id() default "";

    /**
     * Unacknowledged messages allowed at once. -1 takes {@code acemq.listener.prefetch}.
     *
     * @return prefetch, or -1
     */
    int prefetch() default -1;

    /**
     * Consumers on this queue. -1 takes {@code acemq.listener.concurrency}. Above 1, this
     * queue's messages are no longer handled in order -- which is usually fine and
     * occasionally the cause of a bug that only appears under load.
     *
     * @return concurrency, or -1
     */
    int concurrency() default -1;

    /**
     * Whether to start with the application context. Empty takes
     * {@code acemq.listener.auto-startup}; a property placeholder is resolved, so
     * {@code autoStartup = "${orders.consume:false}"} works.
     *
     * @return "true", "false", a placeholder, or empty
     */
    String autoStartup() default "";
}
