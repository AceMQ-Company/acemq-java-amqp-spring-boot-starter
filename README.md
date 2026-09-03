# acemq-java-amqp-spring-boot-starter

Spring Boot auto-configuration for [acemq-java-amqp](https://github.com/AceMQ-Company/acemq-java-amqp):
one connection, a declared topology, annotated listeners, health and metrics — configured
from `application.yaml` and nothing else.

> **Status: working, unreleased.** 33 unit tests and 4 integration tests against RabbitMQ 4
> in Testcontainers. Nothing is published anywhere yet.

```yaml
acemq:
  url: amqp://localhost:5672
  topology:
    exchanges:
      - name: orders
        type: topic
    queues:
      - name: orders.new
    bindings:
      - queue: orders.new
        exchange: orders
        routing-key: order.created
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

That is the whole surface for the common case: a connection bean to inject, and an
annotation for the other direction.

## Installing

Two modules are published:

| Artifact | What it is |
|---|---|
| `acemq-spring-boot-starter` | The dependency an application adds: the auto-configuration, the library, the RabbitMQ transport and the JSON codec |
| `acemq-spring-boot-autoconfigure` | The auto-configuration alone, for an application that brings its own transport or codec |

```xml
<repositories>
  <repository>
    <id>acemq</id>
    <url>https://acemq-company.github.io/maven/</url>
  </repository>
</repositories>

<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

The repository block is needed because AceMQ is not on Maven Central before 1.0.

## What it configures

| Bean | When | Notes |
|---|---|---|
| `AceMq` | Always | Closed with the context |
| `ConnectionConfig` | Always | Exposed separately, so it can be inspected or replaced without taking over connecting |
| `Codec` | Always | `acemq.format`, default json |
| `Telemetry` | Always | Micrometer when a `MeterRegistry` is in the context, otherwise the library's auto-detection |
| `AceListenerRegistry` | Always | Starts and stops `@AceListener` methods; hand it a listener id to scale, pause or read counters |
| `AceMqTopologyInitializer` | Always | Applies `acemq.topology`, and does nothing when nothing is declared |
| `AceMqHealthIndicator` | Actuator on the classpath | Reports the connection under `/actuator/health` |

Every one is `@ConditionalOnMissingBean`. Define your own `AceMq` and the rest of the
starter keeps working against it, because everything else takes the connection as a
dependency rather than creating one.

## Documentation

Seven guide pages and five tutorials, in [docs/](docs/). They read as markdown here and
render to a site with `.github/scripts/build-docs-site.sh`, which the `docs.yml` workflow
publishes once this repository has a remote.

| | |
|---|---|
| **Start here** | [docs/index.md](docs/index.md) · [Getting started](docs/getting-started.md) |
| **Reference** | [Configuration](docs/configuration.md) — every `acemq.*` property |
| **Usage** | [Listeners](docs/listeners.md) · [Topology](docs/topology.md) · [Observability](docs/observability.md) · [Testing](docs/testing.md) |
| **Tutorials** | [Five, in order](docs/tutorials.md), each ending with something that runs |
| **Support** | [Enterprise support](https://acemq.com) |

## Two decisions worth knowing about

**There is no `AceTemplate`.** The library's publisher is already a small typed object
obtained from the connection, and a template wrapping it would be a second API to document,
a second one to keep in step with the first, and the place where a starter starts making
decisions the library deliberately left to the caller. Inject `AceMq`.

**A listener method cannot return an acknowledgement.** The library has an ack-aware handler
interface that nothing in the library itself currently uses. Wiring an annotation to an
unused code path is how a starter grows a feature its own library does not have; throwing
from the method is how a listener rejects a message, and `acemq.listener.retry` decides what
happens next.

## Requirements

Java 17 and Spring Boot 3. The library itself is Java 11 bytecode and works on Boot 2.7 —
an application still there can use `acemq-amqp-core` directly, without this starter.

Docker for the integration tests.

## Licence

Apache-2.0. RabbitMQ is a trademark of Broadcom Inc.; this project is not affiliated with it.
