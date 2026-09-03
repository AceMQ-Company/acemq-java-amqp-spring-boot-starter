# Tutorial 3: when the handler fails

Twenty-five minutes. Continues from [Tutorial 2](tutorial-topology.md).

By the end: a handler that fails on purpose, a retry ladder that does not block the consumer,
and a measured demonstration of why the obvious alternative is expensive.

## A handler that fails

```java
@Component
class OrderListener {

    private static final Logger log = LoggerFactory.getLogger(OrderListener.class);
    private final AtomicInteger failures = new AtomicInteger();

    @AceListener(queue = "orders.new")
    void onOrder(Message<Order> message) {
        Order order = message.payload();
        log.info("order {} attempt {}", order.id(), message.attempt());

        // Fails twice, then succeeds: a downstream that is briefly unavailable.
        if (order.quantity() > 0 && failures.getAndIncrement() % 3 != 2) {
            throw new IllegalStateException("downstream unavailable");
        }
        log.info("order {} handled on attempt {}", order.id(), message.attempt());
    }
}
```

Taking `Message<Order>` rather than `Order` is what gives the handler `attempt()`.

Restart and post an order:

```
INFO c.e.orders.OrderListener : order A-1 attempt 1
ERROR ... handler failed, message rejected
```

One attempt, and the message is gone: rejected, with no dead-letter exchange on the queue to
catch it. The queue count in the management UI is back to zero and nothing has been handled.

That is the default, and it is the honest one — a starter that retried by default would be
retrying on behalf of somebody who never asked, against a downstream that may not be
idempotent.

## The ladder

```yaml
acemq:
  listener:
    retry:
      enabled: true
      max-attempts: 5
      initial-delay: 2s
      max-delay: 30s
      multiplier: 2.0
      jitter: 0.2
```

Restart, post an order, and watch the timestamps:

```
10:41:03.114 INFO  order A-2 attempt 1
10:41:05.402 INFO  order A-2 attempt 2
10:41:09.887 INFO  order A-2 attempt 3
10:41:09.889 INFO  order A-2 handled on attempt 3
```

Two seconds, then four, with jitter spreading each one by up to 20%. The ladder is
2s → 4s → 8s → 16s → 30s (the ceiling), and `give-up-after` can end it early for a message
that is simply too old to matter.

## What the ladder is not doing

It is not sleeping. Between 10:41:03 and 10:41:05 the consumer is free, and any other
message on the queue is handled in that gap. The message being retried is **republished with
a delay** and an incremented attempt count; it is not held.

Watch it happen. Add a second listener on the same queue in a separate bean, or just post
two orders a second apart with the ladder on: the second order is handled while the first is
waiting for its next attempt.

### The alternative, measured

Write the retry by hand instead:

```java
@AceListener(queue = "orders.new")
void onOrder(Order order) throws InterruptedException {
    for (int attempt = 1; attempt <= 5; attempt++) {
        try {
            downstream.call(order);
            return;
        } catch (TransientFailure e) {
            Thread.sleep(2000L * attempt);   // do not do this
        }
    }
}
```

With `prefetch: 100` and one consumer, that sleep blocks the channel. The hundred messages
already delivered into that consumer's buffer are unavailable to any other consumer — they
are not on the queue any more, they are in a buffer behind a sleeping thread. Four attempts
of backoff is 20 seconds during which the queue looks busy and nothing moves, and the
messages cannot be picked up by an instance that is idle.

The ladder costs a republish per attempt. The sleep costs the throughput of everything
prefetched behind it.

## Where a message goes when the ladder gives up

After `max-attempts`, the message is dead-lettered. With no dead-letter exchange on the
queue there is nowhere to put it, and it is dropped — so declare one.

The queue argument is `x-dead-letter-exchange`, which means a classic queue in the topology
block, or a policy for a quorum queue. For this tutorial, use a classic queue so it can be
declared in one place:

```yaml
acemq:
  topology:
    exchanges:
      - { name: orders, type: topic }
      - { name: orders.dlx, type: fanout }
    queues:
      - name: orders.new
        type: classic
        arguments:
          x-dead-letter-exchange: orders.dlx
      - { name: orders.dead }
    bindings:
      - { queue: orders.new,  exchange: orders,     routing-key: order.created }
      - { queue: orders.dead, exchange: orders.dlx, routing-key: "" }
```

`orders.new` already exists as a quorum queue from Tutorial 1, and RabbitMQ will not change
it. Delete it in the management UI first, and note that this is exactly the migration
problem [Tutorial 2](tutorial-topology.md#why-it-is-not-corrected) described: in production
the answer is a new queue name, not a delete.

Now make the handler fail permanently:

```java
throw new IllegalStateException("downstream permanently broken");
```

Post an order and watch: five attempts, spread over the ladder, then

```
WARN ... message dead-lettered after 5 attempts
```

and one message in `orders.dead`, carrying the headers that say where it came from and why.

## Reading the ladder from inside the handler

`message.attempt()` is how a handler behaves differently on a later attempt:

```java
@AceListener(queue = "orders.new")
void onOrder(Message<Order> message) {
    if (message.attempt() > 3) {
        // Stop calling the flaky downstream; park it for a human instead.
        parking.save(message.payload(), message.envelope().error().orElse("unknown"));
        return;                      // acknowledged, handled our way
    }
    downstream.call(message.payload());
}
```

Returning normally acknowledges, whatever attempt it is. That is the escape hatch for the
message that should not go round again.

## The other two settings

**`requeue-on-failure: true`** puts the message back on the queue immediately. It is almost
never what you want: an immediate retry against a downstream that is down is a hot loop, and
the message goes to the front of the queue rather than the back. It exists because there are
cases — a handler that failed on a transient local resource — where it is right.

**`give-up-after`** ends the ladder for a message older than a duration, whatever attempt it
is on. For anything with a business deadline, that is the more honest limit: a payment
authorisation retried for six hours is worse than one dead-lettered after ten minutes.

Next: [health and metrics](tutorial-observability.md), including the metric that counts
every retry this tutorial just produced.
