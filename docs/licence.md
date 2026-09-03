# Licence and warranty

The AceMQ Spring Boot starter is
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). You may use it in
production, commercially, without asking and without paying.

## No warranty

The libraries are provided **"as is", without warranties or conditions of any kind**, and
the authors and contributors accept no liability for damages arising from their use.

That is not a notice added here for comfort — it is
[section 7](https://www.apache.org/licenses/LICENSE-2.0#no-warranty) and
[section 8](https://www.apache.org/licenses/LICENSE-2.0#no-liability) of the licence, and it
is the same footing as every other Apache-licensed dependency already running in your
systems.

Practically, it means what it means for any open-source library you depend on: **test it
against your workload before you rely on it.** The integration suite runs against a real
RabbitMQ 4.x broker in a container, and what is covered is stated plainly in the
documentation, including the parts that are deliberately not here.

Two things about this artifact in particular are worth reading before it configures a
production application:

- It **creates topology** at startup by default. `acemq.topology.apply: create-only`
  creates what is missing on whatever broker the URL points at. It never modifies and never
  deletes, and [Topology](topology.md) explains the other two modes — but an application
  pointed at the wrong broker will create queues there.
- It **starts consumers**. An instance that starts is an instance taking messages off a
  queue, including a batch or migration instance of the same application that was not meant
  to. `acemq.enabled: false` and `acemq.listener.auto-startup: false` both exist for that.

## If you need more than a licence gives you

Warranties, indemnity, response times and someone accountable come from a contract, not from
a licence. That is what [AceMQ Enterprise support](https://acemq.com) is for: architecture
review, production readiness, TLS and permission design, and incident response.

The libraries are complete and free to use without it, and are not crippled to sell it.

## Trademarks

The licence grants no trademark rights
([section 6](https://www.apache.org/licenses/LICENSE-2.0#trademarks)).

**RabbitMQ is a trademark of Broadcom Inc. and/or its subsidiaries.** AceMQ is an
independent project, is not affiliated with, endorsed by or sponsored by Broadcom, and
references to RabbitMQ describe compatibility only.

**Spring and Spring Boot are trademarks of Broadcom Inc. and/or its subsidiaries.** This
starter is an independent project. It is not part of the Spring project, not endorsed by it,
and the word "starter" describes the artifact's shape — a pom that pulls in a working set of
dependencies — as the Spring Boot documentation defines it.

AMQP is an open standard maintained by OASIS.

## Contributions

Contributions are accepted under the same licence, per
[section 5](https://www.apache.org/licenses/LICENSE-2.0#contributions): anything you
deliberately submit for inclusion is licensed to the project under Apache-2.0 unless you
state otherwise.
