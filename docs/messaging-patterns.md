# Messaging patterns

The library's patterns, wired into a Spring Boot application. Each of these is a library type
reached through the `AceMq` bean, so the Spring-specific part is always the same question:
which bean holds it, and what closes it.

| | |
|---|---|
| [Request and reply](#request-and-reply) | A synchronous answer over a queue |
| [Scheduling](#scheduling) | Deliver this in an hour |
| [Transactional outbox](#transactional-outbox) | A message and a database row, atomically |
| [Saga](#saga) | Several steps, with compensations when one fails |
| [Pipelines](#pipelines) | A typed chain of queues, one step per queue |
| [Ordered per key](#ordered-per-key) | Order within a customer, parallelism across customers |
| [Routing slips](#routing-slips) | The route travels with the message |

[Retries, dead letters, replay and idempotency](reliability.md) are on their own page, as are
[serialization, schemas, claim checks and encryption](serialization.md).

## Request and reply

A request onto a queue, and an answer back on a reply queue, without HTTP. Worth it when the
caller already has a broker connection and the callee is a consumer rather than a service with
an endpoint — and not worth it merely to avoid writing a controller.

The answering side:

```java
@Configuration
class PriceService {

    @Bean(destroyMethod = "close")
    Responder priceResponder(AceMq mq, Prices prices) {
        return mq.respond("prices.requests", PriceRequest.class, request -> prices.quote(request));
    }
}
```

`respond` takes a plain `Function<Q, A>`: the return value is the reply, and the library matches
it to the request and publishes it to whatever reply queue the request named. A four-argument
form takes `ConsumerOptions`, which is how a responder gets a prefetch, a retry policy or a
codec of its own.

The asking side:

```java
@Configuration
class Requesting {

    /** One per application. It owns an exclusive reply queue and a correlation table. */
    @Bean(destroyMethod = "close")
    Requester requester(AceMq mq) {
        return mq.requester();
    }
}

@Service
class Checkout {

    private final Requester requester;

    Checkout(Requester requester) {
        this.requester = requester;
    }

    Price price(PriceRequest request) {
        return requester.request("prices", "price.requested", request,
                Price.class, Duration.ofSeconds(2));
    }

    CompletableFuture<Price> priceAsync(PriceRequest request) {
        return requester.requestAsync("prices", "price.requested", request, Price.class);
    }
}
```

**Make the `Requester` a bean, not a local.** It owns a reply queue and a subscription to it,
and creating one per call means a queue per call — which a broker will let you do right up to
the point where it does not.

`request(...)` blocks for the timeout and then throws `RequestTimedOutException`. `requestAsync`
has no timeout parameter; bound it with `orTimeout` on the future, or use the synchronous form.
A timeout is not a failure of the request — the answer may well arrive afterwards and be
counted in `requester.unmatched()`, which together with `timedOut()` is the pair worth putting on
a gauge. A rising `unmatched` means the timeout is too short rather than that the responder is
broken.

**A blocking request from a web request thread is a thread held for the timeout.** Two seconds
under load is a thread pool. `requestAsync` and a `CompletableFuture`-returning controller
method, or a virtual-thread executor, are the ways out.

## Scheduling

```java
@Configuration
class Scheduling {

    @Bean(destroyMethod = "close")
    Scheduler scheduler(AceMq mq) {
        return Scheduler.on(mq);
    }
}

@Service
class Reminders {

    private final Scheduler scheduler;

    void remindAboutAbandonedBasket(Basket basket) {
        scheduler.in(Duration.ofHours(1), "baskets", "basket.abandoned", basket);
    }

    void chargeAtRenewal(Subscription subscription) {
        scheduler.at(subscription.renewsAt(), "billing", "subscription.renew", subscription);
    }
}
```

The delay is held on the broker, in queues whose time-to-live expires the message onward. So a
scheduled message survives a restart of the application, which is the whole reason not to use
`@Scheduled` and an in-memory timer for this.

`scheduler.hops()` is the counter to understand: a long delay is built from several shorter
hops, because a queue's time-to-live is fixed when the queue is declared. Rising hops are
normal for long delays and are not a fault.

This is not a replacement for `@Scheduled`. A cron job that runs every night belongs in
Spring's scheduler; a message about one basket, an hour from now, belongs here. The distinction
is whether the schedule is about the application or about a single message.

## Transactional outbox

The problem: a request has to write a row and publish a message, and there is no transaction
spanning a database and a broker. Publish first and the transaction may roll back, leaving a
message about something that did not happen. Publish last and the process may die, leaving a row
nothing was told about.

The outbox writes the message into the same database in the same transaction, and a relay
publishes it afterwards.

```java
@Configuration
class Outbox {

    /**
     * Two connection sources on purpose. The first is Spring's — so add() joins whatever
     * transaction the calling @Transactional method is in, which is the entire point. The
     * second is the pool, for the relay, which must not be inside anybody's transaction.
     */
    @Bean
    OutboxStore outboxStore(DataSource dataSource) {
        JdbcOutboxStore store = new JdbcOutboxStore(
                () -> DataSourceUtils.getConnection(dataSource),
                dataSource);
        store.createSchemaIfAbsent();
        return store;
    }

    @Bean(destroyMethod = "close")
    OutboxRelay outboxRelay(AceMq mq, OutboxStore store) {
        OutboxRelay relay = new OutboxRelay(mq, store);
        relay.start();
        return relay;
    }
}
```

`DataSourceUtils.getConnection(dataSource)` is the load-bearing line. It returns the connection
bound to the current transaction when there is one, and a fresh one otherwise — which is exactly
what `add()` needs and what `dataSource::getConnection` would get wrong. With the plain data
source the insert would be in its own transaction and would commit even when the business
transaction rolled back, which is the failure the outbox exists to prevent, reintroduced one
method call lower down.

Writing to it:

```java
@Service
class Orders {

    private final OrderRepository orders;
    private final OutboxStore outbox;

    @Transactional
    public void place(Order order) {
        orders.save(order);
        outbox.add(OutboxRecord.of(
                "orders", "order.created",
                Envelope.of("order.created").correlationId(order.id()).build(),
                serialise(order)));
    }
}
```

One transaction, two rows, no message published yet. If the transaction rolls back, both
disappear together.

`OutboxRecord.of(exchange, routingKey, envelope, payload)` takes the payload as a `String` — the
outbox stores the encoded body, and the relay publishes those bytes rather than re-encoding an
object. JSON from your object mapper is the ordinary answer.

The relay polls, claims a batch under a lease, publishes, and marks each record published or
failed. `new OutboxRelay(mq, store, batchSize, pollInterval, lease)` sets those; the defaults are
sensible and the lease is the one worth thinking about — it is how long a claimed batch stays
claimed if the relay dies mid-publish, and it must exceed the time a batch takes to publish or
two relays will publish the same records.

**Several replicas each run a relay, and that is fine.** `claimBatch` leases rows, so two relays
take different batches. What it does not give you is exactly-once delivery: a relay that
publishes and then dies before `markPublished` will publish that record again. The outbox
guarantees at-least-once, which is why its consumers want
[idempotency](reliability.md#idempotency).

Watch `pendingCount()`. A relay that has stopped looks exactly like a quiet system until
somebody asks why nothing downstream has happened:

```java
@Bean
MeterBinder outboxDepth(OutboxStore store, OutboxRelay relay) {
    return registry -> {
        Gauge.builder("outbox.pending", store::pendingCount).register(registry);
        Gauge.builder("outbox.relay.running", () -> relay.isRunning() ? 1 : 0).register(registry);
        FunctionCounter.builder("outbox.published", relay, OutboxRelay::published).register(registry);
        FunctionCounter.builder("outbox.failed", relay, OutboxRelay::failed).register(registry);
    };
}
```

`relay.drainOnce()` publishes one batch synchronously and returns how many, which is what a test
uses instead of waiting for a poll interval. `drain()` publishes until the outbox is empty.

## Saga

Several steps that must all happen, each with something to undo it. When step four fails, the
first three are compensated in reverse.

```java
@Service
class Fulfilment {

    private final Payments payments;
    private final Inventory inventory;
    private final Shipping shipping;

    SagaResult fulfil(Order order) {
        Saga<Order> saga = Saga.<Order>named("fulfil-order")
                .step("take-payment", payments::take)
                .compensateWith(payments::refund)
                .step("reserve-stock", inventory::reserve)
                .compensateWith(inventory::release)
                .step("book-courier", shipping::book)
                .compensateWith(shipping::cancel)
                .build();

        return saga.run(order);
    }
}
```

Each `compensateWith` undoes the step immediately before it. A step with no compensation is
allowed and means "nothing to undo" — which is a claim worth being sure of, because a failure
later on will not come back to it.

```java
SagaResult result = saga.run(order);

result.isComplete();     // every step ran
result.compensated();    // it failed and every compensation ran
result.failedAt();       // which step
result.failure();        // why
result.completed();      // the steps that ran
result.unresolved();     // compensations that themselves failed
result.hasUnresolved();  // the case that needs a human
```

**`hasUnresolved()` is the one to handle.** A saga that failed and compensated cleanly is a
normal outcome and needs no attention. A saga whose *compensation* failed has left the system
half-changed and there is nobody left to fix it automatically — a payment taken and not
refunded. Log it loudly, alert on it, and give somebody a way to see the list:

```java
SagaResult result = saga.run(order);
if (result.hasUnresolved()) {
    log.error("saga fulfil-order left {} unresolved for order {}; manual action needed",
            result.unresolved(), order.id());
}
```

`Saga` is a plain object, not a bean — build it where the steps are, from the beans that do the
work. The steps are `Consumer<T>`, so they can publish, call a database, or do both; the saga
does not know or care.

This is an orchestrated saga: one process runs the steps and knows the order. A choreographed
saga, where each service reacts to the previous one's event, is [a pipeline](#pipelines) or
plain listeners, and does not use this type.

## Pipelines

A typed chain where each step is its own queue: the output of one step is the input of the next,
and each step can fail, retry and scale independently. The type changes along the chain and the
compiler checks it.

```java
@Configuration
class OrderPipeline {

    @Bean(destroyMethod = "close")
    Pipeline<OrderPlaced> orders(AceMq mq, Validation validation, Pricing pricing, Warehouse warehouse) {
        return mq.pipeline("orders", OrderPlaced.class)
                 .step("validate", ValidatedOrder.class, message -> validation.check(message.payload()))
                 .step("price", PricedOrder.class, message -> pricing.apply(message.payload()))
                 .step("reserve", ReservedOrder.class, message -> warehouse.reserve(message.payload()))
                 .withRetry(RetryPolicy.exponential(5, Duration.ofSeconds(2), Duration.ofSeconds(30)))
                 .concurrency(4)
                 .prefetch(40)
                 .build();
    }
}
```

`build()` declares the queues and starts a consumer on each. `send(payload)` puts a payload in
at the top and returns the run id.

```java
pipeline.send(order);           // returns the run id
pipeline.queueFor("price");     // the queue that step consumes, for a dashboard
pipeline.step("price");         // that step's ConsumerGroup, for scaling and counters
pipeline.entered();
pipeline.completed();
pipeline.endedEarly();          // a step returned null and stopped the run
pipeline.inFlight();
```

**A step that returns `null` ends the run** without it being a failure, which is how a filter
step works — validation that rejects an order stops it going further rather than throwing.
`endedEarly()` counts those, and a pipeline where `endedEarly` is most of `entered` is usually a
validation rule that is wrong rather than a lot of bad orders.

`withRetry`, `idempotent`, `concurrency`, `prefetch` and `encodedAs` apply to every step. A
single step that needs different settings is a sign the chain should be two pipelines, or that
the step should be an ordinary listener publishing onward.

The step handler is a `Step<I, O>`, which takes a `Message<I>` rather than a bare payload — so a
step can read the envelope, the correlation id and the attempt count, which is most of what a
step wants that a `Function` would not give it.

## Ordered per key

Concurrency above one on a queue gives up ordering. Usually that is fine; sometimes order within
one customer, one account or one aggregate is a correctness requirement while order across them
is not.

```java
@Configuration
class Accounts {

    @Bean(destroyMethod = "close")
    OrderedQueue<AccountEvent> accountEvents(AceMq mq, Ledger ledger) {
        return mq.<AccountEvent>ordered("account.events", AccountEvent.class)
                 .partitions(12)
                 .keyedBy(AccountEvent::accountId)
                 .prefetch(20)
                 .onFailure(OrderedQueue.OnFailure.STOP)
                 .declare()
                 .consume(event -> ledger.apply(event.payload()));
    }
}
```

Twelve partitions, twelve queues, one consumer each. Every event for one account hashes to the
same partition and is therefore handled in order by one consumer, while twelve accounts are
handled at once.

Publish through the queue rather than through a publisher, so the partition is chosen for you:

```java
int partition = accountEvents.send(event);
```

The three failure behaviours are a real choice and worth making deliberately:

| | |
|---|---|
| `STOP` | The partition halts. Order is preserved; that account stops moving until somebody intervenes |
| `RETRY_IN_PLACE` | Retry the same message, in order, `attempts` times with a delay |
| `SKIP` | Drop it and carry on. Order is preserved; a message is gone |

`STOP` is the default and the honest one for a ledger: an event that cannot be applied means the
next event for that account cannot be applied correctly either, and carrying on produces a
balance that is wrong and does not look wrong. `haltedPartitions()` is what a health check reads
— a halted partition is a stuck customer and nothing else will tell you.

```java
@Bean
HealthIndicator accountOrdering(OrderedQueue<AccountEvent> accountEvents) {
    return () -> accountEvents.haltedPartitions().isEmpty()
            ? Health.up().withDetail("partitions", accountEvents.partitions()).build()
            : Health.down().withDetail("halted", accountEvents.haltedPartitions()).build();
}
```

`onFailure(OnFailure.RETRY_IN_PLACE, 3, Duration.ofSeconds(5))` is the form that takes the
attempt count and delay. `Partitioning.partitionFor(key, partitions)` is the same hash the queue
uses, for a test that wants to assert two keys land apart, or for a dashboard that maps a
customer to a queue.

Twelve is a number, not a recommendation. Partitions cannot be changed after declaration without
breaking the mapping from key to queue, so pick a count above the concurrency you expect to need
and leave it.

## Routing slips

The route travels in the message rather than being known by each hop. Useful when the sequence
of services differs per message — a claim that needs underwriting only above a threshold, say.

```java
Itinerary route = Itinerary.empty()
        .then("underwriting", "policy.underwrite")
        .then("billing", "policy.bill")
        .then("documents", "policy.issue");

mq.publisher("underwriting", "policy.underwrite", Policy.class)
  .send(policy, Envelope.of("policy.submitted").header(Itinerary.HEADER, route.toHeader()).build());
```

A step reads the slip, does its work, and forwards to the next stop:

```java
@AceListener(queue = "underwriting.requests")
void underwrite(Message<Policy> message) {
    underwriting.assess(message.payload());

    Itinerary remaining = Itinerary.from(message.headers())
            .orElse(Itinerary.empty())
            .advance();

    remaining.next().ifPresent(stop ->
            mq.publisher(stop.exchange(), stop.routingKey(), Policy.class)
              .send(message.payload(), message.envelope()
                      .causing("policy.underwritten")
                      .header(Itinerary.HEADER, remaining.toHeader())
                      .build()));
}
```

`Itinerary.from(headers)` reads it back, `advance()` moves past the current stop, `next()` is
where to send it, `isFinished()` says the route is done, and `done()` is where it has been —
which is the audit trail the pattern is actually for.

This is the manual form and the only one there is: nothing in the library forwards a slip for
you. A pipeline is the better answer when the sequence is fixed, because the chain is declared
once and typed. A slip is for when the route genuinely varies per message, and the cost is that
every step has the forwarding code above.

`RoutingSlip` is the same idea carried in three separate headers rather than one JSON blob, and
`Envelope.route()` exposes it. Both exist because the family's libraries had to agree on a wire
format; `Itinerary` is the one to write new code against.
