# Listeners

`@AceListener` consumes a queue with a method.

```java
@Component
class OrderListener {

    @AceListener(queue = "orders.new")
    void onOrder(Order order) {
        // ...
    }
}
```

The annotation goes on a method of any bean. Nothing else is needed: no container factory,
no registrar, no interface to implement.

## What the method may take

Exactly one parameter, in one of two shapes.

**The payload**, decoded with the configured codec:

```java
@AceListener(queue = "orders.new")
void onOrder(Order order) { }
```

**The whole message**, when the envelope matters:

```java
@AceListener(queue = "orders.new")
void onOrder(Message<Order> message) {
    Order order = message.payload();
    int attempt = message.attempt();
    String correlation = message.envelope().correlationId();
    Map<String, Object> headers = message.headers();
}
```

`Message<T>` carries the payload, the envelope — id, type, correlation and causation ids,
attempt count, first-seen time, origin — the raw headers, the queue it came from and the
time it was received.

Two parameters is an error, and so is a raw `Message` without a type argument. Both fail at
startup with the method in the message, because the codec needs a concrete type to decode
into and guessing `Object` produces a `LinkedHashMap` that fails at the handler's first
field access instead.

## Per-listener settings

```java
@AceListener(
        queue = "${orders.queue}",
        id = "orders",
        prefetch = 40,
        concurrency = 4,
        autoStartup = "${orders.consume:true}")
void onOrder(Order order) { }
```

| Attribute | Default | Notes |
|---|---|---|
| `queue` | required | Property placeholders are resolved |
| `id` | `beanName#methodName` | Used in logs and by `AceListenerRegistry.get(id)` |
| `prefetch` | `acemq.listener.prefetch` | Unacknowledged messages allowed at once |
| `concurrency` | `acemq.listener.concurrency` | Consumers on this queue |
| `autoStartup` | `acemq.listener.auto-startup` | A string, so it can be a placeholder |

