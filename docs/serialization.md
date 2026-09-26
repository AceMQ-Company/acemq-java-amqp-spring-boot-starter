# Serialization, schemas and large payloads

How a payload becomes bytes, how those bytes stay readable as the type changes, and what to do
when the payload is too big to send.

| | |
|---|---|
| [Formats](#formats) | `acemq.format`, and the one dependency each other format needs |
| [A codec of your own](#a-codec-of-your-own) | The bean that everything else here replaces |
| [Avro and the registry](#avro-and-the-schema-registry) | A schema id on the wire instead of a schema |
| [Schema evolution](#schema-evolution) | Adding a field without stopping the consumers |
| [Claim check](#claim-check) | A payload too large for a broker |

[Payload encryption](security.md#payload-encryption) is also a codec, and is on the security
page because the hard part is the keys.

## Formats

```yaml
acemq:
  format: json
```

`json`, `xml`, `yaml`, `toml`, `avro`, `protobuf`, `text` and `bytes`. The starter ships JSON
and nothing else, so any other format needs its codec on the classpath:

```xml
<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-amqp-codec-yaml</artifactId>
  <version>0.7.3</version>
</dependency>
```

`-xml`, `-toml`, `-avro` and `-protobuf` are the others; `text` and `bytes` are in the core and
need nothing. Naming a format whose codec is absent fails at startup with the format named,
rather than at the first send with a missing-class error.

**JSON is the right default and staying on it is a real answer.** The formats worth moving for
are Avro and Protobuf, and the reason is schema enforcement rather than size — a JSON consumer
finds out about a renamed field when it produces a null, and an Avro one finds out when the
schema is registered.

A publisher can override the connection's format for one message type:

```java
mq.publisher("audit", "audit.entry", AuditEntry.class).asYaml().send(entry);
```

`.as("yaml")`, `.as(codec)`, `.asJson()`, `.asXml()`, `.asYaml()`, `.asText()`, `.asBytes()`. A
consumer overriding it is `ConsumerOptions.as(codec)`, which means declaring that consumer as a
bean — `@AceListener` has no codec attribute. The content type travels with the message, so a
codec that can read several formats works out which it is looking at.

## A codec of your own

The starter's codec is a bean:

```java
@Bean
@ConditionalOnMissingBean
public Codec aceMqCodec(AceMqProperties properties) {
    return Codecs.byName(properties.getFormat());
}
```

`@ConditionalOnMissingBean`, so defining a `Codec` bean replaces it — and the `AceMq` bean is
built with yours, so every publisher and every listener uses it. That one seam is how everything
else on this page is wired: the encrypting codec, the claim-check codec and the Avro codec are
all codecs that wrap another codec.

They compose, in the order you nest them:

```java
@Bean
Codec aceMqCodec(AceMqProperties properties, ClaimCheckStore store, Keyring keyring) {
    Codec format = Codecs.byName(properties.getFormat());
    Codec encrypted = EncryptedCodec.wrapping(format, keyring);
    return ClaimCheckCodec.wrapping(encrypted, store, 256 * 1024);
}
```

**That order is the one you want.** Encrypt first, then decide whether the ciphertext is too big
for the broker — so what goes to the claim-check store is already encrypted. Nesting them the
other way round stores the plaintext in the object store and encrypts the little pointer to it,
which is a lot of work for no protection at all.

`CompositeCodec` is there for the other case: reading several formats on one queue during a
migration, where the content type decides which codec handles each message.

## Avro and the schema registry

Avro has two shapes here, and the difference matters.

**A fixed schema**, when both ends were built together:

```java
@Bean
Codec aceMqCodec() {
    return AvroCodec.of(OrderPlaced.class);   // a generated SpecificRecord
}
```

`AvroCodec.of(Schema)` is the same thing from a parsed schema. The content type is
`avro/binary`, and the schema is not on the wire — both ends have to have the same one, which is
fine when they are deployed together and a trap when they are not.

**A registered schema**, when they are not:

```java
@Configuration
class Schemas {

    @Bean
    SchemaRegistry schemaRegistry(DataSource dataSource) {
        JdbcSchemaRegistry registry = new JdbcSchemaRegistry(dataSource);
        registry.createSchemaIfAbsent();
        return registry;
    }

    @Bean
    Codec aceMqCodec(SchemaRegistry registry) {
        return AvroCodec.registered(registry);
    }
}
```

Now each message carries a small schema id, the content type is
`application/vnd.acemq.avro`, and a consumer looks the id up rather than assuming. A producer
that adds a field registers a new id; consumers that have not been redeployed keep reading the
old one because the registry still has it.

`JdbcSchemaRegistry(dataSource, table)` names the table. `InMemorySchemaRegistry` is the test
implementation — `new InMemorySchemaRegistry().register(1, definition)` — and is wrong for
anything with more than one process, for the same reason the in-memory idempotency store is.

**The registry has to be shared and it has to outlive the messages.** A registry in one
service's own database that another service cannot reach is not a registry; a registry whose
rows are cleaned up is a queue full of messages nobody can decode.

## Schema evolution

The question this answers: a producer adds a field and is deployed first. What do the consumers
do?

With a registry, the consumer reads the producer's schema by id, and Avro resolves it against
the schema the consumer was compiled with. Additive changes — a new field with a default, a
widened numeric type — resolve. A removed field, a renamed field or a narrowed type do not, and
the failure is at decode rather than at a null field access later.

When the consumer's own schema is the one that should win, say so:

```java
@Bean
Codec aceMqCodec(SchemaRegistry registry) {
    return AvroCodec.registered(registry, OrderPlaced.getClassSchema());
}
```

That is the reader schema: messages are decoded *as* this shape, whatever shape they were
written in, with Avro's resolution rules in between. It is the right form for a consumer that
must keep working while producers move ahead of it, and it is the form to reach for when a
rolling deployment has both versions running at once.

`SchemaDefinition` is the registry's unit — a format, a subject and the definition text, with a
`fingerprint()` that is what the registry deduplicates on. `AvroCodec.definitionOf(schema)`
builds one from a parsed Avro schema, which is what a build-time registration step uses.

The practical rule, which no library can enforce: **add fields with defaults, never remove or
rename**. A field nobody sets any more is cheaper than a coordinated deployment across six
services.

## Claim check

A broker is not a file store. RabbitMQ will carry a 40 MB message and will do it badly: the
memory is per-message and per-consumer, the queue stops being able to page out, and prefetch
now means "hold forty of these in memory".

`ClaimCheckCodec` puts the body somewhere else and sends the key:

```java
@Configuration
class LargePayloads {

    @Bean
    ClaimCheckStore claimCheckStore() {
        return new FilesystemClaimCheckStore(Path.of("/var/lib/orders/claims"));
    }

    @Bean
    Codec aceMqCodec(AceMqProperties properties, ClaimCheckStore store) {
        return ClaimCheckCodec.wrapping(Codecs.byName(properties.getFormat()), store);
    }
}
```

Below the threshold — 64 KB by default, `wrapping(delegate, store, threshold)` to change it —
nothing happens and the message is an ordinary message. Above it, the body goes to the store and
the message carries the key. The consumer's codec fetches it back, so a handler sees the same
payload either way and never knows which path it took.

`InMemoryClaimCheckStore` is for tests. `FilesystemClaimCheckStore` works for a single host or a
shared volume. Anything else — S3, GCS, a blob container — is a `ClaimCheckStore` of your own,
which is three methods:

```java
@Component
class S3ClaimCheckStore implements ClaimCheckStore {

    private final S3Client s3;
    private final String bucket;

    @Override
    public String put(byte[] content) {
        String key = UUID.randomUUID().toString();
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
```

### Nothing deletes for you

`delete(key)` exists and the codec never calls it. It cannot: the codec has no idea whether the
message has been handled by every consumer that was going to handle it, and on a fanout with
three consumers the first one to finish is not the last. So the store grows until something
prunes it, and deciding what prunes it is part of adopting the pattern.

**A handler cannot see the key.** This is the part worth knowing before designing around it. By
the time the codec hands a handler its payload, the key has been resolved and discarded — there
is no `claim` on the envelope for the codec to have set, because a `Codec` only ever sees bytes
and never sees the envelope. So the tidy-up-from-the-consumer design does not work as written.

Three answers that do:

**Retention on the store.** A lifecycle rule on the bucket, or a `find -mtime` on the directory,
with a window longer than the longest a message can sit on a queue — dead-letter queues and
retry rungs included. Simplest by a distance, and the one to use unless there is a reason not to.

**Delete from a consumer that reads the raw bytes.** A second consumer on the same queue, or a
dedicated reaper queue, configured with the bytes codec so the key is still there:

```java
@Bean(destroyMethod = "close")
ConsumerGroup claimReaper(AceMq mq, ClaimCheckStore store) {
    return mq.consumeGroup("orders.large.reap", byte[].class, message -> {
                 String key = ClaimCheckCodec.keyOf(message.payload());
                 if (key != null) {
                     store.delete(key);
                 }
             })
             .options(ConsumerOptions.defaults().as(Codecs.byName("bytes")))
             .start();
}
```

`keyOf(body)` returns null for a message that travelled inline, which is why the null check is
not defensive padding.

**Record it on publish.** The publishing side does know: encode with the claim-check codec
yourself, read the key off the result, and put it in the envelope as an ordinary header that the
handler can then read. More moving parts, and the only one of the three that gives a handler the
key directly.

`keyOf` is also what a triage tool uses on a dead-lettered message: it answers "does the object
still exist" before anyone decides the message is replayable.

### Two behaviours worth knowing

**The threshold is inclusive, and is measured after serialisation.** A payload that encodes to
exactly 64 KB is offloaded; 64 KB minus one byte travels inline. Sizing against the object rather
than the encoded bytes is how a message that was supposed to be small turns out not to be.

**A message with no claim-check framing still decodes.** The codec recognises its own framing and
passes anything else to the delegate, so turning the claim check on does not orphan the messages
already on the queue, and a producer that has not been redeployed keeps working.

**A missing object is a permanent failure.** If the retention rule ran before the message was
handled, the decode throws `AceMqException` and will throw identically on every retry. Catch it
and rethrow `AceFatalException` rather than letting the ladder spend ten minutes on it — see
[retries](reliability.md#two-exceptions-the-ladder-treats-differently).
