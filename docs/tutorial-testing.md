# Tutorial 5: testing it

Twenty-five minutes. Continues from [Tutorial 4](tutorial-observability.md).

By the end: a test suite that runs in under a second without Docker, an integration test with
a real broker, and a clear line between what each can prove.

## The fast one

Add the in-memory transport:

```xml
<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-amqp-test</artifactId>
  <version>0.2.10</version>
  <scope>test</scope>
</dependency>
```

`src/test/java/com/example/orders/OrderFlowTest.java`:

```java
package com.example.orders;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import org.acemq.amqp.core.AceMq;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "acemq.url=memory://order-flow-test",
        "acemq.topology.exchanges[0].name=orders",
        "acemq.topology.exchanges[0].type=topic",
        "acemq.topology.queues[0].name=orders.new",
        "acemq.topology.queues[0].type=classic",
        "acemq.topology.bindings[0].queue=orders.new",
        "acemq.topology.bindings[0].exchange=orders",
        "acemq.topology.bindings[0].routing-key=order.created"
})
class OrderFlowTest {

    @Autowired AceMq mq;
    @Autowired OrderListener listener;

    @Test
    void anOrderReachesTheListener() throws Exception {
        mq.publisher("orders", "order.created", Order.class).send(new Order("A-1", 3));

        assertThat(listener.awaitOne(5, SECONDS)).isTrue();
    }
}
```

with a latch on the listener:

```java
@Component
class OrderListener {

    private final CountDownLatch received = new CountDownLatch(1);

    @AceListener(queue = "orders.new")
    void onOrder(Order order) { received.countDown(); }

    boolean awaitOne(long timeout, TimeUnit unit) throws InterruptedException {
        return received.await(timeout, unit);
    }
}
```

```bash
mvn test
```

No container, no port, and the whole starter is exercised: the properties are bound, the
connection is made, the topology is applied, the listener is registered and started, and the
message is routed and decoded.

Two things the fake insists on, both of them honest:

**Queues must be `type: classic` here.** The in-memory transport does not claim quorum
queues and refuses one rather than pretending. Production topology and test topology differ
by that one line — and the alternative, a fake that accepts a quorum declaration and gives
you a classic queue, is how a test suite passes against behaviour that does not exist.

**The host names the broker.** `memory://order-flow-test` is its own broker; another test
class with another name shares nothing with it. That is how tests stay isolated without
resetting global state between them.

## Never sleep

```java
Thread.sleep(200);              // passes here, fails on CI, passes on the rerun
assertThat(listener.handled()).isEqualTo(1);
```

A latch, or Awaitility, or a `drain` call. An intermittent test is worse than a missing one,
because it trains everybody to press the button again.

## A slice with no messaging at all

```java
@SpringBootTest(properties = "acemq.enabled=false")
@AutoConfigureMockMvc
class OrderControllerValidationTest { }
```

No connection, no listener. A test about HTTP validation has no business connecting to a
broker, and this is the switch — the same one a batch instance of the application uses in
production.

## The one with a broker

Some things the fake cannot answer, and they are exactly the things that break in
production. Add Testcontainers:

```xml
<dependencyManagement>
  <dependencies>
    <!-- Before Boot's BOM: the first import wins, and Boot 3.5.7 manages 1.21.3,
         whose docker-java negotiates an API version Docker Desktop refuses. -->
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-bom</artifactId>
      <version>1.21.4</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>rabbitmq</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

`src/test/java/com/example/orders/OrdersIT.java`:

```java
@Testcontainers
@SpringBootTest
class OrdersIT {

    @Container
    static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("acemq.url", BROKER::getAmqpUrl);
        registry.add("acemq.username", BROKER::getAdminUsername);
        registry.add("acemq.password", BROKER::getAdminPassword);
    }

    @Autowired AceMq mq;
    @Autowired OrderListener listener;

    @Test
    void declaresAQuorumQueueAndRoundTrips() throws Exception {
        // The production topology, unmodified: no type, so quorum.
        mq.publisher("orders", "order.created", Order.class).send(new Order("A-1", 3));

        assertThat(listener.awaitOne(10, SECONDS)).isTrue();
    }
}
```

Name it `*IT` and let Failsafe run it, which is the split every AceMQ repository uses:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <excludes><exclude>**/*IT.java</exclude></excludes>
  </configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>integration-test</goal><goal>verify</goal></goals>
    </execution>
  </executions>
</plugin>
```

```bash
mvn test      # fast, no Docker
mvn verify    # everything, needs Docker
```

## What belongs in which

| Question | Where it can be answered |
|---|---|
| Are the properties bound and the beans wired? | Memory |
| Does the listener receive a decoded payload? | Memory |
| Does the retry ladder republish rather than sleep? | Memory |
| Is the declared quorum queue one a broker will create? | Broker |
| Does `validate` refuse to create? | Broker |
| Does TLS connect with these certificates? | Broker |
| Is a message redelivered when a consumer dies mid-handler? | Broker |
| Is the throughput acceptable? | Neither — [acemq-java-amqp-workloads](https://github.com/AceMQ-Company/acemq-java-amqp-workloads) |

The rule of thumb: the fake answers questions about **your code**, and a broker answers
questions about **the broker**. A test suite that runs the second kind on every save is a
test suite people stop running.

## Where to next

- [Configuration](configuration.md) for everything the tutorials did not touch — TLS,
  virtual hosts, timeouts, formats.
- [Listeners](listeners.md) for concurrency and the registry.
- The [library's own documentation](https://acemq-company.github.io/acemq-java-amqp/) for
  what is underneath: outbox, sagas, claim checks, pipelines and ordered queues, all of which
  work in a Boot application through the same injected `AceMq`.
