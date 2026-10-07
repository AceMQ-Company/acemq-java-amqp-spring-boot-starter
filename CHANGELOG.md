# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

This artifact has its own version line, starting at 0.1.0, because it tracks Spring Boot's
release train as much as it tracks AceMQ's.

## [Unreleased]

### Changed

- **The library moves from 0.7.3 to 0.7.12.** `<acemq.version>` follows the libraries
  workspace's released line again; nothing in the starter's API changes with it.

## [0.2.0] - 2026-09-26

### Added

- **`acemq.topology.queues[].dead-letter`.** A queue could not be declared with a dead-letter
  exchange from `application.yml` at all, and that is the property that decides whether a message
  a handler cannot handle is kept or dropped.

  There are two routes to a dead letter and only one of them worked from properties. A consumer
  started with a retry ladder declares `acemq.dlx`, `{queue}.dlq` and `{queue}.parked` itself and
  publishes its give-ups there, so `acemq.listener.retry.enabled` was enough for that path. The
  other route is the broker's: a message that expires against an `x-message-ttl`, or one a handler
  rejects with no ladder configured, is dead-lettered only if the queue carries
  `x-dead-letter-exchange`. A consumer deliberately does not add that argument — it does not own
  the source queue and a redeclaration that guesses quorum-or-classic wrong is a
  `PRECONDITION_FAILED` that stops it starting — so it has to come from wherever the queue is
  declared, which for this starter is the properties file, which had no way to say it.

  `dead-letter: true` declares all four objects together, because they are only correct together:
  the queue with `x-dead-letter-exchange: acemq.dlx` and a routing key of `{name}.dlq`, the
  `acemq.dlx` direct exchange shared by every dead-lettering queue in the topology, and
  `{name}.dlq` and `{name}.parked` bound to it on their own names. A queue pointed at a
  dead-letter exchange nothing declares throws messages away exactly as if dead-lettering had
  never been configured.

  It works on a classic queue as well and keeps that queue's own arguments. Setting
  `x-dead-letter-exchange` in `arguments` alongside it is refused rather than overruled.

- **The docs site checks its own links.** Twenty-two pages that cross-reference each other
  heavily, and a renamed heading breaks a link silently: pandoc renders
  `](reliability.md#dead-letters)` into an anchor whether or not anything answers to it. The
  render step now fails on a link to a page or a heading that does not exist, checked against the
  markdown so the same links also work when the files are read on GitHub.

### Changed

- **`arguments` on a quorum queue is refused rather than dropped.** The library declares a quorum
  queue by name and accepts no arguments for it, so
  `{ name: orders.new, arguments: { x-message-ttl: 604800000 } }` was silently a queue with no
  time-to-live. A file that asks for both now fails at startup, naming the queue and the
  arguments, and saying to use `type: classic`. The previous behaviour was documented as
  "classic queues only" and was still a queue quietly declared without what it was given —
  found, if ever, by a disk filling up.

  This will stop an application whose configuration is already not doing what it says. That is
  the point of it.

### Documentation

