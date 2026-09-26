# Retries, dead letters and replay

What happens to a message a handler cannot handle, and how it gets another chance.

Four things, in the order a service usually needs them:

1. **Retries** — try again, later, without blocking the consumer. Properties.
2. **Dead letters** — stop trying, and keep the message somewhere it can be looked at. One
   property.
3. **Replay** — send the kept messages back through once the cause is fixed. A bean.
4. **Idempotency** — handle a redelivery without doing the work twice. A bean.

## Retries

The ladder is properties, and [Listeners](listeners.md#when-a-handler-throws) has the
arithmetic for why it is not a `Thread.sleep`:

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
      give-up-after: 10m
```

`max-attempts` counts deliveries, the first one included, so five attempts means four waits:
2s, 4s, 8s, 16s — each spread by up to twenty per cent, and none of them reaching the 30s
ceiling, which only starts biting at seven attempts. The message is abandoned outright once it
is ten minutes old however many attempts are left. `give-up-after` is the
one people leave off and then want: without it, a message published during an outage keeps
its full ladder and is still being retried long after the batch it belonged to is irrelevant.

Every `@AceListener` in the application shares this ladder. It is connection-level
configuration, not per-listener — see [the limits](#what-the-annotation-cannot-reach) for what
to do when one queue genuinely needs a different one.

### Two exceptions the ladder treats differently

```java
@AceListener(queue = "payments.new")
void onPayment(Payment payment) {
    if (payment.amount() < 0) {
        throw new AceFatalException("a payment cannot be negative: " + payment.amount());
    }
    gateway.take(payment);   // throws something transient; the ladder handles it
}
```

`AceFatalException` means *do not retry this* — the message is wrong, and a fifth attempt
will be wrong in the same way. It goes straight to the dead-letter queue on the first attempt
instead of occupying the ladder for ten minutes first.

`AceRetryableException` is the opposite statement, made explicitly. Any other exception is
retried too, so it is only worth throwing when the intent needs to be visible in the code.

The distinction is the one thing worth taking from this page into a handler. A validation
failure and an unreachable gateway are both "the handler threw", and treating them the same
means either the invalid message is retried five times or the gateway is given one attempt.

### The delays are visible

```java
RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(2), Duration.ofSeconds(30));
policy.schedule();      // [PT2S, PT4S, PT8S, PT16S] — one per retry, so maxAttempts - 1
policy.brokerRungs();   // the delays long enough to wait in the broker rather than in memory
```

Useful in a test, and useful for answering "how long before this message is dead-lettered"
without reading the implementation. `RetryPolicy.fixed(3, Duration.ofSeconds(1))` and
`RetryPolicy.none()` are the other two constructors.

Doubling and twenty per cent jitter are a cross-language default rather than a taste: the same
policy has to produce the same numbers in Go, .NET, Python and Ruby, because the same message
can be retried by a consumer written in any of them. A message that waited one second under
one library and five under another has no schedule at all.

`brokerRungs()` is worth knowing about if you ever look at the broker. A delay short enough to
hold in memory is held there; a longer one — `waitInBrokerFrom(...)`, thirty seconds by
default — is spent in a rung queue on the broker instead, so a consumer restart does not lose
it and a ten-minute backoff does not pin a thread.

## Dead letters

A message that cannot be handled has to go somewhere, and there are two quite different routes
to it. Getting this wrong is the single most common way a message is lost quietly, so it is
worth separating them.

**The library's route.** A consumer started with a retry ladder declares what the ladder needs
before it consumes anything: the rung queues, a retry exchange, `acemq.dlx`, and
`<queue>.dlq` and `<queue>.parked` bound to it. When the ladder gives up, the consumer
publishes the message to `<queue>.dlq` itself. Nothing needs declaring for that to work —
turning on `acemq.listener.retry.enabled` is enough.

**The broker's route.** A message the library never gets to see — one that expires against an
`x-message-ttl`, or one a handler rejects with no ladder configured at all — is dead-lettered
by the broker, and only if the queue was declared with `x-dead-letter-exchange`. A consumer
deliberately does not add that argument: it does not own the source queue, does not know
whether it was meant to be quorum or classic, and a redeclaration that guesses wrong is a
`PRECONDITION_FAILED` that stops the consumer starting at all.

So the argument has to come from wherever the queue is declared, which for this starter means
`application.yml`:

```yaml
acemq:
  topology:
    exchanges:
      - { name: payments, type: topic }
    queues:
      - { name: payments.new, dead-letter: true }
    bindings:
      - { queue: payments.new, exchange: payments, routing-key: payment.* }
