# Configuration

Every property this starter reads, what it does, and what it defaults to. All of them live
under `acemq.`, and all of them appear in an IDE's completion because the module ships
Spring's configuration metadata.

## Connection

| Property | Default | What it does |
|---|---|---|
| `acemq.enabled` | `true` | Configure AceMQ at all. Off, no connection is made and no listener runs |
| `acemq.url` | `amqp://localhost:5672` | Broker URL. The scheme picks the transport, so `amqps://` is how TLS is asked for |
| `acemq.username` | — | Username. Unset, credentials in the URL are used |
| `acemq.password` | — | Password |
| `acemq.virtual-host` | transport default | Virtual host |
| `acemq.client-name` | `spring.application.name` | Connection name in the broker's management UI |
| `acemq.connection-timeout` | `10s` | TCP and protocol handshake |
| `acemq.confirm-timeout` | `30s` | How long a publish waits for its confirm before failing |
| `acemq.blocked-timeout` | `30s` | How long publishing blocks when the broker has applied back pressure |
| `acemq.publisher-confirms` | `true` | Publisher confirms |
| `acemq.max-outstanding-publishes` | `10000` | Upper bound on unconfirmed publishes in flight |
| `acemq.format` | `json` | Default codec: json, xml, yaml, toml, avro, protobuf, text, bytes |

**Credentials in the URL survive.** Setting neither `username` nor `password` leaves
whatever is in `amqp://user:pass@host` alone. This is a property the mapping is tested for,
because the natural implementation passes two nulls through and quietly connects as a guest,
and the failure that produces is an authentication error against a broker whose
configuration is correct.

**`acemq.format` needs its codec on the classpath.** The starter ships JSON. Any other
format is one dependency away — `acemq-amqp-codec-yaml`, `-xml`, `-toml`, `-avro`,
`-protobuf` — and naming a format whose codec is missing fails at startup rather than at
the first send.

## TLS

| Property | Default | What it does |
|---|---|---|
| `acemq.tls.mode` | `disabled` | `disabled`, `required` or `insecure` |
| `acemq.tls.keystore` | — | Directory holding `keystore.p12` and `truststore.p12` |
| `acemq.tls.keystore-password` | — | Password for both stores |
| `acemq.tls.allow-development-certificates` | `false` | Accept certificates carrying AceMQ's development marker |

```yaml
acemq:
  url: amqps://broker.internal:5671
  tls:
    mode: required
    keystore: /etc/acemq/certs
    keystore-password: ${KEYSTORE_PASSWORD}
```

`required` verifies the certificate chain and the hostname. `insecure` verifies neither and
exists for a development broker with a self-signed certificate.

**There is no `verify-hostname: false`.** Disabling verification is spelled `insecure`, and
that is deliberate: a boolean in a properties file reads exactly like the lines around it,
and the one that turned verification off for an afternoon in 2024 is still there. A word
that says what it is survives a code review.

`allow-development-certificates` cannot silently weaken a real deployment. A production
certificate does not carry AceMQ's development marker, so the option only accepts the
certificates that announce themselves as untrustworthy.

## Topology

| Property | Default | What it does |
|---|---|---|
| `acemq.topology.apply` | `create-only` | `create-only`, `validate` or `dry-run` |
| `acemq.topology.fail-on-drift` | `false` | Fail startup when the broker differs from the declaration |
| `acemq.topology.exchanges[].name` | — | Exchange name |
| `acemq.topology.exchanges[].type` | `topic` | `direct`, `topic`, `fanout` or `headers` |
| `acemq.topology.queues[].name` | — | Queue name |
| `acemq.topology.queues[].type` | `quorum` | `quorum` or `classic` |
| `acemq.topology.queues[].arguments` | empty | Broker arguments, classic queues only |
| `acemq.topology.bindings[].queue` | — | Queue to bind |
| `acemq.topology.bindings[].exchange` | — | Exchange to bind it to |
| `acemq.topology.bindings[].routing-key` | `""` | Routing key, or the pattern for a topic exchange |

Nothing declared means nothing applied, and no round trip to the broker at startup.
[Topology](topology.md) covers the apply modes and drift.

## Listeners

These are the defaults; every `@AceListener` can override the first three.

| Property | Default | What it does |
|---|---|---|
| `acemq.listener.prefetch` | `100` | Unacknowledged messages allowed per consumer |
| `acemq.listener.concurrency` | `1` | Consumers per listener |
| `acemq.listener.auto-startup` | `true` | Start listeners with the application context |
| `acemq.listener.shutdown-timeout` | `30s` | How long shutdown waits for in-flight handlers |
| `acemq.listener.requeue-on-failure` | `false` | Requeue a failed message rather than dead-lettering it |

**Prefetch 100 is a starting point, not an answer.** The right number is a function of
handler time and message size, and the only way to find it is to measure —
[acemq-java-amqp-workloads](https://github.com/AceMQ-Company/acemq-java-amqp-workloads)
exists for exactly that.

**`shutdown-timeout` is the difference between a clean redeploy and a burst of
duplicates.** A message still in a handler when the connection closes is redelivered.

## Retries

| Property | Default | What it does |
|---|---|---|
| `acemq.listener.retry.enabled` | `false` | Enable the ladder |
| `acemq.listener.retry.max-attempts` | `3` | Total attempts, the first included |
| `acemq.listener.retry.initial-delay` | `1s` | Delay before the second attempt |
| `acemq.listener.retry.max-delay` | `1m` | Ceiling on the delay |
| `acemq.listener.retry.multiplier` | `2.0` | Each delay is the previous times this |
| `acemq.listener.retry.jitter` | `0` | Random spread, 0 to 1, to break up retry storms |
| `acemq.listener.retry.give-up-after` | — | Give up on a message older than this, whatever attempt it is on |

```yaml
acemq:
  listener:
    retry:
      enabled: true
      max-attempts: 5
      initial-delay: 2s
      max-delay: 30s
      jitter: 0.2
      give-up-after: 10m
```

Off by default, because a retry nobody chose is a retry nobody has thought about. What the
ladder does when it is on is the reason the library exists: the message is **republished
with a delay** rather than slept on inside the handler. A `Thread.sleep` in a consumer
blocks its channel, and everything prefetched behind it waits with it —
[Listeners](listeners.md#when-a-handler-throws) has the arithmetic.

## Health

| Property | Default | What it does |
|---|---|---|
| `management.health.acemq.enabled` | `true` | Register the health indicator |

Actuator's own `management.endpoint.health.show-details` decides whether the details
described in [Observability](observability.md) are visible over HTTP.

## A complete file

```yaml
spring:
  application:
    name: orders-service

acemq:
  url: amqps://broker.internal:5671
  username: ${BROKER_USER}
  password: ${BROKER_PASSWORD}
  virtual-host: /orders
  confirm-timeout: 10s
  format: json

  tls:
    mode: required
    keystore: /etc/acemq/certs
    keystore-password: ${KEYSTORE_PASSWORD}

  topology:
    apply: validate          # somebody else provisions; fail rather than create
    fail-on-drift: true
    exchanges:
      - { name: orders, type: topic }
    queues:
      - { name: orders.new }
      - { name: orders.audit, type: classic, arguments: { x-message-ttl: 604800000 } }
    bindings:
      - { queue: orders.new,   exchange: orders, routing-key: order.created }
      - { queue: orders.audit, exchange: orders, routing-key: "order.#" }

  listener:
    prefetch: 40
    concurrency: 4
    shutdown-timeout: 45s
    retry:
      enabled: true
      max-attempts: 5
      initial-delay: 2s
      max-delay: 30s
      jitter: 0.2
```
