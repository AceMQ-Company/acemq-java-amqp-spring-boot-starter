# Tutorial 4: health and metrics

Twenty minutes. Continues from [Tutorial 3](tutorial-retries.md).

By the end: a health endpoint that says something useful, Prometheus metrics, three graphs
worth having on day one, and a blocked broker that does **not** fail the health check.

## Health

Actuator is already a dependency of the tutorial project. Expose the endpoint with details:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      show-details: always
```

```bash
curl -s localhost:8080/actuator/health | jq .components.aceMq
```

```json
{
  "status": "UP",
  "details": {
    "transport": "rabbitmq",
    "open": true,
    "blocked": false,
    "inFlight": 0
  }
}
```

Stop the broker and ask again:

```bash
docker stop rabbit
curl -s localhost:8080/actuator/health | jq '.status, .components.aceMq.details.open'
```

```
"DOWN"
false
```

Start it again — `docker start rabbit` — and within a few seconds the connection recovers and
the status returns to UP. The library reconnects; the health indicator reports what it finds.

## A blocked broker is up

RabbitMQ blocks publishing connections when it hits a memory or disk alarm. This indicator
reports that as up, with the reason:

```json
{
  "status": "UP",
  "details": { "open": true, "blocked": true, "blockedReason": "low on disk space" }
}
```

DOWN would be the dramatic choice and the wrong one. A blocked connection is the broker
protecting itself, and an application that fails its readiness check for it gets removed
from its load balancer, restarted by its orchestrator, and brought back into the same
blocked broker — having thrown away whatever it was holding. The blockage is the broker's
problem to clear.

`blocked: true` is what to alert on. It is a fact about the broker, and it belongs in an
alert, not in a liveness probe.

Reproducing it is possible with `rabbitmqctl set_vm_memory_high_watermark 0.0001` inside the
container, and worth doing once against a scratch broker to see the shape of it.

## Metrics

Add the Prometheus registry:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
```

That is all. The starter finds the `MeterRegistry` in the context and gives the library
telemetry backed by it; no configuration and no bean of your own.

Post a few orders, then:

```bash
curl -s localhost:8080/actuator/prometheus | grep '^acemq' | head -20
```

```
acemq_publish_total{exchange="orders",routing_key="order.created",transport="rabbitmq",outcome="confirmed"} 6.0
acemq_publish_duration_seconds_count{...} 6.0
acemq_consume_total{queue="orders.new",transport="rabbitmq",outcome="acked"} 2.0
acemq_consume_total{queue="orders.new",transport="rabbitmq",outcome="retried"} 4.0
acemq_messages_retried_total{queue="orders.new"} 4.0
acemq_consume_in_flight{queue="orders.new"} 0.0
```

The `outcome` tag is where the value is. `confirmed`, `unroutable` and `failed` on the
publish side; `acked`, `retried`, `dead_lettered` and `rejected` on the consume side.

## Three graphs

**Unroutable publishes.** The failure from [Tutorial 1](tutorial-first-message.md#what-happens-when-nothing-is-listening):
a message the broker confirmed and dropped because no binding matched. It is silent
everywhere else.

```promql
rate(acemq_publish_total{outcome="unroutable"}[5m])
```

Anything above zero is a binding that is wrong, and it should page.

**Retries.** The ladder working, or a downstream that is down.

```promql
rate(acemq_messages_retried_total[5m])
```

A steady low rate is normal. A step change is a downstream, and it arrives before the
dead-letter count does — which is the whole reason to watch it.

**Handler time.** The number prefetch and concurrency should be chosen from.

```promql
histogram_quantile(0.99, rate(acemq_consume_duration_seconds_bucket[5m]))
```

Prefetch is a buffer measured in messages; what matters is how long that buffer takes to
drain, which is p99 handler time times prefetch. At 50ms and prefetch 100, a consumer is
holding five seconds of work — fine for throughput, and five seconds of redelivery on an
unclean shutdown.

## Per-listener counters

Metrics are per queue. When the question is *which listener*, ask the registry:

```java
@RestController
class ListenerController {

    private final AceListenerRegistry listeners;

    ListenerController(AceListenerRegistry listeners) {
        this.listeners = listeners;
    }

    @GetMapping("/listeners")
    Map<String, Object> listeners() {
        Map<String, Object> out = new LinkedHashMap<>();
        listeners.running().forEach((id, group) -> out.put(id, Map.of(
                "queue", group.queue(),
                "consumers", group.size(),
                "prefetch", group.prefetch(),
                "acknowledged", group.acknowledged(),
                "rejected", group.rejected(),
                "retried", group.retried(),
                "inFlight", group.inFlight())));
        return out;
    }
}
```

```bash
curl -s localhost:8080/listeners | jq
```

The same object can throttle a listener that is overwhelming a downstream:

```java
listeners.get("orderListener#onOrder").ifPresent(group -> {
    group.scaleTo(1);
    group.prefetch(10);
});
```

which is a smaller and much faster intervention than a redeploy.

## The one log line to alert on

```
WARN o.a.s.b.AceListenerRegistry : listener orders still had 3 message(s) in flight after PT30S; they will be redelivered
```

That is a shutdown that could not drain. Every message it names will be redelivered, and if
the handlers are not idempotent, some of them will be handled twice. It appears at
deployment time, which is exactly when nobody is reading logs — so alert on it.

`acemq.listener.shutdown-timeout` is the dial: it needs to be longer than p99 handler time
times prefetch, and shorter than the orchestrator's grace period. Both numbers are
knowable.

Next: [testing it](tutorial-testing.md), with and without Docker.