**Concurrency above 1 gives up ordering.** Messages on one queue are handled in parallel,
which is usually what a service wants and occasionally the cause of a bug that only appears
under load. When order matters per key rather than globally, the library's
[ordered queues](https://acemq-company.github.io/acemq-java-amqp/) do that properly; this
annotation does not try to.

Two listeners cannot share an id. The clash fails at startup rather than leaving whichever
one lost invisible in the registry.

## When a handler throws

Returning normally acknowledges the message. Throwing rejects it, and what happens then is
configuration:

| Configuration | What happens to a failed message |
|---|---|
| Nothing set | Dead-lettered, if the queue has a dead-letter exchange, and dropped otherwise |
| `acemq.listener.requeue-on-failure: true` | Requeued, and retried immediately, and probably failing again |
| `acemq.listener.retry.enabled: true` | Republished with a delay, on the ladder |

The ladder is the reason to prefer this library, so it is worth being precise about what it
does. A retry written by hand usually looks like this:

```java
@AceListener(queue = "orders.new")
void onOrder(Order order) throws InterruptedException {
    try {
        downstream.call(order);
    } catch (TransientFailure e) {
        Thread.sleep(2000);       // do not do this
        downstream.call(order);
    }
}
```

That sleep blocks the consumer's channel. With prefetch 100, up to a hundred messages are
sitting in that consumer's buffer, unavailable to any other consumer, waiting for a thread
that is doing nothing. Ten seconds of backoff across four consumers is forty seconds of
stalled throughput and a queue that looks busy while nothing moves.

The ladder republishes instead: the message goes back to the broker with a delay and an
incremented attempt count, the channel is free, and the next message is handled now. Set
it up in properties:

```yaml
acemq:
  listener:
    retry:
      enabled: true
      max-attempts: 5
      initial-delay: 2s
      max-delay: 30s
      jitter: 0.2
```

and throw:

```java
@AceListener(queue = "orders.new")
void onOrder(Order order) {
    downstream.call(order);   // throws, the ladder handles it
}
```

`message.attempt()` tells a handler which attempt it is on, which is how a fifth attempt can
do something different from the first.

**There is no return value that acknowledges.** The library has an ack-aware handler
interface that nothing in the library currently uses. An annotation wired to an unused code
path is a starter growing a feature its own library does not have, so this one does not.

[Retries, dead letters and replay](reliability.md) is the whole of this subject: where a message
goes when the ladder gives up, how to get it back, and how to survive the redelivery.

## What the annotation does not reach

The annotation has five attributes and there will not be many more. Three things it cannot do,
each with the same answer — declare that consumer as a `ConsumerGroup` bean and use the library's
own API:

| | |
|---|---|
| An [idempotency store](reliability.md#idempotency) | `ConsumerOptions.idempotent(store)` |
| A retry ladder for this queue only | `ConsumerOptions.withRetry(policy)` |
| A codec for this queue only | `ConsumerOptions.as(codec)` |

All three are `ConsumerOptions`, and the registry that starts annotated listeners builds those
from `acemq.listener.*` alone — which is a property file, and none of the three is a value a
property file can hold. [Idempotency](reliability.md#idempotency) has the bean form in full, and
[Patterns](patterns.md) is the map of which patterns need it.

## Starting and stopping

Listeners are started by `AceListenerRegistry`, a `SmartLifecycle`, after the rest of the
context is built. Starting a consumer while the beans it calls are still being created is
how a message arrives at a half-built service, and it happens on exactly the deployments
where the queue already has a backlog.

On shutdown the registry drains every listener before closing any of them:

```
WARN o.a.s.b.AceListenerRegistry : listener orders still had 3 message(s) in flight after PT30S; they will be redelivered
```

Draining is not the same as emptying the queue. A listener stopped with a backlog leaves the
backlog; what it drains is the messages already inside handlers. Everything else stays on
the queue.

## A queue that does not exist

A listener on a queue nobody created is caught as the application starts, not at the
broker's first refusal:

```
@AceListener orders (bean 'orderListener', method onOrder) listens on queue 'orders.nwe', which does not exist
Declare the queue under acemq.topology.queues, create it on the broker, or set acemq.listener.missing-queue=warn to start without these listeners.
```

The check runs when listeners start, which is after the declared topology has been applied
and after every bean that declares a queue in its own initialisation has run. A queue under
`acemq.topology.queues` is taken as provided for and not asked about — unless
`acemq.topology.apply` is `dry-run`, which creates nothing. Every other queue is asked about
passively: nothing is created, and an existing queue is not changed whatever its type or
arguments. Every missing queue is named in the one failure, so three typos cost one restart.

With `acemq.listener.missing-queue: warn` the application starts, each missing queue is
logged as a warning, and those listeners stay stopped. Start one later with
`registry.start("orders")`, or through the [actuator endpoint](observability.md#the-acemq-endpoint);
both check the queue again and refuse while it is still missing.

A listener with `autoStartup = "false"` is not checked until it is started.

## The registry

Inject `AceListenerRegistry` to reach a running listener:

```java
@Service
class OrderThrottle {

    private final AceListenerRegistry listeners;

    OrderThrottle(AceListenerRegistry listeners) {
        this.listeners = listeners;
    }

    void slowDown() {
        listeners.get("orders").ifPresent(group -> {
            group.scaleTo(1);
            group.prefetch(10);
        });
    }

    long acknowledged() {
        return listeners.get("orders").map(ConsumerGroup::acknowledged).orElse(0L);
    }
}
```

`get(id)` returns the library's own `ConsumerGroup`, which is what carries `scaleTo`, `prefetch`,
`drainTimeout`, `drain`, `close` and the counters — `acknowledged`, `rejected`, `retried`,
`inFlight`, plus `queue`, `size` and `prefetch` as readers. It is a library type rather than a
wrapper of this starter's, because a wrapper would be one more thing to keep in step.

Two things are not on it, and are worth knowing before looking for them.

**Pausing.** A group has no pause of its own. `mq.pauseConsuming()` and `mq.resumeConsuming()`
stop and start every consumer on the connection at once, which is the right tool for shedding
load and the wrong one for quietening a single noisy listener. `scaleTo(1)` is the floor —
`scaleTo(0)` is refused with "a group needs at least one consumer" — so the way to stop one
listener entirely is `registry.stop("id")`, and `registry.start("id")` to resume it.

Note also that scaling **down** blocks: the consumers being removed are drained first, up to
`drainTimeout`. A `scaleTo` that returned instantly while abandoning work in the background would
be the more convenient lie.

**Dead letters.** Not a group counter. `acemq.messages.dead.lettered.total`, tagged by queue, is
the [metric](observability.md#metrics), and `mq.messageCount("orders.new.dlq")` is the depth —
see [Dead letters](reliability.md#dead-letters).

Every listener is a group, even at concurrency one. One type means one set of counters and
one shutdown path, and a group of one costs nothing.

A listener registered with `autoStartup = "false"` is not running; `registry.start("id")`
starts it. `registry.stop("id")` stops one — draining it first, as shutdown does — and
`start` brings it back. The [`acemq` actuator endpoint](observability.md#the-acemq-endpoint)
calls the same two methods.

## There is no template

The publishing side has no annotation and no `AceTemplate`. Inject `AceMq`:

```java
mq.publisher("orders", "order.created", Order.class).send(order);
```

A template wrapping that would be a second API to document, a second one to keep in step
with the first, and the place where a starter starts making decisions the library
deliberately left to the caller — which codec, which options, whether the send is
synchronous. The publisher is already small, already typed, and already documented in the
library's own guide.

[Publishing](publishing.md) is the page: keeping a publisher as a bean, what `send` returns,
batches, and what happens when the broker stops reading.
