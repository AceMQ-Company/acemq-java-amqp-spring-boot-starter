# Interceptors

Two interfaces that see every message on the connection: `PublishInterceptor` on the way out and
`ConsumeInterceptor` on the way in. They are the seam for the cross-cutting things — a tenant
header, an audit record, a metric the library does not emit, a log line with your own
correlation field.

## Registering them

`AceMq.intercept(...)` registers one, and it is a method on the connection rather than a bean the
starter collects. So the registration is a bean of its own that depends on the connection:

```java
@Configuration
class Interception {

    /**
     * Registers every interceptor bean in the context, in their own declared order. Runs before
     * anything can publish or consume, because the AceMq bean is its dependency and the listener
     * registry starts later still.
     */
    @Bean
    InitializingBean aceMqInterceptors(
            AceMq mq,
            ObjectProvider<PublishInterceptor> publishing,
            ObjectProvider<ConsumeInterceptor> consuming) {

        return () -> {
            publishing.orderedStream().forEach(mq::intercept);
            consuming.orderedStream().forEach(mq::intercept);
        };
    }
}
```

`ObjectProvider` rather than a `List`, so an application with no interceptors does not need an
empty list bean. `orderedStream()` respects Spring's `@Order`; the interceptors' own `order()`
method is what the library sorts by, so set one or the other and not both — see
[ordering](#ordering).

There is no `acemq.interceptors` property and there is not going to be one. An interceptor is
code, and a property that names a class is a class name in a string that nothing checks.

## On the way out

```java
@Component
class TenantHeader implements PublishInterceptor {

    @Override
    public PublishContext beforePublish(PublishContext context) {
        return context.withEnvelope(context.envelope().toBuilder()
                .header("tenant", TenantContext.current())
                .build());
    }
}
```

`beforePublish` returns the context, so it can change it. `withEnvelope(...)` is the only
replacement it offers — the exchange, the routing key and the payload are readable and not
replaceable, deliberately: an interceptor that could redirect a message is an interceptor that
can make a publish go somewhere the calling code did not ask for, and finding that afterwards
means reading every interceptor in the application.

Returning the context unchanged is fine, and is what an observing interceptor does:

```java
@Component
class PublishAudit implements PublishInterceptor {

    @Override
    public PublishContext beforePublish(PublishContext context) {
        return context;
    }

    @Override
    public void afterConfirm(PublishContext context, PublishResult result) {
        audit.record(context.exchange(), context.routingKey(),
                result.messageId(), result.routed(), result.latency());
    }

    @Override
    public void onError(PublishContext context, Throwable failure) {
        audit.recordFailure(context.exchange(), context.routingKey(), failure);
    }
}
```

`afterConfirm` and `onError` are `default` methods, so implement only the ones you want.
`afterConfirm` runs when the broker has confirmed, which makes it the right place for an audit
record that must not claim a message was sent when it was not.

## On the way in

```java
@Component
class ConsumeLogging implements ConsumeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ConsumeLogging.class);

    @Override
    public void beforeHandle(ConsumeContext context) {
        MDC.put("correlationId", context.envelope().correlationId());
        MDC.put("messageType", context.envelope().type());
        MDC.put("attempt", String.valueOf(context.envelope().attempt()));
    }

    @Override
    public void afterHandle(ConsumeContext context, Ack ack) {
        log.info("handled {} on {}: {}", context.envelope().type(), context.queue(), ack);
        MDC.clear();
    }

    @Override
    public void onError(ConsumeContext context, Throwable failure) {
        log.warn("attempt {} of {} failed", context.envelope().attempt(),
                context.envelope().type(), failure);
        MDC.clear();
    }
}
```

This one is worth its own mention, because putting the correlation id into the MDC is what makes
every log line a handler writes traceable, and there is nowhere else to do it once for every
listener.

`beforeHandle` cannot reject a message — it returns nothing. The `Ack` handed to `afterHandle` is
what the consumer decided, reported rather than requested: `accept`, `retry`, `deadLetter` or
`release`. An interceptor that wants to reject a message is really a handler, or a validation
step in a [pipeline](messaging-patterns.md#pipelines).

**`onError` runs instead of `afterHandle`, not as well as it.** Clear the MDC in both, as above,
or clear it in `beforeHandle` before setting it — a thread that keeps a stale correlation id
attributes the next message's logs to the previous message.

## Ordering

```java
@Override
public int order() {
    return 10;   // lower runs first on the way out
}
```

`order()` is a `default` method on both interfaces. Lower runs first. It matters when one
interceptor reads something another sets — a tracing interceptor that reads the tenant header has
to run after the one that adds it.

`order()` and Spring's `@Order` are two mechanisms for the same thing, and using both is how they
come to disagree. `orderedStream()` in the registration above sorts by Spring's; the library then
sorts by its own. Pick `order()` — it travels with the interceptor and works the same way outside
Spring.

## What not to put in one

An interceptor is on the hot path of every message on the connection, synchronously.

- **No blocking I/O.** An audit record that writes to a database on `afterConfirm` adds that
  write to every publish's latency. Queue it and flush it elsewhere.
- **No exceptions you have not thought about.** An interceptor that throws in `beforePublish`
  fails the publish. That is occasionally what you want — a tenant header that is missing because
  the context was lost is a bug worth failing on — and usually not.
- **Nothing that belongs to one message type.** An interceptor sees everything on the connection.
  Logic for one queue belongs in that queue's handler, where it is findable.

## Tracing

The library's tracing is not an interceptor — it is the `Telemetry` bean, which gets the publish
and consume scopes and the propagation headers. Reach for that rather than for an interceptor
when what you want is spans; [Observability](observability.md) has it.
