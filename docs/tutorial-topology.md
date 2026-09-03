# Tutorial 2: declaring the topology

Twenty minutes. Continues from [Tutorial 1](tutorial-first-message.md).

By the end: a topology with three queues, a drift you caused on purpose, and the
configuration a production deployment uses when the broker is provisioned by somebody else.

## Adding to the topology

An audit queue that keeps everything for a week, and a second consumer for cancellations:

```yaml
acemq:
  topology:
    exchanges:
      - name: orders
        type: topic
    queues:
      - name: orders.new
      - name: orders.cancelled
      - name: orders.audit
        type: classic
        arguments:
          x-message-ttl: 604800000    # 7 days
    bindings:
      - queue: orders.new
        exchange: orders
        routing-key: order.created
      - queue: orders.cancelled
        exchange: orders
        routing-key: order.cancelled
      - queue: orders.audit
        exchange: orders
        routing-key: "order.#"
```

Restart:

```
INFO o.a.s.b.AceMqTopologyInitializer : topology (CREATE_ONLY):
  PRESENT  exchange orders (topic)
  PRESENT  queue orders.new (quorum)
  CREATE   queue orders.cancelled (quorum)
  CREATE   queue orders.audit (classic)
  PRESENT  binding orders.new <- orders [order.created]
  CREATE   binding orders.cancelled <- orders [order.cancelled]
  CREATE   binding orders.audit <- orders [order.#]
```

`PRESENT` and `CREATE` are different facts about this deployment, and both are in the log.
An hour later, when a message has gone somewhere unexpected, the question "did this instance
create that queue?" has an answer.

Restart again and the whole thing is silent: no changes, nothing to say.

## Why the audit queue is classic

`x-message-ttl` as a queue argument is refused by RabbitMQ on a quorum queue. The starter
accepts `arguments` on classic queues only, rather than accepting them everywhere and
dropping them where they cannot apply — a property that is silently ignored is worse than
one that does not exist, because the first is discovered in production and the second at
startup.

For a quorum queue that needs a TTL, RabbitMQ's own answer is a policy, which is a
management-API operation and belongs to
[acemq-java-rabbitmq-admin](https://github.com/AceMQ-Company/acemq-java-rabbitmq-admin),
not to the message path.

## Causing drift on purpose

In the management UI, delete `orders.audit`. Recreate it by hand — **Add a new queue**,
name `orders.audit`, type Classic, and argument `x-message-ttl = 86400000` (one day, not
seven).

Restart:

```
INFO o.a.s.b.AceMqTopologyInitializer : topology (CREATE_ONLY):
  DRIFT    queue orders.audit: x-message-ttl 604800000 declared, 86400000 on the broker
```

The application starts. The queue is not touched.

Now make drift fatal:

```yaml
acemq:
  topology:
    fail-on-drift: true
```

```
***************************
APPLICATION FAILED TO START
***************************

Description:

the broker's topology differs from the declared one, and acemq.topology.fail-on-drift is set:
  DRIFT    queue orders.audit: x-message-ttl 604800000 declared, 86400000 on the broker
```

### Why it is not corrected

Because RabbitMQ will not change a queue's arguments in place. A "repair" is delete and
recreate, and during a rolling deployment that means deleting a queue older instances are
still consuming from, losing what it held, and then having those instances fail their own
declarations against the new one.

The safe repair is a migration with a new queue name — `orders.audit.v2`, bound alongside,
consumers moved, the old one drained and removed — and that is not something a starter
should do while an application is coming up.

Delete `orders.audit` in the UI and restart to get back to a clean state.

## The production configuration

On a platform where Terraform or a definitions import provisions the broker, an application
that creates queues is an application that can create the wrong ones. Turn creation off:

```yaml
acemq:
  topology:
    apply: validate
    fail-on-drift: true
```

Delete `orders.cancelled` in the management UI, then restart:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

...
  MISSING  queue orders.cancelled
```

That failure is the point. The alternative — an application that starts, consumes nothing,
and reports itself healthy — is the outage that takes an afternoon to find, because every
graph shows a service that is up and a queue that is empty.

## Seeing the plan without applying it

```yaml
acemq:
  topology:
    apply: dry-run
```

The plan is logged and the broker is untouched. This answers "what would this deployment
change?" before it changes it, and is worth a run in staging before any release that alters
the topology block.

## A summary of the three modes

| Mode | Creates | Fails on missing | Use |
|---|---|---|---|
| `create-only` | yes | no | Development, and any environment where the application owns its topology |
| `validate` | no | yes | Production with external provisioning |
| `dry-run` | no | no | Seeing what a release would do |

None of them ever deletes or modifies anything. That is not configurable, and it is the
property that makes `create-only` safe to run on every instance of every deployment.

Next: [what happens when the handler fails](tutorial-retries.md).
