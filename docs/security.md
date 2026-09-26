# Security

Four separate things, often confused with each other:

| | |
|---|---|
| [**Transport security**](#tls) | TLS on the connection to the broker. Properties |
| [**Credentials**](#credentials) | Who the application connects as, and where the secret comes from |
| [**Payload encryption**](#payload-encryption) | The message body, encrypted so the broker cannot read it. A `Codec` bean |
| [**Exposure**](#what-the-application-exposes) | What the starter puts on an actuator endpoint, and what to do about it |

TLS protects the message in transit and stops at the broker. Payload encryption protects it
from the broker as well, and from anybody reading a backup of it. They are not alternatives:
an application handling card data usually wants both.

## TLS

The scheme in the URL asks for TLS; `acemq.tls.mode` says how strictly.

```yaml
acemq:
  url: amqps://broker.internal:5671
  tls:
    mode: required
    keystore: /etc/acemq/certs
    keystore-password: ${KEYSTORE_PASSWORD}
```

| Property | Default | What it does |
|---|---|---|
| `acemq.tls.mode` | `disabled` | `disabled`, `required` or `insecure` |
| `acemq.tls.keystore` | — | Directory holding `keystore.p12` and `truststore.p12` |
| `acemq.tls.keystore-password` | — | Password for both stores |
| `acemq.tls.allow-development-certificates` | `false` | Accept certificates carrying AceMQ's development marker |

`required` verifies the certificate chain and the hostname. With no `keystore` it verifies
against the JVM's own trust store, which is the right answer for a broker with a certificate
from a public authority. With a `keystore` it verifies against the truststore in that
directory and presents the client certificate from the keystore, which is the right answer for
an internal certificate authority and for mutual TLS.

`insecure` verifies neither the chain nor the hostname. It exists so that a development broker
with a self-signed certificate is one word rather than an afternoon, and it should never reach
an environment where the broker holds anything real.

**There is no `verify-hostname: false`.** Disabling verification is spelled `insecure`, and
that is deliberate. A boolean in a properties file reads exactly like the lines around it, and
the one that turned verification off for an afternoon in 2024 is still there. A word that says
what it is survives a code review.

`mode: disabled` against an `amqps://` URL does not quietly downgrade to plaintext — the
connection fails. A URL that asks for TLS and a mode that refuses it are a contradiction, and
guessing which half was meant is how an application ends up sending credentials in the clear
while its configuration says `amqps`.

### Development certificates

A self-signed certificate that works in development is also a self-signed certificate that
works in production, which is the problem. The library's answer is a marker: certificates
generated for development carry the string

```
ACEMQ DEVELOPMENT ONLY - DO NOT TRUST
```

in their subject, and `mode: required` refuses them unless
`allow-development-certificates: true` is also set.

That option cannot silently weaken a real deployment. A certificate from a real authority does
not carry the marker, so the option only ever accepts the certificates that announce
themselves as untrustworthy. Leaving it set in a production profile is harmless in the sense
that it changes nothing; it is still worth not doing, because the next person reads it as a
statement about the environment.

Generating a set is a library concern rather than a starter one:

```java
DevelopmentCertificates.Result certs = new DevelopmentCertificates()
        .generate(Path.of("target/certs"), "localhost", "acemq-dev".toCharArray(), Duration.ofDays(30));

certs.directory();   // holds keystore.p12 and truststore.p12
certs.expiry();
```

`acemq-security-dev` also carries a Maven mojo, so the same thing can be bound to a build
phase rather than written into a test fixture. And
`new DevelopmentCertificates().rabbitMqConfiguration(pathInsideTheContainer)` returns the
`rabbitmq.conf` fragment that makes a broker present the certificate it just generated — which
is what makes a Testcontainers broker speak `amqps` without a checked-in certificate. See
[Testing](testing.md).

The default password, `acemq-dev`, is a constant in the library. It is not a secret and is not
pretending to be one.

### A development profile

```yaml
# application-local.yml
acemq:
  url: amqps://localhost:5671
  tls:
    mode: required
    keystore: target/certs
    keystore-password: acemq-dev
    allow-development-certificates: true
```

`required` rather than `insecure`, even locally. Verifying against a real truststore in
development is what makes a certificate problem show up on a laptop instead of in a
deployment, and the development marker is what keeps this file from being a template somebody
copies into staging.

## Credentials

```yaml
acemq:
  username: ${BROKER_USER}
  password: ${BROKER_PASSWORD}
```

Ordinary Spring property resolution, so everything Boot can do applies: environment
variables, a `SPRING_CONFIG_IMPORT` of a mounted file, Spring Cloud Vault, a
`ConfigDataLoader` of your own. The starter reads two strings and does not care where Boot
found them.

**Credentials in the URL survive.** Setting neither `username` nor `password` leaves whatever
is in `amqp://user:pass@host` alone. The mapping is tested for it, because the natural
implementation passes two nulls through and quietly connects as `guest` — and the failure that
produces is an authentication error against a broker whose configuration is correct.

Do not put a password in `application.yml`. Boot's `${...}` costs nothing, and a password in a
properties file is a password in the image, in the build cache and in whatever scrapes the
repository.

### Credentials that change while the application is running

A broker credential fetched from Vault with a lease, or a short-lived token, changes after the
connection is made. `acemq.username` and `acemq.password` are read once, when the
`ConnectionConfig` bean is built, so they cannot express that.

The library can: `Security.withCredentials(CredentialsProvider)` is asked each time it
connects. The starter does not map a property onto it — there is no sensible property for "call
this code" — so reach it by defining the `ConnectionConfig` bean yourself. Every bean in the
auto-configuration backs off when the application defines its own, and this one is the
intended seam:

```java
@Configuration
class BrokerConnection {

    /**
     * Replaces the auto-configured ConnectionConfig. Everything downstream — the AceMq bean,
     * the listeners, health — is built from this one.
     */
    @Bean
    ConnectionConfig aceMqConnectionConfig(
            AceMqProperties properties, Environment environment, VaultOperations vault) {

        Security security = Security.fromKeystore(properties.getTls().getKeystore())
                .keystorePassword(properties.getTls().getKeystorePassword())
                .withCredentials(() -> {
                    VaultResponse lease = vault.read("rabbitmq/creds/orders-service");
                    return Credentials.of(
                            (String) lease.getRequiredData().get("username"),
                            (String) lease.getRequiredData().get("password"));
                });

        // The auto-configuration fills the client name in from spring.application.name when
        // acemq.client-name is unset. That code is not running any more, so do it here —
        // otherwise every connection in the broker's UI is called "acemq".
        String clientName = properties.getClientName() != null
                ? properties.getClientName()
                : environment.getProperty("spring.application.name", "spring-boot");

        return ConnectionConfig.url(properties.getUrl())
                .clientName(clientName)
                .connectionTimeout(properties.getConnectionTimeout())
                .confirmTimeout(properties.getConfirmTimeout())
                .blockedTimeout(properties.getBlockedTimeout())
                .maxOutstandingPublishes(properties.getMaxOutstandingPublishes())
                .security(security)
                .build();
    }
}
```

Note what this costs, because it is the general cost of overriding an auto-configured bean:
this method now owns the whole mapping. Neither `acemq.publisher-confirms` nor
`acemq.virtual-host` is read above, so setting them in the file would now do nothing, and a
property added to `AceMqProperties` in a later version is not picked up until this method is
updated. Take `AceMqProperties` as a parameter, as here, and copy across every one you care
about rather than hard-coding values — that way the file still configures the connection and
only the credentials are code. `AceMqConnections.from(properties)` is the starter's own
mapping and is public; read it to see what a complete copy looks like.

`CredentialsProvider` has three ready-made forms for the cases that are not Vault:

```java
CredentialsProvider.of("orders-service", secret);
CredentialsProvider.fromEnvironment("BROKER_USER", "BROKER_PASSWORD");
CredentialsProvider.fromFile(Path.of("/var/run/secrets/broker"));
```

`fromFile` re-reads the file, so a Kubernetes secret remounted with a new value is picked up on
the next connection without a restart. `Credentials.token(token)` is the shape for a broker
using a token rather than a username and password.

## Payload encryption

TLS ends at the broker. A message on a queue is plaintext on the broker's disk, in its
management UI, and in whatever backs it up. For a payload holding card data, medical data or
anything a regulator has an opinion about, that is often not good enough.

`EncryptedCodec` wraps another codec: the payload is serialised as usual, then encrypted, and
the result is a body the broker cannot read and a content type that says so.

The starter's codec is a bean with `@ConditionalOnMissingBean`, so defining your own replaces
it — and everything built from it, publishers and listeners alike, uses it:

```java
@Configuration
class Encryption {

    @Bean
    Codec aceMqCodec(AceMqProperties properties, Keyring keyring) {
        return EncryptedCodec.wrapping(Codecs.byName(properties.getFormat()), keyring);
    }

    /**
     * Two keys: the one messages are written with, and the one they used to be written with.
     * Both are needed for as long as a message written under the old one might still be on a
     * queue — which, for a queue with a week's retention, is a week.
     */
    @Bean
    Keyring keyring(
            @Value("${payments.keys.current}") String current,
            @Value("${payments.keys.previous}") String previous) {
        return Keyring.builder()
                .add("payments-2026-06", Keys.fromBase64(previous))
                .current("payments-2026-09", Keys.fromBase64(current))
                .build();
    }
}
```

`Keyring.of(id, key)` is the single-key form, for an application that has not rotated yet.

**Rotation is the reason the keyring is a keyring.** Each encrypted body records the id of the
key that wrote it, so a consumer holding both keys reads old and new messages without knowing
which is which, while every new message is written with `current`. Retire a key only once
nothing on any queue can still be holding a message written under it; a body whose key id is
no longer in the ring fails to decode, loudly, rather than being handled as something else.

```java
EncryptedCodec.keyIdOf(body);       // which key wrote this, without decrypting it
EncryptedCodec.CONTENT_TYPE;        // application/vnd.acemq.encrypted
```

`keyIdOf` is what a triage tool uses on a message in a dead-letter queue: it answers "can this
consumer even read it" before anyone tries.

Keys themselves: `Keys.generate()` for a new one, `Keys.fromBase64` / `Keys.toBase64` to move
one in and out of a secret store, `Keys.fromBytes` for a KMS that hands back raw material. The
key is a `javax.crypto.SecretKey` and the starter never sees it — it goes into the `Keyring`
bean and stays there.

**A `@Value` on a base64 key is the example, not the recommendation.** It is short enough to
read. In a real deployment the two `@Value`s are a Vault lookup or a KMS decrypt, for the same
reason `acemq.password` should not be in the file.

### Encrypting only some messages

Replacing the `Codec` bean encrypts everything on the connection. When only one message type
needs it, put the codec on the publisher and the consumer instead of on the connection:

```java
@Bean(destroyMethod = "close")
Publisher<Payment> paymentPublisher(AceMq mq, Codec encrypting) {
    return mq.publisher("payments", "payment.taken", Payment.class).as(encrypting);
}

@Bean(destroyMethod = "close")
ConsumerGroup payments(AceMq mq, Codec encrypting, PaymentHandler handler) {
    return mq.consumeGroup("payments.new", Payment.class, handler::handle)
             .options(ConsumerOptions.defaults().as(encrypting))
             .start();
}
```

`@AceListener` has no codec attribute, so an annotated listener always uses the connection's
codec. A queue whose messages are encrypted and whose consumer is an annotation therefore
needs the connection-wide form above — or the consumer declared as a bean, as here.

The encrypted content type is deliberately not something the plain JSON codec claims it can
decode, so a consumer configured with the wrong codec fails at the first message with a clear
error rather than handing a handler nonsense.

## What the application exposes

The health indicator reports the broker URL's transport, whether the connection is open,
whether it is blocked and why, and how many publishes are in flight. None of that is a
credential, and the URL's user info is not among it — but "which broker, in which state" is
more than an unauthenticated endpoint should say about your infrastructure.

Actuator's own settings decide who sees it:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: when-authorized
      show-components: when-authorized
```

`show-details: always` on a publicly reachable actuator is the mistake worth naming. The
starter does not choose for you, because Actuator already has these properties and a starter
overriding them would be a second place to look.

A [replay endpoint](reliability.md#replay) is the other one. It republishes production traffic
and belongs behind an authenticated role, not behind `include: "*"`.

## Reporting a problem

Security issues in this starter go to the address in
[SECURITY.md](https://github.com/AceMQ-Company/acemq-java-amqp-spring-boot-starter/blob/main/SECURITY.md),
not to the issue tracker.
