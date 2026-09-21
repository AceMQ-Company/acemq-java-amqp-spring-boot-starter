# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

This artifact has its own version line, starting at 0.1.0, because it tracks Spring Boot's
release train as much as it tracks AceMQ's.

## [Unreleased]

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
