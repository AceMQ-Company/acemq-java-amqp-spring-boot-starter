# Observability

Health under `/actuator/health`, metrics through whatever `MeterRegistry` the application
already has, and counters on each listener.

## Health

With Actuator on the classpath, `AceMqHealthIndicator` is registered automatically.

```json
{
  "status": "UP",
  "components": {
    "aceMq": {
      "status": "UP",
      "details": {
        "transport": "rabbitmq",
        "open": true,
        "blocked": false,
        "inFlight": 3
      }
    }
  }
}
```

Four facts, chosen because they are the four worth having during an incident: whether the
connection is open, whether the broker has applied back pressure and why, how many publishes
are in flight, and which transport this is.

Turn it off with `management.health.acemq.enabled: false`. The details are visible over HTTP
only when Actuator's own `management.endpoint.health.show-details` allows it.

### A blocked connection is reported up

When RabbitMQ hits a memory or disk alarm it blocks publishing connections. This indicator
reports that as **up, with the reason**:

```json
{
  "status": "UP",
  "details": {
    "open": true,
    "blocked": true,
    "blockedReason": "low on disk space"
  }
}
```

Down would be the more dramatic choice and the wrong one. A blocked connection is the broker
protecting itself; an application that fails its own health check for it gets restarted by
an orchestrator into the same blocked broker, having thrown away whatever it was holding.
The blockage is the broker's problem to clear, and `blocked: true` is what an alert should
watch.

Down means what it says: the connection is not open.

## Metrics

Put a `MeterRegistry` in the context — every Boot application with
`spring-boot-starter-actuator` and a registry dependency has one — and the library's metrics
go to it. Nothing else is needed.

| Meter | Type | Tags |
|---|---|---|
| `acemq.publish.duration` | timer | `exchange`, `routing.key`, `transport`, `message.type`, `outcome` |
| `acemq.publish.total` | counter | as above |
| `acemq.consume.duration` | timer | `queue`, `transport`, `message.type`, `outcome` |
| `acemq.consume.total` | counter | as above |
| `acemq.consume.attempts` | distribution | `queue` |
| `acemq.consume.in.flight` | gauge | `queue` |
| `acemq.messages.retried.total` | counter | `queue` |
| `acemq.messages.dead.lettered.total` | counter | `queue` |
| `acemq.request.duration` / `.total` | timer, counter | request-response |
| `acemq.outbox.lag` / `.total` | gauge, counter | the outbox pattern |
| `acemq.pipeline.run.duration` / `.total` | timer, counter | pipelines |

The `outcome` tag is where most of the value is: `confirmed`, `unroutable` and `failed` on
the publish side; `acked`, `retried`, `dead_lettered` and `rejected` on the consume side.

Three things worth graphing on day one:

```promql
# Publishes the broker could not route: a binding is wrong, and no exception was thrown
rate(acemq_publish_total{outcome="unroutable"}[5m])

# The retry ladder working, or a downstream that is down
rate(acemq_messages_retried_total[5m])

# Handler time, which is what prefetch and concurrency should be chosen from
histogram_quantile(0.99, rate(acemq_consume_duration_seconds_bucket[5m]))
```

### Without Micrometer

If Micrometer is not on the classpath, the starter falls back to the library's own
auto-detection, which finds OpenTelemetry when it is there and is otherwise a no-op. Nothing
fails and nothing is logged about it, because an application without a metrics library has
made a choice.

To take over entirely, define a `Telemetry` bean; the starter backs off.

## Listener counters

`AceListenerRegistry` hands out the running `ConsumerGroup` for each listener id, and the
group carries counters that are per-listener rather than per-application:

```java
listeners.get("orders").ifPresent(group -> {
    log.info("acked {}, rejected {}, retried {}, in flight {}",
            group.acknowledged(), group.rejected(), group.retried(), group.inFlight());
});
```

These are the numbers to reach for when metrics say the application is slow and the question
is *which listener*.

## Logging

Two lines matter at startup, both at INFO:

```
INFO o.a.s.b.AceMqTopologyInitializer : topology (CREATE_ONLY):
  CREATE   queue orders.new (quorum)
INFO o.a.s.b.AceListenerRegistry     : listener orders consuming orders.new with 4 consumer(s), prefetch 40
```

and one at shutdown, when a drain does not finish:

```
WARN o.a.s.b.AceListenerRegistry : listener orders still had 3 message(s) in flight after PT30S; they will be redelivered
```

That warning is the one to alert on after a deployment: it is the difference between a clean
redeploy and the duplicate burst that nobody attributes to shutdown.

Set `logging.level.org.acemq=DEBUG` for the rest, which includes the "nothing to do"
topology line and each listener that was not started because its auto-startup is off.
