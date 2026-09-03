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

import java.lang.reflect.Method;
import java.util.Map;
import org.acemq.amqp.api.Message;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.core.MethodIntrospector;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Finds {@link AceListener} methods on every bean and registers them.
 *
 * <p>It registers; it does not start. Starting belongs to {@link AceListenerRegistry}, which
 * is a lifecycle, and a post-processor that started consumers would do it while the context
 * is still being built.
 *
 * <p>The registry is looked up lazily through an {@link ObjectProvider}. A post-processor
 * that injects its collaborators eagerly forces them -- and everything they need, which here
 * is the broker connection -- to be created before any other bean is post-processed, which
 * turns an unreachable broker into a failure with an unrelated stack trace.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class AceListenerAnnotationBeanPostProcessor implements BeanPostProcessor, EmbeddedValueResolverAware {

    private final ObjectProvider<AceListenerRegistry> registry;
    private final AceMqProperties.Listener defaults;

    private StringValueResolver resolver;

    public AceListenerAnnotationBeanPostProcessor(
            ObjectProvider<AceListenerRegistry> registry, AceMqProperties.Listener defaults) {
        this.registry = registry;
        this.defaults = defaults;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Map<Method, AceListener> annotated = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<AceListener>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, AceListener.class));
        for (Map.Entry<Method, AceListener> entry : annotated.entrySet()) {
            registry.getObject().register(endpoint(bean, beanName, entry.getKey(), entry.getValue()));
        }
        return bean;
    }

    private AceListenerEndpoint endpoint(Object bean, String beanName, Method method, AceListener listener) {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException("@AceListener method " + method
                    + " must take exactly one parameter: the payload, or a Message<payload>");
        }
        MethodParameter parameter = new MethodParameter(method, 0);
        boolean takesMessage = Message.class.isAssignableFrom(parameter.getParameterType());
        Class<?> payloadType = takesMessage
                ? messagePayloadType(parameter, method)
                : parameter.getParameterType();

        String queue = resolve(listener.queue());
        if (!StringUtils.hasText(queue)) {
            throw new IllegalStateException("@AceListener on " + method + " has an empty queue name");
        }
        String id = StringUtils.hasText(listener.id())
                ? resolve(listener.id())
                : beanName + "#" + method.getName();

        return new AceListenerEndpoint(
                id,
                queue,
                listener.prefetch() < 0 ? defaults.getPrefetch() : listener.prefetch(),
                listener.concurrency() < 0 ? defaults.getConcurrency() : listener.concurrency(),
                autoStartup(listener, method),
                payloadType,
                takesMessage,
                bean,
                method);
    }

    private Class<?> messagePayloadType(MethodParameter parameter, Method method) {
        Class<?> resolved = ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve();
        if (resolved == null) {
            // A raw Message, or Message<?>. The codec needs a concrete type to decode
            // into, and guessing Object here would produce a LinkedHashMap the handler
            // then casts, which fails at the first field access rather than here.
            throw new IllegalStateException("@AceListener method " + method
                    + " takes a raw Message; give it a type parameter, as in Message<Order>");
        }
        return resolved;
    }

    private boolean autoStartup(AceListener listener, Method method) {
        if (!StringUtils.hasText(listener.autoStartup())) {
            return defaults.isAutoStartup();
        }
        String value = resolve(listener.autoStartup());
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("@AceListener on " + method + " has autoStartup=\"" + value
                + "\", which is neither true nor false");
    }

    private String resolve(String value) {
        return resolver == null ? value : resolver.resolveStringValue(value);
    }
}
