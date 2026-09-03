# Getting started

A broker, a dependency, and a message that goes out and comes back. Fifteen minutes, and
nothing here is left as an exercise.

## A broker

```bash
docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:4-management
```

The management UI is at <http://localhost:15672>, guest/guest. Worth having open: every
queue and connection on this page shows up there.

## The dependency

AceMQ is not on Maven Central before 1.0, so the repository is declared as well as the
dependency.

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

Gradle:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://acemq-company.github.io/maven/") }
}

dependencies {
    implementation("org.acemq:acemq-spring-boot-starter:0.1.0")
}
```

That brings the auto-configuration, the library, the RabbitMQ transport and the JSON codec.
Nothing else is required to start.

## Configuration

```yaml
spring:
  application:
    name: orders-service

acemq:
  url: amqp://localhost:5672
  username: guest
  password: guest
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

Three things are worth noticing.

**The URL alone would have done.** `amqp://localhost:5672` is the default, and so are
guest/guest against a local broker with the default user. The block above is what a real
application looks like, not the minimum.

**`spring.application.name` becomes the connection name.** The broker's Connections page
lists it. On a page of forty connections, that is the difference between finding the noisy
service at a glance and reading process lists.

**The queue is quorum.** `queues: [{ name: orders.new }]` declares a durable quorum queue,
which is the library's default everywhere and the right answer for anything whose loss
would be noticed. [Topology](topology.md#classic-queues) covers asking for a classic one.

## Sending

Inject `AceMq` and ask it for a publisher.

```java
import org.acemq.amqp.core.AceMq;
import org.springframework.stereotype.Service;

@Service
class OrderService {

    private final AceMq mq;

    OrderService(AceMq mq) {
        this.mq = mq;
    }

    void place(Order order) {
        mq.publisher("orders", "order.created", Order.class).send(order);
    }
}
```

`send` returns when the broker has confirmed the message, because publisher confirms are on
by default. That is the property most worth keeping: without confirms, a successful `send`
means the message reached a socket buffer.

Holding the publisher in a field is fine and slightly cheaper than creating one per call:

```java
private final Publisher<Order> orders;

OrderService(AceMq mq) {
    this.orders = mq.publisher("orders", "order.created", Order.class);
}
```

## Receiving

```java
import org.acemq.spring.boot.AceListener;
import org.springframework.stereotype.Component;

@Component
class OrderListener {

    @AceListener(queue = "orders.new")
    void onOrder(Order order) {
        log.info("order {} for {} units", order.id(), order.quantity());
    }
}
```

The method takes the decoded payload. Ask for `Message<Order>` instead and the envelope,
headers, queue name and attempt count come with it — see [Listeners](listeners.md).

Returning normally acknowledges the message. Throwing rejects it, and what happens next is
[configured, not guessed](listeners.md#when-a-handler-throws).

## Running it

```bash
mvn spring-boot:run
```

The log shows the topology being applied on the first run:

```
INFO o.a.s.b.AceMqTopologyInitializer : topology (CREATE_ONLY):
  CREATE   exchange orders (topic)
  CREATE   queue orders.new (quorum)
  CREATE   binding orders.new <- orders [order.created]
INFO o.a.s.b.AceListenerRegistry     : listener orderListener#onOrder consuming orders.new with 1 consumer(s), prefetch 100
```

and on the second run, nothing — the queue is already there, and the plan has no changes to
report.

## Where to next

- [Configuration](configuration.md) for every property, including TLS and the retry ladder.
- [Listeners](listeners.md) for concurrency, prefetch and failure handling.
- [Tutorial 1](tutorial-first-message.md) builds the same thing as a working service, with
  the management UI open next to it.
