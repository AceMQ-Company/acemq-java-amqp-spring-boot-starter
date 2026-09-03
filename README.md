# acemq-java-amqp-spring-boot-starter

Spring Boot auto-configuration for [acemq-java-amqp](https://github.com/AceMQ-Company/acemq-java-amqp):
one connection, a declared topology, annotated listeners, health and metrics — configured
from `application.yaml` and nothing else.

> **Status: `0.1.0`, published.** 42 unit tests and 3 integration tests against
> RabbitMQ 4 in Testcontainers, run on Spring Boot 3.5.7, 4.0.6 and 4.1.0.
> Artifacts are on the [Maven repository](https://acemq-company.github.io/maven/).

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

Four modules are published:

| Artifact | What it is |
|---|---|
| `acemq-spring-boot-starter` | The dependency an application adds: the auto-configuration, both health modules, the library, the RabbitMQ transport and the JSON codec |
| `acemq-spring-boot-autoconfigure` | The auto-configuration alone, for an application that brings its own transport or codec |
| `acemq-spring-boot-health-boot3` | The health indicator for Spring Boot 3 |
| `acemq-spring-boot-health-boot4` | The health indicator for Spring Boot 4 |

**One starter serves Spring Boot 3 and Spring Boot 4.** Everything but health is
source-compatible across the two lines; health is not, because Boot 4 moved the contributor
API from `spring-boot-actuator`'s `org.springframework.boot.actuate.health` to
`spring-boot-health`'s `org.springframework.boot.health.contributor`, and the two
`AbstractHealthIndicator` classes share no ancestor. Both health modules ship, each guarded
by a `@ConditionalOnClass` on its own line's API, and exactly one ever matches. Nothing to
choose and nothing to configure.

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
| `AceMqHealthIndicator` | Actuator on the classpath | Reports the connection under `/actuator/health`; Boot 3 and Boot 4 each have their own |

Every one is `@ConditionalOnMissingBean`. Define your own `AceMq` and the rest of the
starter keeps working against it, because everything else takes the connection as a
dependency rather than creating one.

## Documentation

Seven guide pages and five tutorials, published at
**<https://acemq.org/acemq-java-amqp-spring-boot-starter/>**. They read as markdown in
[docs/](docs/) too, and render with `.github/scripts/build-docs-site.sh`.

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

Java 17, and Spring Boot 3 or 4. Built and tested against 3.5.7, 4.0.6 and 4.1.0.

### Spring Boot 2.7

Not a supported configuration yet, but closer than expected, and the measurement is worth
recording rather than re-deriving.

The auto-configure module **compiles at Java 11 bytecode and passes its full suite (31
tests) against Boot 2.7.18** with no source changes. Nothing had to be ported because
`acemq-amqp-core` is deliberately namespace-free — this module has zero `javax.*` and zero
`jakarta.*` imports — and every Spring API it uses (`@AutoConfiguration`, the
`@ConditionalOn*` family, `@ConfigurationProperties`, `SmartLifecycle`,
`BeanPostProcessor`) exists in Spring 5.3 and Boot 2.7. The
`AutoConfiguration.imports` file format is Boot 2.7+ as well.

```bash
mvn -pl acemq-spring-boot-autoconfigure \
    -Dspring.boot.version=2.7.18 \
    -Dmaven.compiler.release=11 -Dmaven.compiler.testRelease=17 test
```

The health module is compiled at Java 11 as well, and verified against
`spring-boot-actuator` 2.7.18 as well as 3.5.7 — Boot 2.7 and Boot 3 share that health API,
so one module serves both.

One thing stands between that and a claim: **it has not been executed on a Java 11 JVM.**
What is verified is the bytecode target and the Boot 2.7 API surface, both on a 21
toolchain. A Boot 2.7 application on Java 17 is the configuration with no known gap.

Boot 2.7 reached open-source end of life, so applications still on it are precisely the
ones that cannot move. That this works is worth knowing before deciding what to support.

Docker for the integration tests.

## Licence

Apache-2.0. RabbitMQ is a trademark of Broadcom Inc.; this project is not affiliated with it.
