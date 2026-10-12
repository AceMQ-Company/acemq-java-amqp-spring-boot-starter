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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.acemq.amqp.api.Message;
import org.acemq.amqp.api.MessageHandler;
import org.acemq.amqp.api.Topology;
import org.springframework.util.ReflectionUtils;

/**
 * One {@link AceListener} method, resolved: which queue, how many consumers, what the
 * payload type is, and how to call it.
 *
 * <p>Everything the annotation left to configuration has been resolved by the time an
 * endpoint exists, so nothing downstream has to know that -1 meant "the default".
 */
public final class AceListenerEndpoint {

    private final String id;
    private final String queue;
    private final int prefetch;
    private final int concurrency;
    private final boolean autoStartup;
    private final Class<?> payloadType;
    private final boolean takesMessage;
    private final Object bean;
    private final String beanName;
    private final Method method;
    private final Topology declaration;

    AceListenerEndpoint(
            String id,
            String queue,
            int prefetch,
            int concurrency,
            boolean autoStartup,
            Class<?> payloadType,
            boolean takesMessage,
            Object bean,
            String beanName,
            Method method,
            Topology declaration) {
        this.id = id;
        this.beanName = beanName;
        this.queue = queue;
        this.prefetch = prefetch;
        this.concurrency = concurrency;
        this.autoStartup = autoStartup;
        this.payloadType = payloadType;
        this.takesMessage = takesMessage;
        this.bean = bean;
        this.method = method;
        this.declaration = declaration;
        ReflectionUtils.makeAccessible(method);
    }

    public String id() {
        return id;
    }

    public String queue() {
        return queue;
    }

    public int prefetch() {
        return prefetch;
    }

    public int concurrency() {
        return concurrency;
    }

    public boolean isAutoStartup() {
        return autoStartup;
    }

    /** The name of the bean that declares the method. */
    public String beanName() {
        return beanName;
    }

    /** The annotated method. */
    public Method method() {
        return method;
    }

    /**
     * What the listener declares before it starts, from {@link AceListener#declare()}: the
     * queue, and its dead-letter queues when it asked for them.
     */
    public Optional<Topology> declaration() {
        return Optional.ofNullable(declaration);
    }

    /** The type messages are decoded into, which is the parameter type or its Message type. */
    public Class<?> payloadType() {
        return payloadType;
    }

    /** Whether the method asked for the whole message rather than the payload. */
    public boolean takesMessage() {
        return takesMessage;
    }

    /**
     * A handler that calls the method.
     *
     * @param <T> payload type, checked at registration rather than here
     * @return the handler to hand to the library
     */
    @SuppressWarnings("unchecked")
    <T> MessageHandler<T> handler() {
        return message -> invoke((Message<Object>) message);
    }

    private void invoke(Message<Object> message) throws Exception {
        try {
            method.invoke(bean, takesMessage ? message : message.payload());
        } catch (InvocationTargetException wrapped) {
            // The handler's own exception, not the reflection wrapper around it. The
            // library decides retry and dead-lettering from the exception type, so an
            // InvocationTargetException reaching it would make every failure look like
            // the same unretryable fault.
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw wrapped;
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException("listener method is not callable: " + method, unreachable);
        }
    }

    @Override
    public String toString() {
        return "AceListenerEndpoint[" + id + " on " + queue + ", " + concurrency
                + " consumer(s), prefetch " + prefetch + "]";
    }
}
