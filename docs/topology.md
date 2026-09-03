# Topology

What the application expects to exist, declared in properties and applied once at startup.

```yaml
acemq:
  topology:
    exchanges:
      - name: orders
        type: topic
    queues:
      - name: orders.new
      - name: orders.audit
        type: classic
        arguments:
          x-message-ttl: 604800000
    bindings:
      - queue: orders.new
        exchange: orders
        routing-key: order.created
      - queue: orders.audit
        exchange: orders
        routing-key: "order.#"
```

Nothing declared means nothing applied — and no round trip to the broker at startup, which
on a service that scales to fifty instances is fifty questions nobody asked.

## Quorum by default

`queues: [{ name: orders.new }]` is a **durable quorum queue**. That is the library's
default everywhere, and it is the right answer for anything whose loss would be noticed: a
classic queue on a node that dies takes its unreplicated messages with it.

### Classic queues

```yaml
queues:
  - name: orders.audit
    type: classic
    arguments:
      x-message-ttl: 604800000
```

Arguments are accepted for classic queues only, and that is deliberate rather than an
oversight. The arguments people reach for first — `x-max-priority`, a queue-level
`x-message-ttl` — are the ones RabbitMQ refuses on a quorum queue, and a property that is
silently dropped is worse than one that is absent.

## The three apply modes

| `acemq.topology.apply` | What it does |
|---|---|
| `create-only` (default) | Creates what is missing. Reports what differs. Changes nothing that exists |
| `validate` | Creates nothing. Fails startup when something is missing |
| `dry-run` | Logs the plan and touches the broker not at all |

`create-only` is the only mode that is safe to run on every deployment of every instance,
which is why it is the default. It never modifies and never deletes.

`validate` is what a production environment usually wants once provisioning is somebody
else's job — Terraform, a platform team, a definitions import. The application then fails
loudly against an unprovisioned broker instead of starting, consuming nothing, and looking
healthy:

```yaml
acemq:
  topology:
    apply: validate
    fail-on-drift: true
```

`dry-run` answers "what would this deployment change?" without changing it.

## The plan

Whatever the mode, the plan is logged when there is anything to say:

```
INFO o.a.s.b.AceMqTopologyInitializer : topology (CREATE_ONLY):
  CREATE   exchange orders (topic)
  PRESENT  queue orders.new (quorum)
  DRIFT    queue orders.audit: x-message-ttl 604800000 declared, 86400000 on the broker
```

"The queue was already there" and "the queue was created just now" are different facts about
a deployment, and both are worth having in the log when a message goes missing an hour
later.

## Drift is reported, never corrected

A queue whose arguments differ from the declaration is **drift**. The starter reports it,
and with `fail-on-drift: true` refuses to start. It does not repair it.

That is not timidity. RabbitMQ will not modify a queue's arguments in place; a "repair"
means delete and recreate, which during a rolling deployment means deleting a queue that
older instances are still consuming from, losing whatever it held, and then having those
older instances fail their own declarations against the new one. The safe repair is a
migration with a new queue name, and it is not something a starter should do while an
application is coming up.

## What is not here

**No deletion.** Nothing in this starter removes an exchange, a queue or a binding. An
application that could delete a queue at startup is an application one typo away from
deleting the wrong one.

**No policies, users or permissions.** Those are management-API operations, not AMQP ones,
and they belong to
[acemq-java-rabbitmq-admin](https://github.com/AceMQ-Company/acemq-java-rabbitmq-admin) —
kept deliberately separate, because nothing in a message path should depend on a
product-specific management endpoint.

**No dead-letter wiring.** A dead-letter exchange is a queue argument
(`x-dead-letter-exchange`) and can be declared as one on a classic queue, or set by policy,
which is how RabbitMQ recommends doing it for quorum queues.