```

That one line declares four things, because they are only correct together:

| | |
|---|---|
| `payments.new` | with `x-dead-letter-exchange: acemq.dlx` and `x-dead-letter-routing-key: payments.new.dlq` |
| `acemq.dlx` | a direct exchange, shared by every dead-lettering queue in the topology |
| `payments.new.dlq` | a message that was understood and could not be processed |
| `payments.new.parked` | a message that could not even be decoded |

A queue pointed at a dead-letter exchange nothing declares, or at one with no queue bound to
it, throws messages away exactly as if dead-lettering had never been configured — which is
why this is one property rather than four blocks of YAML.

The names are the same ones the ladder uses, so the two routes converge: a give-up and an
expiry both end up in `payments.new.dlq`, and one replay drains both. That is not a
coincidence — `.dlq` and `.parked` are the cross-language convention, and the Go, .NET, Python
and Ruby libraries name them identically.

**`.dlq` and `.parked` are separate on purpose.** A payment that failed because the gateway
returned 500 and a payment whose JSON does not parse are investigated differently and fixed
differently. Mixed into one queue, whoever drains it sorts them by hand.

`dead-letter: true` works on a classic queue too, and keeps the arguments you give it:

```yaml
queues:
  - { name: orders.audit, type: classic, dead-letter: true, arguments: { x-message-ttl: 604800000 } }
```

Setting `x-dead-letter-exchange` yourself in `arguments` alongside `dead-letter: true` is
refused rather than overruled. Either answer would be a guess about which of two conflicting
instructions was meant.

### Seeing what is in there

```java
@Service
class DeadLetters {

    private final AceMq mq;

    DeadLetters(AceMq mq) {
        this.mq = mq;
    }

    long waiting() {
        return mq.messageCount("payments.new.dlq");
    }

    long undecodable() {
        return mq.messageCount("payments.new.parked");
    }
}
```

Worth a gauge. A dead-letter queue that is normally empty and is now not is the earliest
signal that something downstream changed, and it is a number nobody looks at unless it is on
a dashboard.

```java
@Bean
MeterBinder deadLetterDepth(AceMq mq) {
    return registry -> Gauge.builder("orders.dlq.depth", () -> mq.messageCount("payments.new.dlq"))
            .description("messages the payments handler gave up on")
            .register(registry);
}
```

## Replay

Once the cause is fixed, the dead letters need to go back. `mq.replay(queue)` moves them from
the dead-letter queue to the queue they came from.

```java
@Service
class PaymentReplay {

    private final AceMq mq;

    PaymentReplay(AceMq mq) {
        this.mq = mq;
    }

    /** How many are waiting, and where they would go. */
    long pending() {
        return mq.replay("payments.new").pending();
    }

    /** Everything. */
    int replayAll() {
        return mq.replay("payments.new").replayAll();
    }

    /** A hundred at a time, which is how a large backlog is done safely. */
    int replaySome() {
        return mq.replay("payments.new").replay(100);
    }

    /** Only the ones a fix actually covers. */
    int replayGatewayFailures() {
        return mq.replay("payments.new")
                 .replay(500, delivery -> delivery.headers()
                         .getOrDefault(AceHeaders.ERROR, "")
                         .toString()
                         .contains("gateway"));
    }
}
```

`mq.replay(q)` reads from `q.dlq` and publishes back to `q`; `from()` and `to()` say so, which
is worth asserting in a test rather than trusting. `.parked()` switches the source to
`q.parked` instead, for the messages that could not be decoded — after the codec is fixed
rather than after the downstream is.

A replayed message arrives with its attempt counter **reset**, so it gets the full ladder
again. That is the right default: the reason it is being replayed is that the previous
attempts were against a broken downstream and tell you nothing. `.keepingAttempts()` is there
for the case where they do — a poison message being given exactly one more chance.

The envelope records the replay: `replayedFrom()`, `replayedAt()` and `replayCount()`. A
handler can see that it is looking at a second pass, and a message with a replay count of
four has been through this loop four times and is not going to succeed on the fifth.

**Expose this deliberately.** A replay endpoint is a button that republishes production
traffic, and `replayAll()` on a queue with 400,000 messages in it is a self-inflicted
incident. An actuator endpoint behind an authenticated role, or a scheduled job with a bound,
rather than an open `POST /replay`.

## Idempotency

A message can be delivered twice. Not often, but by design: a redelivery after a consumer
died mid-handler, a retry whose original did in fact succeed, a replay somebody ran twice.
Any handler with a side effect needs to survive it.

An `IdempotencyStore` remembers the message ids it has completed and drops the duplicates
before the handler sees them.

```java
@Configuration
class Idempotency {

