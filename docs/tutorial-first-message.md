# Tutorial 1: your first message

Fifteen minutes. At the end you have a service with an HTTP endpoint that publishes an
order, a listener that consumes it, and a management UI showing both.

Start from the project and broker in [Tutorials](tutorials.md#before-you-start).

## The payload

```java
package com.example.orders;

public record Order(String id, int quantity) {}
```

A record is enough. The JSON codec that ships with the starter handles it, and there is
nothing to register.

## The configuration

`src/main/resources/application.yaml`:

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

## Publishing

```java
package com.example.orders;

import org.acemq.amqp.api.Publisher;
import org.acemq.amqp.core.AceMq;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private final Publisher<Order> orders;

    OrderController(AceMq mq) {
        this.orders = mq.publisher("orders", "order.created", Order.class);
    }

    @PostMapping("/orders")
    String place(@RequestBody Order order) {
        var result = orders.send(order);
        return "published " + result.messageId() + " in " + result.latency().toMillis() + "ms";
    }
}
```

`send` returns a `PublishResult` when the **broker has confirmed** the message, because
publisher confirms are on by default. `routed()` on that result says whether the message
reached a queue: a message published to an exchange with no matching binding is confirmed
and dropped, and that is the failure worth knowing about early.

## Consuming

```java
package com.example.orders;

import org.acemq.spring.boot.AceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class OrderListener {

    private static final Logger log = LoggerFactory.getLogger(OrderListener.class);

    @AceListener(queue = "orders.new")
    void onOrder(Order order) {
        log.info("order {} for {} units", order.id(), order.quantity());
    }
}
```

## Running it

```bash
mvn spring-boot:run
```

The first run declares the topology:

```
INFO o.a.s.b.AceMqTopologyInitializer : topology (CREATE_ONLY):
  CREATE   exchange orders (topic)
  CREATE   queue orders.new (quorum)
  CREATE   binding orders.new <- orders [order.created]
INFO o.a.s.b.AceListenerRegistry     : listener orderListener#onOrder consuming orders.new with 1 consumer(s), prefetch 100
```

Send one:

```bash
curl -X POST localhost:8080/orders \
  -H 'content-type: application/json' \
  -d '{"id":"A-1","quantity":3}'
```

```
published 0195f0c2-... in 4ms
INFO c.e.orders.OrderListener : order A-1 for 3 units
```

## What to look at in the management UI

Three pages, and each one shows something the log does not.

**Connections.** One connection, named `orders-service` — that is
`spring.application.name`, which the starter uses as the client name. On a broker with forty
connections, this is the difference between finding the noisy service at a glance and
reading process lists.

**Queues.** `orders.new`, type **quorum**. That is the library's default: a durable,
replicated queue. Nothing in the configuration asked for it, and asking for a classic one is
[one line](topology.md#classic-queues).

**Exchanges → orders.** One binding, `orders.new` on `order.created`.

## What happens when nothing is listening

Stop the application and publish again — except there is nothing to publish with, so instead
change the routing key in `OrderController` to `order.cancelled`, restart, and post another
order.

```bash
curl -X POST localhost:8080/orders -H 'content-type: application/json' -d '{"id":"A-2","quantity":1}'
```

It succeeds. The message was confirmed by the broker and then dropped, because no binding
matched. The queue count does not move.

This is AMQP working as designed, and it is the single most common "the message vanished"
report. Two ways to catch it:

```java
var result = orders.send(order);
if (!result.routed()) {
    log.error("order {} was not routed by any binding", order.id());
}
```

and, in [Tutorial 4](tutorial-observability.md), the metric that counts it:
`acemq_publish_total{outcome="unroutable"}`.

Change the routing key back to `order.created` before continuing.

## What you have

- A publisher that blocks until the broker confirms.
- A listener that is started after the context is ready and drained on shutdown.
- A quorum queue nobody had to ask for.
- One thing that will bite eventually, met early: an unroutable message is a successful
  publish.

Next: [declaring the topology](tutorial-topology.md), including the deployment where the
application must not create anything at all.
