# AceMQ Spring Boot starter

Auto-configuration for [acemq-java-amqp](https://acemq-company.github.io/acemq-java-amqp/):
one connection, a declared topology, annotated listeners, health and metrics — configured
from `application.yaml`.

```yaml
acemq:
  url: amqp://localhost:5672
  topology:
    exchanges: [{ name: orders, type: topic }]
    queues:    [{ name: orders.new }]
    bindings:  [{ queue: orders.new, exchange: orders, routing-key: order.created }]
```

```java
@Component
class Orders {

    private final AceMq mq;

    Orders(AceMq mq) {
        this.mq = mq;
    }

    void place(Order order) {
        mq.publisher("orders", "order.created", Order.class).send(order);
    }

    @AceListener(queue = "orders.new")
    void onOrder(Order order) {
        // ...
    }
}
```

## The guide

| | |
|---|---|
| [Getting started](getting-started.md) | A broker, a dependency, a message that goes and comes back |
| [Configuration](configuration.md) | Every `acemq.*` property, what it does, and what it defaults to |
| [Listeners](listeners.md) | `@AceListener`: payloads, concurrency, failures, starting and stopping |
| [Topology](topology.md) | Declaring exchanges, queues and bindings, and the three apply modes |
| [Observability](observability.md) | Health, metrics, and what the numbers mean |
| [Testing](testing.md) | A context test without Docker, and an integration test with it |
| [Tutorials](tutorials.md) | Five, in order, each ending with something that runs |

## What it is

A thin layer. The starter creates beans from properties and calls the library; it does not
wrap the library's API in one of its own. What you inject is `AceMq`, the same object the
[library's documentation](https://acemq-company.github.io/acemq-java-amqp/) describes, so
every example there works here unchanged.

Concretely, it gives an application:

- **One connection**, built from `acemq.*`, closed with the context.
- **A topology** applied once at startup, or validated rather than applied.
- **Listeners** as annotated methods, started after the context is ready and drained on
  shutdown.
- **Metrics** through whatever `MeterRegistry` the application already has.
- **Health** under `/actuator/health`, when Actuator is on the classpath.

## What it is not

- **Not a replacement for Spring AMQP.** If an application is happily using
  `RabbitTemplate` and `@RabbitListener`, this offers no reason to move on its own. The
  reason to move is the library underneath: non-blocking retry ladders, an outbox, sagas,
  claim checks and pipelines, and a topology that is planned rather than declared by
  side effect. This starter is how those reach a Boot application, not a competing
  listener container.
- **Not a message-broker abstraction.** AceMQ is portable across AMQP brokers; it is not a
  facade over Kafka, JMS and SQS. Neither is this.
- **Not a template.** There is no `AceTemplate`. See
  [why](listeners.md#there-is-no-template), which is the same reason there is no
  `@AceListener` return value that acknowledges.

## Versions

| | |
|---|---|
| Java | 17 |
| Spring Boot | **3.x and 4.x**, from one artifact. Built and tested against 3.5.7, 4.0.6 and 4.1.0 |
| acemq-java-amqp | 0.2.10 |
| Brokers | RabbitMQ 3.13 and 4.x, via `acemq-transport-rabbitmq` |

Spring's own artifacts are `provided`: this starter never brings a Boot version of its own
into an application. The library is Java 11 bytecode, so a Boot 2.7 application can use
`acemq-amqp-core` directly — it just cannot use this starter, which needs Boot 3 or 4.

### One artifact, both Boot lines

Everything except health is source-compatible across the two lines, so the auto-configuration
is one module. Health is not: Boot 4 moved the contributor API out of `spring-boot-actuator`
(`org.springframework.boot.actuate.health`) into `spring-boot-health`
(`org.springframework.boot.health.contributor`), and the two `AbstractHealthIndicator`
classes share no ancestor.

So the starter ships two small health modules, `acemq-spring-boot-health-boot3` and
`-boot4`, each compiled against its own line and each guarded by a `@ConditionalOnClass` on
that line's health API. The packages never coexist, so exactly one matches and the other jar
is inert. Nothing to configure, and nothing to choose at dependency time.

Two details make it work, and both were found by testing rather than by reasoning. The
condition sits on the **auto-configuration class**, not on its bean method: Boot filters
auto-configuration candidates from ASM metadata before loading them, and a condition on the
method would come too late — reading the method resolves its return type, whose supertype is
the missing one. And the two modules use **different package names** (`...health.boot3` and
`...health.boot4`): identical fully-qualified names would mean the first jar on the
classpath wins, its condition fails on the other line, and the application silently gets no
health indicator.

Pre-1.0: the API may still change. The [changelog](https://github.com/AceMQ-Company/acemq-java-amqp-spring-boot-starter/blob/main/CHANGELOG.md)
records what did.
