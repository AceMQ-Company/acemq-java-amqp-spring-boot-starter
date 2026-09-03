# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

This artifact has its own version line, starting at 0.1.0, because it tracks Spring Boot's
release train as much as it tracks AceMQ's.

## [Unreleased]

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
