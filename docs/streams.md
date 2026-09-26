# Streams

A RabbitMQ stream is an append-only log. Reading does not remove anything, several readers can
be at different places in it at once, and a reader can start again from the beginning. That is
a different data structure from a queue, and it solves different problems:

| A queue | A stream |
|---|---|
| A message is delivered once and gone | A message stays until it ages out |
| Two consumers share the work | Two consumers each see everything |
| "Handle this" | "Here is what happened" |
| No history | The history *is* the queue |

Use a stream for an event log several services project from, for a late-joining consumer that
needs the last hour rather than only what arrives next, and for anything you want to be able to
re-read after a bug. Use a queue for work.

## What the starter does and does not do

Be clear about this before writing any code, because two of the starter's conveniences do not
apply.

| | |
|---|---|
| `acemq.topology.queues[].type` | `quorum` or `classic` only. **There is no `stream`** |
| `@AceListener` | Consumes a queue. **It cannot read a stream** |
| The `AceMq` bean, `Telemetry`, `Codec`, health | All apply normally |

Neither omission is an oversight, and both have the same cause: a stream has no useful default
offset. A queue consumer starts where the queue is, because there is nowhere else to start. A
stream reader has to say whether it wants the whole history, only what arrives next, or
wherever it got to last time — and that answer is the application's, is usually read from a
database, and does not belong in a properties file. An annotation with a mandatory attribute
that can only be answered in code is worse than no annotation.

So a stream is declared and read in a `@Bean`. Everything else the starter provides still
holds: the connection, its codec, its telemetry, its health indicator and its shutdown
ordering.

## Declaring one

```java
@Configuration
class OrderLog {

    static final String LOG = "orders.log";

    /**
     * Declared once, at startup, alongside whatever acemq.topology declares. Retention is
     * mandatory in the sense that a stream without it grows until the disk is full.
     */
    @Bean
    InitializingBean orderLogStream(AceMq mq) {
        return () -> mq.declareStream(LOG, Duration.ofDays(7), 20L * 1024 * 1024 * 1024);
    }
}
```

`declareStream(name, maxAge, maxLengthBytes)` — either bound may be null, and at least one of
them should not be. A stream keeps everything until one of its limits is reached and then drops
from the oldest end; with neither limit set it keeps everything for ever, which is a disk
alarm with a delay on it.

There is a four-argument form that also sets the segment size. Leave it alone unless you have
measured a reason: segments are the unit the broker deletes in, so a very large segment means
retention is enforced in very large steps.

`InitializingBean` rather than `@PostConstruct` on a `@Component` so that the declaration is
ordered after the `AceMq` bean by the dependency rather than by luck. `AceMqTopologyInitializer`
does the same thing for `acemq.topology`.

## Reading one

```java
@Configuration
class OrderProjections {

    @Bean(destroyMethod = "close")
    StreamConsumer statementProjection(AceMq mq, Statements statements) {
        return mq.stream(OrderLog.LOG, OrderPlaced.class)
                 .fromFirst()
                 .prefetch(200)
                 .consume(message -> statements.apply(message.payload()));
    }
}
```

`mq.stream(name, type)` returns a `StreamReader<T>`, which is a builder: choose where to start,
then `consume(handler)` to start reading. The handler is the same `MessageHandler<T>` a queue
consumer takes, so it sees a `Message<OrderPlaced>` with the payload and the envelope.

