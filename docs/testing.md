# Testing

Two kinds of test, and the line between them is Docker.

## Without a broker

`acemq-amqp-test` provides an in-process transport. Connect with a `memory://` URL and the
whole starter configures itself against it — same beans, same listeners, same topology
apply, no container.

```xml
<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-amqp-test</artifactId>
  <version>0.2.10</version>
  <scope>test</scope>
</dependency>
```

```java
@SpringBootTest(properties = {
        "acemq.url=memory://orders-test",
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
    void handlesAnOrder() throws Exception {
        mq.publisher("orders", "order.created", Order.class).send(new Order("A-1", 3));

        assertThat(listener.awaitOne(5, SECONDS)).isTrue();
    }
}
```

Two things to know about the fake, both of which it is honest about.

**Queues must be declared classic.** The in-memory transport does not claim quorum queues,
and it *refuses* one rather than pretending — which is the correct behaviour and does mean
a topology written for production needs `type: classic` here. A test that wants to exercise
the quorum default needs a broker.

**The host names the broker.** `memory://orders-test` and `memory://billing-test` are
different brokers; two connections to the same name share state. Give each test class its
own name rather than resetting shared state between tests.

What it does implement is routing, prefetch and settlement, which is what most tests
actually exercise. What it does not: replication, persistence, delayed delivery,
dead-lettering. Code depending on those fails here for the same reason it would fail
against a broker that lacks them.

## A slice without messaging at all

```java
@SpringBootTest(properties = "acemq.enabled=false")
```

No connection is made and no listener runs. This is the right switch for a test that
exercises HTTP or persistence and has no interest in the broker — and for a batch instance
of the same application in production.

## Testing the auto-configuration itself

`ApplicationContextRunner` is faster than a full `@SpringBootTest`, and is what this
starter's own tests use:

```java
new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AceMqAutoConfiguration.class))
        .withPropertyValues("acemq.url=memory://wiring")
        .run(context -> {
            assertThat(context).hasSingleBean(AceMq.class);
            assertThat(context.getBean(AceMq.class).isOpen()).isTrue();
        });
```

## With a broker

Testcontainers, for the things the fake cannot answer: that a quorum queue is one a broker
will actually create, that `validate` refuses to create, that TLS is configured the way it
was meant.

```java
@Testcontainers
class OrdersIT {

    @Container
    static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("acemq.url", BROKER::getAmqpUrl);
        registry.add("acemq.username", BROKER::getAdminUsername);
        registry.add("acemq.password", BROKER::getAdminPassword);
    }

    @Test
    void roundTrips() { }
}
```

Name them `*IT`, so `mvn test` stays fast and `mvn verify` is the one that needs Docker.
That split is the same in every AceMQ repository.

**Pin Testcontainers ahead of Boot's BOM.** Spring Boot 3.5.7 manages 1.21.3, whose
docker-java negotiates Docker API 1.32; Docker Desktop reports a minimum of 1.40 and answers
that handshake with an HTTP 400, which Testcontainers reports as "could not find a valid
Docker environment" — a message that sends people to look at their socket, which is fine.
Import the Testcontainers BOM *before* `spring-boot-dependencies`, since the first import
wins:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-bom</artifactId>
      <version>1.21.4</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>3.5.7</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Waiting for a message

A listener is asynchronous, so a test that asserts immediately after `send` asserts on
nothing. Use a latch rather than a sleep:

```java
@Component
class OrderListener {

    private final CountDownLatch received = new CountDownLatch(1);

    @AceListener(queue = "orders.new")
    void onOrder(Order order) {
        received.countDown();
    }

    boolean awaitOne(long timeout, TimeUnit unit) throws InterruptedException {
        return received.await(timeout, unit);
    }
}
```

Awaitility works as well and reads better for a condition that is not a single event. A
`Thread.sleep(200)` passes on a laptop and fails on a loaded CI runner, which is the worst
of the three outcomes because it fails intermittently.