    /** Shared by every instance, which is the point — two pods are two consumers. */
    @Bean
    IdempotencyStore idempotencyStore(DataSource dataSource) {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource);
        store.createSchemaIfAbsent();
        return store;
    }
}
```

`new JdbcIdempotencyStore(dataSource, retention, claimTimeout, table)` is the constructor
with the knobs: how long a completed id is remembered, how long a claim is held before it is
assumed abandoned, and which table.

`InMemoryIdempotencyStore.forOneDay()` is the other implementation. It is correct for exactly
one process, so it is right for a test and for a single-instance job, and wrong for anything
that scales out — two replicas with in-memory stores deduplicate independently, which is to
say not at all.

### What the annotation cannot reach

`@AceListener` does not take an idempotency store, and neither
`acemq.listener.*` nor the annotation has a property for one. The store is applied through
`ConsumerOptions.idempotent(...)`, and the registry that starts annotated listeners builds
those options from properties alone.

So a consumer that needs one is declared as a bean instead of as an annotation:

```java
@Configuration
class PaymentConsumers {

    @Bean(destroyMethod = "close")
    ConsumerGroup payments(AceMq mq, IdempotencyStore store, PaymentHandler handler) {
        return mq.consumeGroup("payments.new", Payment.class, handler::handle)
                 .concurrency(4)
                 .prefetch(40)
                 .options(ConsumerOptions.prefetch(40)
                         .idempotent(store)
                         .withRetry(RetryPolicy.exponential(
                                 5, Duration.ofSeconds(2), Duration.ofSeconds(30))))
                 .start();
    }
}
```

This is the general escape hatch, and it is worth being clear that it is not a workaround.
`@AceListener` covers the common case from properties; a bean covers everything, because it
is the library's own API with nothing in the way. The costs of the bean form are real but
small: it is not in `AceListenerRegistry`, so `registry.get(id)` will not find it and the
registry's ordered drain does not cover it — `destroyMethod = "close"` closes it when the
context is destroyed, which is after the annotated listeners have drained.

The same applies to a per-listener retry ladder, a per-listener codec
(`ConsumerOptions.as(codec)`), and `requeueOnFailure` for one queue but not the others. All
three are `ConsumerOptions`, and all three mean declaring that consumer as a bean.

`handler::handle` above is a `MessageHandler<Payment>`, which takes `Message<Payment>` and
returns nothing. `AckAwareHandler`, which returns an `Ack` and so can dead-letter or delay a
single message by hand, is not reachable from `consumeGroup` either — it is an interface the
library exposes and does not yet dispatch to.

## Graceful shutdown

A message inside a handler when the process exits is redelivered, and a rolling deployment
that does not wait produces a burst of duplicates that nobody attributes to the deployment.

```yaml
acemq:
  listener:
    shutdown-timeout: 45s
```

`AceListenerRegistry` is a `SmartLifecycle` at the default phase, so it stops first: every
listener drains, then every listener closes, then — after every lifecycle has stopped — the
`AceMq` bean is destroyed and the connection closes. Draining means waiting for the messages
already inside handlers; the backlog on the queue stays on the queue.

Set the timeout above the longest a handler can take, and set your orchestrator's
`terminationGracePeriodSeconds` above that. A 45-second drain inside a 30-second grace period
is a SIGKILL partway through the drain, which is the thing the drain existed to prevent.

Nothing needs to be called by hand. `mq.drainConsumers(timeout)` exists for a consumer
declared as a bean outside the registry, and for a test that wants to assert the drain.
