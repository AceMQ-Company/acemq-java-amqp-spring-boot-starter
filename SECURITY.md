# Reporting a vulnerability

Email **security@acemq.com** with what you found and how to reproduce it. Please do
not open a public issue for anything exploitable.

You should get an acknowledgement within two working days, and an assessment of
whether it is a vulnerability, what is affected, and a rough timeline within a week.
If a fix is warranted, we will tell you when it is released and credit you unless you
would rather we did not.

## What is in scope

The starter and its auto-configuration: `acemq-spring-boot-starter`,
`acemq-spring-boot-autoconfigure`, and the Boot 3 and Boot 4 health modules.

Things worth reporting even if they feel minor:

- A property binding that weakens the connection's security compared with what was
  configured — TLS not required when the properties asked for it, hostname
  verification quietly off, development certificates accepted without the explicit
  opt-in.
- A broker password, keystore password or encryption key appearing in a log line, an
  exception message, or the output of an actuator endpoint. `/actuator/configprops`
  and `/actuator/env` are the ones to check.
- A health or metrics contribution that exposes more than liveness, readiness and
  counters — queue contents, credentials, or message bodies.
- An annotated listener that acknowledges a message before the method has returned
  successfully, since that turns a crash into a silently dropped message.
- Auto-configuration that activates on a classpath it should not, or that overrides a
  bean the application defined deliberately.

## What is not

- **Actuator endpoints being reachable.** Exposing them is Spring Boot's decision and
  yours; the defaults are Boot's. Securing them is
  [Spring Security's job](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).
- **A password in `application.yml`.** Where configuration lives is yours to decide.
- **Vulnerabilities in Spring Boot or Spring Framework** — report those to VMware
  Tanzu.
- **Vulnerabilities in `acemq-java-amqp`** — report those against
  [that repository](https://github.com/AceMQ-Company/acemq-java-amqp), or here if you
  are unsure which it is.
- Findings from a scanner with no demonstrated impact.

## Supported versions

Pre-1.0, only the latest release. There are no maintenance branches yet, so a fix
means a new patch version.

## What this starter does not do for you

It wires the library into an application context. It does not secure your actuator
endpoints, decide where your secrets live, or authorise who may publish what.
