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

`get(id)` returns the library's own `ConsumerGroup`, which is what carries `scaleTo`,
`prefetch`, `pause`, `resume`, `drain` and the counters — acknowledged, rejected, retried,
dead-lettered, in flight. It is a library type rather than a wrapper of this starter's,
because a wrapper would be one more thing to keep in step.

Every listener is a group, even at concurrency one. One type means one set of counters and
one shutdown path, and a group of one costs nothing.

A listener registered with `autoStartup = "false"` is not running; `registry.start("id")`
starts it.

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
