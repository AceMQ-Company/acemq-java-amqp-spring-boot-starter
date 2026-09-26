# Publishing

There is no annotation and no template on the publishing side. Inject `AceMq` and ask it for
a publisher.

```java
@Service
class Orders {

    private final AceMq mq;

    Orders(AceMq mq) {
        this.mq = mq;
    }

    void place(Order order) {
        mq.publisher("orders", "order.created", Order.class).send(order);
    }
}
```

[Why there is no `AceTemplate`](listeners.md#there-is-no-template) is a separate argument.
This page is about what the publisher does once you have it.

## A publisher is worth keeping

`mq.publisher(...)` returns a `DefaultPublisher<T>`, and building one is cheap but not free:
it resolves the codec, and on a confirming connection it registers for confirms. A service
that publishes the same type to the same place on every request should make it once.

The obvious place is a `@Bean`:

```java
@Configuration
class OrderPublishers {

    @Bean(destroyMethod = "close")
    Publisher<Order> orderPublisher(AceMq mq) {
        return mq.publisher("orders", "order.created", Order.class);
    }
}
```

The bean type is `Publisher<Order>` — the library's interface — rather than
`DefaultPublisher<Order>`. Inject it as `Publisher<Order>` and a test can substitute
something else without a subclass.

`destroyMethod = "close"` refuses further sends once the context is shutting down. It does
not wait for anything: the connection belongs to `AceMq`, and a publisher holds no resource
of its own. What waits for messages still in flight is the shutdown of the connection itself
— `AceMq` is a bean whose destroy method is `close`, and Spring destroys it after every
`SmartLifecycle` has stopped.

Declare `DefaultPublisher<Order>` instead when you want the fluent methods — `.as(...)`,
`.with(...)`, `.replyingTo(...)` — on the injected bean rather than at the call site.

## What `send` gives back

```java
PublishResult result = publisher.send(order);

result.messageId();   // the id the envelope was given
result.routed();      // whether the broker matched it to a queue
result.latency();     // how long the confirm took
```

`send` returns when the broker has confirmed the message, which is what
`acemq.publisher-confirms: true` buys and is the default. It throws
`PublishFailedException` when the confirm does not arrive inside
`acemq.confirm-timeout`, or arrives as a negative acknowledgement.

**`routed()` is false, not an exception, for a message nothing was listening for.** A publish
to a routing key with no matching binding is confirmed by the broker and then discarded.
That is usually a topology mistake rather than a runtime one, so it is reported rather than
thrown — but a service that has no business publishing into a void should check it:

```java
PublishResult result = publisher.send(order);
if (!result.routed()) {
    log.error("nothing is bound for order.created; the order was dropped");
}
```

`PublishOptions.allowUnroutable()` turns that check off at the broker level, for a publisher
that genuinely does not care whether anyone is listening — an audit feed nobody has
subscribed to yet, say.

## Options

```java
mq.publisher("orders", "order.created", Order.class,
        PublishOptions.defaults()
                .expiringAfter(Duration.ofMinutes(5))
                .withPriority(4));
```

| Method | What it does |
|---|---|
| `PublishOptions.defaults()` | Persistent, mandatory, no expiry, no priority |
| `PublishOptions.transientDelivery()` | Not written to disk; lost on a broker restart |
| `.allowUnroutable()` | Do not report a message nothing is bound for |
| `.expiringAfter(Duration)` | Per-message time to live |
| `.withPriority(int)` | Priority, on a classic queue declared with `x-max-priority` |

`transientDelivery()` is the one worth thinking about twice. It is faster because the broker
does not fsync, and it means a broker restart loses whatever had not yet been handed to a
consumer. For a metrics sample that is the right trade; for an order it is not.

Priority needs a classic queue with `x-max-priority`, which means
`acemq.topology.queues[].type: classic` and the argument set — see
[Configuration](configuration.md#topology). A priority on a message bound for a quorum queue
is accepted by the broker and ignored.

## The envelope

Every message carries an `Envelope`: an id, a type, a version, a correlation id, an attempt
count, the time it was first seen. The publisher fills one in. Pass your own when a message
belongs to a conversation that started somewhere else:

```java
void onPaymentTaken(Message<PaymentTaken> incoming) {
    Envelope outgoing = Envelope.of("order.confirmed")
            .correlationId(incoming.envelope().correlationId())
            .causationId(incoming.envelope().id())
            .header("tenant", tenantOf(incoming))
            .build();

    mq.publisher("orders", "order.confirmed", OrderConfirmed.class)
      .send(confirmation, outgoing);
}
```

`correlationId` is the conversation; `causationId` is the single message that caused this
one. Carrying the first and setting the second is what makes a trace across six services
readable afterwards, and it is two lines that nobody writes unless they are shown.

`Envelope.of(type)` returns a builder. `incoming.envelope().causing("order.confirmed")`
returns a builder that has already carried the correlation across and set the causation, which
is the same thing in one call.

## Batches

```java
List<PublishResult> results = publisher.sendAll(orders);
```

`sendAll` publishes the whole collection and then waits once for all of the confirms, rather
than waiting for each in turn. For a thousand small messages over a connection with any
latency at all that is the difference between one round trip's wait and a thousand.

When every message is confirmed, the list is positional: `results.get(3)` is the result for
the fourth payload. When any of them fails, `sendAll` throws `PublishFailedException` instead
of returning, and the message says how many of how many failed:

```
23 of 1000 messages were not confirmed; 977 were. The first failure was: ...
```

The count is there because a half-succeeded batch is the ordinary outcome of a broker problem
partway through, and a caller told only that it failed will resend the 977 that arrived.
There is no partial list to read: the results of the successful sends are not returned
alongside the failure. A batch that must be reconciled message by message wants `sendAsync`
and its own list of futures, below.

## Asynchronously

```java
CompletableFuture<PublishResult> future = publisher.sendAsync(order);
```

`sendAsync` returns as soon as the message is on the wire; the future completes when the
confirm arrives. This is the shape for a request handler that has other work to do, and for
a loop that wants many publishes in flight at once:

```java
List<CompletableFuture<PublishResult>> inFlight = orders.stream()
        .map(publisher::sendAsync)
        .toList();
CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
```

`sendAll` is the same thing with one wait and less code, and it is the right default when you
have the collection in hand. Reach for the futures instead when the publishes do not arrive
together, or when you need to know exactly which of them failed — which `sendAll` does not
tell you.

Note that `sendAsync` is asynchronous about the *confirm*, not about the send. A publish onto
a blocked connection, or one that has reached `max-outstanding-publishes`, still waits in the
calling thread; see below.

## Back pressure and blocked connections

A broker running short of memory or disk stops reading from its publishers. This is a
RabbitMQ feature, not a fault: it is how the broker protects itself from a producer that is
faster than its consumers. From the publishing side it looks like `send` not returning.

Two properties bound how long that lasts:

| Property | Default | What it does |
|---|---|---|
| `acemq.blocked-timeout` | `30s` | How long a publish waits on a blocked connection before failing |
| `acemq.max-outstanding-publishes` | `10000` | Unconfirmed publishes allowed in flight at once, on the asynchronous path |

When the connection is blocked, a publish waits up to `blocked-timeout` and then throws
`ConnectionBlockedException`, whose message names the broker's own reason — `memory` or
`disk`. While the connection is blocked, `confirm-timeout` is deliberately not applied: a
broker under a resource alarm is not a slow broker, and failing the message would not help it
recover. The whole wait is bounded by `blocked-timeout` instead.

`max-outstanding-publishes` is the only back pressure an asynchronous publisher has. The slot
is taken *before* the message is written, and a `sendAsync` or `sendAll` that finds no slot
free waits up to `confirm-timeout` for one and then throws `TransportException` — "the broker
is not keeping up; publish more slowly rather than buffering more". Without that bound a
caller publishing faster than the broker confirms accumulates unconfirmed messages until the
process dies, which looks exactly like throughput right up to the moment it does not.

Both defaults are deliberately survivable rather than fast. The alternative to a bound is an
application that is killed for memory, at which point the messages are gone and there is
nothing left to say how many there were.

A service can ask, rather than wait:

```java
@Service
class OrderIntake {

    private final AceMq mq;

    boolean accepting() {
        return !mq.isBlocked();
    }

    String why() {
        return mq.blockedReason().orElse("not blocked");
    }

    long unconfirmed() {
        return mq.inFlight();
    }
}
```

That is what the [health indicator](observability.md) reports, and it is the right thing to
put behind a readiness probe's decision to shed load — and the wrong thing to put behind a
liveness probe's decision to restart. A blocked broker is not fixed by restarting the
application into the same broker; it is fixed by the broker recovering, and a restart loop
meanwhile turns one problem into two.

`mq.pausePublishing()` and `mq.resumePublishing()` stop and start the publishing side by
hand, which is how a maintenance endpoint or a feature flag drains a producer without
stopping it. A `send` while paused throws `PublishingPausedException` rather than blocking,
so a caller can tell "we are deliberately not publishing" from "the broker is not reading".

## Publishing from inside a transaction

Do not. A `send` inside `@Transactional` publishes a message about a database change that may
still roll back, and the message has no rollback. The fix is the
[transactional outbox](messaging-patterns.md#transactional-outbox), which writes the message
to the same database in the same transaction and publishes it afterwards.

## Interceptors

`PublishInterceptor` sees every message on its way out, which is where a tenant header, an
audit hook or a metric that the library does not already emit belongs. See
[Interceptors](interceptors.md).
