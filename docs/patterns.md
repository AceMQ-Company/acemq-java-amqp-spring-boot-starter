# Patterns

Everything the library does, and how much of it `application.yml` can reach.

The starter is [a thin layer](index.md#what-it-is): it builds beans from properties and hands you
the library's own `AceMq`. So every pattern below is available to a Boot application — but they
divide into three groups, and knowing which group you are in saves a search for a property that
does not exist.

## The three groups

**Properties.** Configured entirely from `application.yml`, with no Java beyond a handler.

| Pattern | Where |
|---|---|
| Consume a queue | [`@AceListener`](listeners.md) |
| Topology: exchanges, queues, bindings, drift | [Topology](topology.md) |
| Retries — the non-blocking ladder | [Retries](reliability.md#retries) |
| Dead letters, `.dlq` and `.parked` | [Dead letters](reliability.md#dead-letters) |
| TLS, credentials, development certificates | [Security](security.md) |
| Serialization format | [Formats](serialization.md#formats) |
| Health, metrics | [Observability](observability.md) |
| Graceful shutdown | [Shutdown](reliability.md#graceful-shutdown) |
| Publisher confirms, back pressure, blocked connections | [Publishing](publishing.md#back-pressure-and-blocked-connections) |

**A bean that replaces an auto-configured one.** Change the behaviour of everything on the
connection at once. Each of these backs off with `@ConditionalOnMissingBean`, so declaring yours
is the whole mechanism.

| Pattern | The bean to declare |
|---|---|
| [Payload encryption](security.md#payload-encryption) | `Codec` |
| [Claim check](serialization.md#claim-check) | `Codec` |
| [Avro with a schema registry](serialization.md#avro-and-the-schema-registry) | `Codec` |
| [Tracing](observability.md#tracing) | `Telemetry` |
| [Rotating credentials](security.md#credentials-that-change-while-the-application-is-running) | `ConnectionConfig` |

**A bean of its own.** The pattern is a library object the starter has no opinion about. Declare
it, take `AceMq` as a parameter, and give it `destroyMethod = "close"` if it holds anything.

| Pattern | The type |
|---|---|
| [Publishing](publishing.md#a-publisher-is-worth-keeping) | `Publisher<T>` |
| [Idempotent consumer](reliability.md#idempotency) | `IdempotencyStore` + a `ConsumerGroup` |
| [Replay](reliability.md#replay) | `Replay`, per call |
| [Request and reply](messaging-patterns.md#request-and-reply) | `Requester`, `Responder` |
| [Scheduling](messaging-patterns.md#scheduling) | `Scheduler` |
| [Transactional outbox](messaging-patterns.md#transactional-outbox) | `OutboxStore` + `OutboxRelay` |
| [Saga](messaging-patterns.md#saga) | `Saga<T>`, built where the steps are |
| [Pipelines](messaging-patterns.md#pipelines) | `Pipeline<T>` |
| [Ordered per key](messaging-patterns.md#ordered-per-key) | `OrderedQueue<T>` |
| [Routing slips](messaging-patterns.md#routing-slips) | `Itinerary`, per message |
| [Streams](streams.md) | `StreamConsumer` |
| [Interceptors](interceptors.md) | `PublishInterceptor`, `ConsumeInterceptor` |

## Four things the annotation cannot do

Worth stating plainly, because the natural first move is to look for an attribute.

**`@AceListener` cannot take an idempotency store.** The registry builds `ConsumerOptions` from
properties, and there is no property for a store because a store is a bean. Declare the consumer
as a `ConsumerGroup` bean instead — [Idempotency](reliability.md#idempotency) has it.

**`@AceListener` cannot have its own retry ladder or codec.** Same reason, same answer. The
ladder in `acemq.listener.retry.*` applies to every annotated listener in the application.

**`@AceListener` cannot read a stream.** A stream reader has to say where in the log to start,
and that answer usually comes from a database. [Streams](streams.md) explains why an annotation
would be worse than a bean here.

**There is no return value that acknowledges.** The library has an `AckAwareHandler` that returns
an `Ack`, and nothing in the library dispatches to it yet. An annotation wired to an unused code
path would be a starter growing a feature its own library does not have.

## Choosing between them

A few of these solve overlapping problems, and the overlap is where time gets lost.

**Saga or pipeline?** A saga is one process running several steps in memory, with compensations;
a pipeline is several queues with a consumer on each. Use a saga when the steps must be undone on
failure and the whole thing is short enough to hold open. Use a pipeline when the steps are long,
need to scale independently, or must survive a restart partway through.

**Pipeline or routing slip?** A pipeline's route is fixed at build time and typed by the compiler.
A slip's route travels in the message and varies per message. Prefer the pipeline; it is the one
where a wrong step is a compile error.

**Ordered queue or concurrency 1?** `concurrency = 1` on a listener gives total order across
every message on the queue, and a throughput of one handler. An
[ordered queue](messaging-patterns.md#ordered-per-key) gives order per key and parallelism
across keys. Total order is almost never the requirement.

**Outbox or just publish?** If the publish and a database write must both happen or neither, the
[outbox](messaging-patterns.md#transactional-outbox). If the publish is the only side effect,
publish directly — the outbox adds a table, a relay and a polling interval, and paying that for
a message with nothing to be consistent with is cost without a benefit.

**Stream or queue?** A queue distributes work and forgets. A stream is a log that several readers
each read in full and can re-read. [Streams](streams.md) has the table.

## The library's own examples

Each of these patterns has a runnable, single-file example in
[acemq-java-amqp-examples](https://github.com/AceMQ-Company/acemq-java-amqp-examples), against a
real broker and without Spring. When a page here shows a pattern wired into a context, that
repository shows the same pattern working on its own — which is the shorter thing to read when
the question is what the library does rather than how the starter reaches it.
