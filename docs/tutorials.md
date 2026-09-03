# Tutorials

Step by step, in order, each one ending with something that runs.

The [guide](index.md) explains how a thing works and why it is that way. These are the other
shape: start with an empty project, finish with a service you can point at a broker.

| | | | |
|---|---|---|---|
| 1 | [Your first message](tutorial-first-message.md) | A service that publishes and consumes, and the management UI showing both | 15 min |
| 2 | [Declaring the topology](tutorial-topology.md) | Exchanges, queues, bindings, and the deployment where the broker is provisioned by somebody else | 20 min |
| 3 | [When the handler fails](tutorial-retries.md) | The retry ladder, dead letters, and why `Thread.sleep` in a consumer is expensive | 25 min |
| 4 | [Health and metrics](tutorial-observability.md) | Actuator, Prometheus, and three graphs worth having on day one | 20 min |
| 5 | [Testing it](tutorial-testing.md) | A fast test with no Docker, and an integration test with a real broker | 25 min |

Each builds on the one before. Nothing is left as an exercise.

## Before you start

A JDK 17 or newer, Maven, and Docker for the broker.

```bash
curl https://start.spring.io/starter.zip \
  -d dependencies=web,actuator \
  -d type=maven-project \
  -d javaVersion=17 \
  -d groupId=com.example \
  -d artifactId=orders \
  -d name=orders \
  -d packageName=com.example.orders \
  -o orders.zip
unzip orders.zip -d orders
cd orders
```

Then add the AceMQ repository and starter to `pom.xml`:

```xml
<repositories>
  <repository>
    <id>acemq</id>
    <url>https://acemq-company.github.io/maven/</url>
  </repository>
</repositories>

<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

## The broker

```bash
docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:4-management
```

Management UI at <http://localhost:15672>, guest/guest. Keep it open — every tutorial here
has something to look at on it.

When you are done:

```bash
docker rm -f rabbit
```