`destroyMethod = "close"` stops the reader when the context is destroyed. A stream reader is not
in `AceListenerRegistry`, so it is not part of the registry's ordered drain — see
[shutdown](#shutdown) below.

### Where to start

| | |
|---|---|
| `.fromFirst()` | The beginning of the stream, whatever is still retained |
| `.fromLast()` | The last message already in the stream, then forwards |
| `.fromNext()` | Only messages that arrive after this reader attaches |
| `.fromOffset(long)` | An exact offset. This is the resume case |
| `.from(Instant)` | The first message at or after a timestamp |
| `.fromLast(Duration)` | Everything from the last hour, say |

`.from(StreamOffset)` takes the same thing as a value, which is what to use when the choice is
computed rather than written: `StreamOffset.first()`, `.last()`, `.next()`, `.at(offset)`,
`.from(instant)`, `.lastly(age)`.

**The default, if you call none of them, is `next()`** — only what arrives after the reader
attaches. It is the one default that cannot lose data by re-reading, and it is very rarely what
a projection wants. Say which you mean.

**These are not interchangeable and the wrong one is a quiet bug.** A projection that must be
correct wants `fromFirst()` on its first run and `fromOffset(...)` thereafter — `fromNext()`
would silently skip whatever arrived while it was being deployed. An alerting consumer that only
cares about now wants `fromNext()`, and `fromFirst()` would replay a week of history through it
on every restart and page somebody about last Tuesday.

## Resuming: offsets are yours to keep

This is the part that catches people. The library does not store your offset. There is no
consumer-group concept doing it for you, and a reader restarted without being told where to
start does not resume — it takes the default, `next()`, and silently skips everything that
arrived while it was down.

There are two places to read the offset from, and they suit different checkpointing strategies.

**`StreamConsumer.lastHandledOffset()`** is the offset of the last message the handler returned
normally for. It is an `OptionalLong`, empty until the first message. This is the supported
accessor, and it is read from outside the handler — by a scheduled flush, or at shutdown.

**The `x-stream-offset` header**, for a handler that wants its own offset while it is running.
The broker sets it on every delivery from a stream, and it reaches the handler as an ordinary
application header:

```java
static long offsetOf(Message<?> message) {
    Object offset = message.headers().get("x-stream-offset");
    if (!(offset instanceof Number)) {
        throw new IllegalStateException(
                "no x-stream-offset on a delivery from " + message.queue()
                        + "; this is not a stream");
    }
    return ((Number) offset).longValue();
}
```

It is a `Number` rather than a `Long` because the AMQP client chooses the narrowest integer that
fits, so the cast has to go through `Number`. It is not part of the library's own API — it is
RabbitMQ's header, passed through untouched — so a version that stops setting it would not be a
break in this library. `lastHandledOffset()` is the safer of the two if either will do.

With the header, a projection can checkpoint inside its own transaction:

```java
@Configuration
class OrderProjections {

    /**
     * Resumes from the offset the last run got to, and reads the whole stream the first time.
     * The checkpoint goes into the same database as the projection, in the same transaction —
     * two stores mean a crash between them replays or skips.
     */
    @Bean(destroyMethod = "close")
    StreamConsumer statementProjection(AceMq mq, Statements statements, Checkpoints checkpoints) {
        StreamReader<OrderPlaced> reader = mq.stream(OrderLog.LOG, OrderPlaced.class);

        StreamReader<OrderPlaced> positioned = checkpoints.offsetOf("statements")
                .map(last -> reader.fromOffset(last + 1))
                .orElseGet(reader::fromFirst);

        return positioned.consume(message ->
                statements.applyAndCheckpoint(message.payload(), offsetOf(message)));
    }
}
```

`last + 1`, not `last`. The offset you stored is the one you *finished*; starting there again
hands the handler that message a second time.

`applyAndCheckpoint` is a single `@Transactional` method on `Statements` — that is the whole
point of it being one method rather than two calls from here.

**Checkpoint in the same transaction as the work.** A projection that applies a row and then
writes its offset in a second transaction will, when it crashes between them, redo the row on
restart. That is fine if the projection is idempotent and a duplicated statement line if it is
not. If the two genuinely cannot be one transaction, make the handler idempotent instead — see
[Idempotency](reliability.md#idempotency) — and checkpoint afterwards.

How often to checkpoint is a throughput decision. Every message is one write per message; every
thousand means re-reading up to a thousand after a crash. Both are correct; only the second is
fast.

## When a handler throws

By default the reader **stops**. `StreamConsumer.isRunning()` goes false and
`stoppedBy()` holds the exception.

That is the opposite of a queue consumer's default, and it is right for a log: a stream is
ordered, and a projection that skips the message it could not handle and carries on is a
projection that is now wrong, silently, for ever. Stopping keeps the offset at the last message
that did work, so the run can be resumed after a fix.

Which means **you have to watch it**, because a stopped reader is not an exception anywhere:

```java
@Bean
HealthIndicator statementProjectionHealth(StreamConsumer statementProjection) {
    return () -> statementProjection.isRunning()
            ? Health.up()
                    .withDetail("handled", statementProjection.handled())
                    .withDetail("offset", statementProjection.lastHandledOffset().orElse(-1))
                    .build()
            : Health.down()
                    .withDetail("stoppedBy", statementProjection.stoppedBy()
                            .map(Throwable::toString).orElse("unknown"))
                    .withDetail("offset", statementProjection.lastHandledOffset().orElse(-1))
                    .build();
}
```

A `HealthIndicator` bean is picked up by Actuator and appears under `/actuator/health`
alongside the starter's own — and a reader that stopped at three in the morning is then an
alert rather than a projection that is four hours stale for reasons nobody can see. Note the
import: `org.springframework.boot.actuate.health.HealthIndicator` on Boot 3 and
`org.springframework.boot.health.contributor.HealthIndicator` on Boot 4. That split is the
reason the starter ships [two health modules](index.md#one-artifact-both-boot-lines), and an
application only ever compiles against its own line.

`.skipFailures()` is the other behaviour: log the failure, count it in `skipped()`, and carry
on. Correct for a consumer where a bad message is genuinely skippable — a metrics feed, a
best-effort cache warm — and wrong for anything where the log is the source of truth.

```java
mq.stream(LOG, OrderPlaced.class).fromNext().skipFailures().consume(handler);
```

## What a reader reports

```java
consumer.queue();              // the stream
consumer.isRunning();
consumer.handled();            // handled without throwing
consumer.failed();
consumer.skipped();            // only ever non-zero with skipFailures()
consumer.lastHandledOffset();  // OptionalLong; empty before the first message
consumer.stoppedBy();          // Optional<Throwable>
```

One subtlety under `skipFailures()`: a skipped offset advances `lastHandledOffset()` too. That
is what makes the checkpoint usable — a resume must not come back to a message the reader has
already decided to skip — but it means the name is "last offset dealt with" rather than "last
offset handled successfully". `skipped()` is how you find out there is a gap, and the reader
logs each one at WARN because nothing else will ever report it.

`handled()` and `lastHandledOffset()` together answer "is this projection keeping up", which is
the question a stream consumer's dashboard exists for. There is no built-in lag metric: the
stream's head offset is not something a reader is told, so lag has to be derived from a
publisher-side counter or from message timestamps.

## Broker support

Streams need RabbitMQ 3.9 or later with the stream plugin enabled. Ask rather than assume:

```java
if (!mq.supports(Capability.STREAMS)) {
    throw new IllegalStateException(
            "this broker has no stream support; enable rabbitmq_stream on it");
}
```

`mq.capabilities()` is the whole set. Worth an assertion at startup in an application whose
brokers differ between environments — the failure otherwise arrives as a declaration error
partway through a deployment.

## Shutdown

A stream reader declared as a bean with `destroyMethod = "close"` is closed during context
destruction, which is *after* every `SmartLifecycle` has stopped and therefore after the
annotated listeners have drained. For most readers that is fine: the handler either finished or
it did not, and the offset says which.

When a reader must stop before the rest of the application does — a projection writing through
a `DataSource` that Spring is about to close, say — make it a `SmartLifecycle` instead, and give
it a phase below `AceListenerRegistry`'s:

```java
@Component
class StatementProjection implements SmartLifecycle {

    private final AceMq mq;
    private final Statements statements;
    private volatile StreamConsumer consumer;

    StatementProjection(AceMq mq, Statements statements) {
        this.mq = mq;
        this.statements = statements;
    }

    @Override
    public void start() {
        consumer = mq.stream(OrderLog.LOG, OrderPlaced.class)
                     .fromFirst()
                     .consume(message -> statements.apply(message.payload()));
    }

    @Override
    public void stop() {
        StreamConsumer running = consumer;
        if (running != null) {
            running.close();
            consumer = null;
        }
    }

    @Override
    public boolean isRunning() {
        StreamConsumer running = consumer;
        return running != null && running.isRunning();
    }

    /** Below the registry's DEFAULT_PHASE, so this stops before the queue listeners do. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
```

A higher phase stops earlier, which is why `AceListenerRegistry` sits at `DEFAULT_PHASE` —
`Integer.MAX_VALUE` — and stops first.

## Testing

`InMemoryTransport` does not implement streams, so a stream test needs a broker. That is a
Testcontainers test rather than a context test; [Testing](testing.md) has the shape, and the
Testcontainers version this family pins.