- **Every pattern the library has, from Spring Boot.** The guide went from seven pages to
  sixteen. What was missing was not reference material — `acemq.*` was documented property by
  property — but any account of how the patterns the library is *for* are reached from a Boot
  application. An application could read the whole site and not learn that an idempotency store
  is a bean rather than a property.

  New pages: **[Patterns](https://acemq.org/acemq-java-amqp-spring-boot-starter/patterns.html)** — the map, and for each pattern whether `application.yml` reaches it,
  whether it replaces an auto-configured bean, or whether it is a bean of its own;
  **[Publishing](https://acemq.org/acemq-java-amqp-spring-boot-starter/publishing.html)** — publisher beans, what `send` returns, envelopes, batches, `sendAsync`, and
  back pressure on a blocked connection; **[Retries, dead letters and replay](https://acemq.org/acemq-java-amqp-spring-boot-starter/reliability.html)** — the ladder, the
  two dead-letter routes, replay, idempotency and graceful shutdown; **[Messaging patterns](https://acemq.org/acemq-java-amqp-spring-boot-starter/messaging-patterns.html)** —
  request-reply, scheduling, the transactional outbox, sagas, pipelines, ordered-per-key and
  routing slips; **[Serialization and schemas](https://acemq.org/acemq-java-amqp-spring-boot-starter/serialization.html)** — codecs, Avro with a registry, evolution and the
  claim check; **[Security](https://acemq.org/acemq-java-amqp-spring-boot-starter/security.html)** — TLS, credentials, development certificates and payload
  encryption; **[Streams](https://acemq.org/acemq-java-amqp-spring-boot-starter/streams.html)** — offsets, resuming, and why `@AceListener` cannot read one;
  **[Interceptors](https://acemq.org/acemq-java-amqp-spring-boot-starter/interceptors.html)** — registering them as beans, and what not to put in one.

- **Four things the annotation cannot reach, said plainly** rather than left to be discovered:
  an idempotency store, a per-listener retry ladder, a per-listener codec, and a stream. All four
  are `ConsumerOptions` or an offset, none is a value a properties file can hold, and all four
  have the same answer — declare that consumer as a `ConsumerGroup` bean. The pages now show that
  bean rather than implying an attribute exists.

- **The documented library version was five minors stale.** The versions table said
  `acemq-java-amqp` 0.2.10 and two dependency snippets a reader would copy said
  `acemq-amqp-test` 0.2.10, while the starter has resolved 0.7.3 since 0.1.1. The starter's own
  version in the install snippets said 0.1.0, which 0.1.1 superseded.

### Fixed

- **The configuration metadata was not generated on JDK 23 or newer.** `acemq.*` completing in an
  IDE depends on `META-INF/spring-configuration-metadata.json`, which
  `spring-boot-configuration-processor` writes during compilation. javac ran any processor it
  found on the compile classpath until JDK 21 deprecated that and JDK 23 turned it off: a build
  that names no processor now gets no annotation processing at all. So on a current JDK this
  module compiled cleanly, passed everything, and produced a jar with no metadata in it.

  Nothing caught it. CI's matrix runs 17, 21 and 25, and the step that asserts the metadata exists
  was conditioned on the 17 leg — so the two legs that had lost it were never asked. No published
  release is affected, because the release job builds on 17; what was one JDK bump away was
  publishing without it and not finding out.

  The processor is now named in `annotationProcessorPaths`, which is an explicit request and so
  runs on every JDK, and the CI step asks on every leg rather than on one. Verified on 3.5.7 and
  4.1.0 under JDK 25.

- **Three documented facts that were not true.** `ConsumerGroup` was described as carrying
  `pause`, `resume` and a dead-letter counter; it has none of the three — pausing is
  `mq.pauseConsuming()` on the connection, `scaleTo(0)` is refused, and dead letters are a metric.
  The in-memory transport was described as not implementing dead-lettering, which meant the retry
  ladder read as untestable without Docker when in fact it is not — the transport claims
  `DEAD_LETTER_NATIVE` for queue-level expiry, which is what the ladder is built from. What it
  genuinely does not do is route a *rejected* message to a dead-letter exchange, and that
  distinction is now what the page says.

## [0.1.1] - 2026-09-21

### Changed

- **The library moves from 0.2.10 to 0.7.3.** 0.1.0 resolved
  `acemq-amqp-core` 0.2.10, which was five minor versions behind the library at the time it
  was published, so an application that added this starter got an AceMQ five releases old
  without anything saying so.

  What an application was missing, and now gets:

  - **`.concurrency(N)` actually creating N consumers.** RabbitMQ's client sizes its
    `ConsumerWorkService` to `availableProcessors()` and shares it across channels, so
    `.concurrency(50)` on a four-core pod silently ran four.
  - **Shutdown finishing inside its budget.** `ConsumerGroup.close()` gave each member the
    full timeout and `AceMq.close()` gave each group its own, multiplying across two levels:
    three groups of two consumers took 15 seconds against an 800ms budget. Now one shared
    deadline.
  - **A health check that returns when the broker is blocked.** It previously reported
    `Degraded` for a blocked connection, which poisons an aggregate health endpoint.
  - **`com.rabbitmq:amqp-client` at 5.36.0**, carrying the fixes for CVE-2026-69219,
    CVE-2026-69220, CVE-2026-63337, CVE-2026-75516, CVE-2026-63336, CVE-2026-63335 and
    CVE-2026-61634.
  - **The Avro codec** (0.5.0) and **batch publish**, `Publisher.sendAll` (0.6.0), neither of
    which existed at 0.2.10.
  - The encryption framing all five libraries converged on, and the claim-check and
    Protobuf changes that came with 0.6.0. Those three existed at 0.2.10; what is new is
    that they now agree across the family.

  No API changed across those five minors. The starter's own surface is identical, and the
  suite passes untouched on Spring Boot 3.5, 4.0 and 4.1.

### Fixed

- **A tag now publishes this starter.** There was no release workflow, so pushing a tag did
  nothing and 0.1.0 reached the Maven feed because somebody ran `mvn deploy` from their own
  machine. The release now runs the suite, refuses a version the changelog does not record,
  counts all five modules into the feed before pushing, and resolves the starter from an
  empty local repository afterwards.

## [0.1.0] - 2026-09-03

### Added

- `AceMqAutoConfiguration`: an `AceMq` connection, a `ConnectionConfig`, the codec named by
  `acemq.format`, telemetry, the declared topology, the listener registry and a health
  indicator. Every bean backs off when the application defines its own.
- `AceMqProperties`: everything under `acemq.*` — URL, credentials, virtual host, client
  name, the three timeouts, publisher confirms, outstanding-publish limit, format, TLS,
  topology and listener defaults including a retry ladder.
- `@AceListener`: consumes a queue with the annotated method. Takes the payload or the whole
  `Message<T>`; prefetch, concurrency, id and auto-startup default to `acemq.listener.*` and
  can be set per listener. Queue names and `autoStartup` resolve property placeholders.
- `AceListenerRegistry`: a `SmartLifecycle` that starts listeners after the context is
  built and drains them on shutdown. Hands out the running `ConsumerGroup` by listener id,
  for scaling, pausing and counters.
- `AceMqTopologyInitializer`: applies `acemq.topology` once, in `CREATE_ONLY`, `VALIDATE`
  or `DRY_RUN`, logging the plan and optionally failing on drift.
- `AceMqHealthIndicator`: open, blocked with its reason, publishes in flight, transport
  name. A blocked connection is reported up, because a broker applying back pressure is not
  a reason to have an orchestrator restart the application into the same broker.
- **Spring Boot 4 support, from the same starter.** Health ships as two modules,
  `acemq-spring-boot-health-boot3` and `acemq-spring-boot-health-boot4`, each compiled
  against its own line and guarded by a `@ConditionalOnClass` on that line's health API.
  Both are in the starter; exactly one ever matches.
- `acemq-spring-boot-starter-tests`, not published: a real `@SpringBootTest` over the
  starter's own classpath, run once per Boot line by the CI matrix.
- Configuration metadata, so `acemq.*` completes in an IDE.

### Notes

- Built and tested on Spring Boot 3.5.7, 4.0.6 and 4.1.0, on Java 17. Boot artifacts are
  `provided`, so this never drags a Boot version into an application.
- Boot 4 moved the health contributor API out of `spring-boot-actuator`
  (`org.springframework.boot.actuate.health`) into `spring-boot-health`
  (`org.springframework.boot.health.contributor`), and the two `AbstractHealthIndicator`
  classes share no ancestor. Everything else this starter uses is source-compatible across
  the two lines, which is why only health is split.
- Two things the split depends on, both found by running it rather than by reasoning about
  it. The `@ConditionalOnClass` has to sit on the auto-configuration class, not on its bean
  method: Boot filters candidates from ASM metadata before loading them, and reading a bean
  method would resolve its return type — whose supertype is the missing one. And the two
  modules must use different package names; with identical fully-qualified names the first
  jar on the classpath wins, its condition evaluates false on the other line, and the
  application silently comes up with no health indicator.
- Testcontainers is pinned to 1.21.4 ahead of Boot's BOM. The 1.21.3 that Boot manages
  negotiates Docker API 1.32, which Docker Desktop refuses with an HTTP 400 that surfaces as
  "could not find a valid Docker environment".
