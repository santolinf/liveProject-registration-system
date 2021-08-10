# Patient Registration System

## Decisions

I deviated slightly when installing both the (single node) Kubernetes cluster and Docker for development.

### Kubernetes

Instead of installing [Minikube](https://minikube.sigs.k8s.io/docs/) to run a local Kubernetes cluster, I resorted to use remote deployments 
of [Microk8s](https://microk8s.io/) and Docker running on a virtualized Ubuntu Server.

This enables me to develop on a variety of local workstation platforms (e.g., Windows, MacOSX and Linux based)
and connect to the same Kubernetes-based infrastructure.

Then locally, for each of the platforms, all that is needed is to install the Kubernetes command-line tool [kubectl](https://kubernetes.io/docs/tasks/tools/). 
With this tool I can deploy and run commands on the remote Kubernetes cluster from any of my choice development platforms.

### Docker

I use either the *IntelliJ IDEA*' Docker plugin or `cURL` tool to build Docker images on the remote Docker engine
using the Docker Engine API.

Images will be stored in a local repository and not in Docker hub. The local Docker repository used is the one supplied by *Microk8s*, which
runs on the remote Ubuntu Server. The images will need to be prefixed with `localhost:32000` in their names, so that they can be found
by the Kubernetes infrastructure.

## Milestone 1 - The Deliverable

Using Camel to integrate systems, develop a solution where:

- **[Http to Jms route]** lab results messages are read from an HTTP endpoint and sent to a message queue
- **[Jms to Db route]** messages are retrieved from a message queue and stored in a local database as well as forwarded to a medical records system
via an HTTP endpoint

The solution consists of 2 Spring Boot based applications.

### Assumptions

The solution for this milestone will be implemented using Apache Camel with Spring Boot integration.

Underlying resources such as connection clients for *ApacheMQ Artemis* and *MongoDB*
will be managed by Spring Boot.

### Outbound - Http to Jms route

#### 1. Create a Java class, which implements a Camel RouteBuilder
#### 2. Read a message from an HTTP endpoint
#### 3. Send the message to a queue

```java
@Component
public class HttpJmsRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("jetty:http://0.0.0.0:{{server.port}}/results")
                .to(ExchangePattern.InOnly, "jms:Q.LAB.RESULTS")
        ;
    }
}
```

The Camel *Jetty* component is configured using Spring `server.port` property of the embedded web server.
This will normally be set to the default `8080`, however this externalised and overridable 
configuration enables me to run this service under other environments where I can choose a different port number.

#### 4. Use Spring Boot as the application runtime

```java
@SpringBootApplication
public class OutHttpJmsApp {

    public static void main(String[] args) {
        SpringApplication.run(OutHttpJmsApp.class, args);
    }
}
```

#### 5. Configure all the components used in the integration flow

```
    <dependencies>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-jetty-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-jms-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-artemis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.activemq</groupId>
            <artifactId>artemis-jms-client</artifactId>
        </dependency>
    </dependencies>

```

The *ActiveMQ Artemis* broker client is configured via the following Spring properties:

```properties
server.port=8080

spring.artemis.mode=native
spring.artemis.broker-url=tcp://${ACTIVEMQ_HOST}:${ACTIVEMQ_PORT}
spring.artemis.user=${ACTIVEMQ_USER}
spring.artemis.password=${ACTIVEMQ_PASS}
```

#### 6. Deploy application to Kubernetes

The service distributable *Jar* file is built using the following command:

```shell
mvnw clean package
```

which creates the application Jar file under the `target` directory.

The service Docker image is created using a *Dockerfile* (below) together
with the service distributable Jar file.

```dockerfile
FROM openjdk:11

COPY target/outbound-http-jms-1.0-SNAPSHOT.jar /opt/app.jar

RUN chown -R 1001:0 /opt && chmod -R 775 /opt
USER 1001

EXPOSE 8080

CMD java -jar /opt/app.jar
```

Either use the IntelliJ IDEA Docker plugin (not shown) or command line tools (see below) to create
the Docker image.

```shell
tar cfz - ./Dockerfile ./target/outbound-http-jms-1.0-SNAPSHOT.jar | curl -i -v -T - -XPOST -H "Content-Type: application/x-tar" -H "content-transfer-encoding: binary" $KUBE_SERVER:2375/v1.40/build?t=localhost:32000/camel-outbound-http-jms
```

The service is deployed to Kubernetes using the Docker image created (and which is pushed
to the local Docker repository on the remote server).

Make sure that on the remote server we run the following command:
```shell
ssh $KUBE_SERVER docker push localhost:32000/camel-outbound-http-jms
```

Use Kubernetes deployment resource to deploy the service.

```shell
kubectl apply -f ./out-http-jms-deploy.yaml
```

where the deployment resource file contains the following:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: outbound-http-jms
spec:
  replicas: 1
  selector:
    matchLabels:
      app: outbound-http-jms
  template:
    metadata:
      labels:
        app: outbound-http-jms
    spec:
      containers:
        - name: outbound-http-jms
          image: localhost:32000/camel-outbound-http-jms:latest
          imagePullPolicy: Always
          env:
            - name: ACTIVEMQ_HOST
              value: "broker"
            - name: ACTIVEMQ_PORT
              value: "61616"
            - name: ACTIVEMQ_USER
              value: "admin"
            - name: ACTIVEMQ_PASS
              value: "admin12#"
          ports:
            - containerPort: 8080
```
To access the service from outside the Kubernetes cluster, expose the service through the *NodePort*:

```shell
kubectl apply -f ./out-http-jms-svc.yaml
```

where the service resource file contains the following:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: outbound-http-jms
spec:
  type: NodePort
  selector:
    app: outbound-http-jms
  ports:
    - port: 8080
      targetPort: 8080
```

### Outbound - Jms to Db route

#### 1. Create a Java class, which extends a Camel RouteBuilder
#### 2. Read a message from a JMS endpoint

```java
@Component
public class JmsDbRoute extends RouteBuilder {

    @Override
    public void configure() {
        DataFormat hl7 = new HL7DataFormat();
        from("jms:Q.LAB.RESULTS")
                .unmarshal(hl7)
                .setBody(convertLFToCR())
                .bean(HL7DataExtractor.class)
                .marshal().json().convertBodyTo(String.class)
                .multicast()
                    .to("mongodb:mongo?database={{spring.data.mongodb.database}}&collection=results&operation=insert")
                    .to("http:{{com.manning.liveproject.camel.medical-records-svc}}/medical-records/results")
                .end()
        ;
    }
}
```
Apply *HL7* data formatting to the incoming message. Then, the relevant data points are extracted from the
HL7 message (the result is a *POJO* bean that replaces the original encoded HL7 message in the body).

```java
                .unmarshal(hl7)
                .setBody(convertLFToCR())
                .bean(HL7DataExtractor.class)
```

Camel will convert the body of the message (the POJO bean) into *JSON* which is then stored in a Mongo
database and the same payload send to an endpoint of a remote medical records system via HTTP.

```java
                .marshal().json().convertBodyTo(String.class)
```

Spring defines the mongo client bean (`mongo`) and makes it available for use as when defining the mongodb endpoint.
The Mongo database name is configurable across environments and is set via the Spring properties.

The host and port of the medical records service is also configurable and set via the Spring properties.

#### 3. Extract the (HL7) data

The bean for extracting data from the HL7 message is as follows:

```java
public class HL7DataExtractor {

    private final HapiContext context = new DefaultHapiContext();
    private final Parser p = context.getGenericParser();

    @Handler
    public ObservationResult parse(@Body String hl7Msg) throws HL7Exception {
        Message hapiMsg = p.parse(hl7Msg);
        Terser terser = new Terser(hapiMsg);

        return ObservationResult.builder()
                .patientId(terser.get("/.PID-3-1"))
                .patientName(terser.get("/.PID-5-2"))
                .patientSurname(terser.get("/.PID-5-1"))

                .observationRequest(ObservationRequest.builder()
                        .code(terser.get("/.OBR-4-1"))
                        .description(terser.get("/.OBR-4-2"))
                        .observationTimestamp(terser.get("/.OBR-7-1"))
                        .build()
                )

                .observationSegment(ObservationSegment.builder()
                        .identifierCode(terser.get("/.OBX-3-1"))
                        .identifierDescription(terser.get("/.OBX-3-2"))
                        .value(terser.get("/.OBX-5-1"))
                        .build()
                )
                .build();
    }
}
```

Here are the POJO beans definitions.

```java
@Data
@Builder
public class ObservationRequest {

    private String code;
    private String description;
    private String observationTimestamp;
}

@Data
@Builder
public class ObservationResult {

    private String patientId;
    private String patientName;
    private String patientSurname;

    private ObservationRequest observationRequest;
    private ObservationSegment observationSegment;
}

@Data
@Builder
public class ObservationSegment {

    private String identifierCode;
    private String identifierDescription;
    private String value;
}
```

#### 4. Use the multicast and pipeline EIPs

```java
        .multicast()
            .to("mongodb:mongo?database={{spring.data.mongodb.database}}&collection=results&operation=insert")
            .to("http:{{com.manning.liveproject.camel.medical-records-svc}}/medical-records/results")
        .end()
```

#### 5. Configure all the components used in the integration flow

```
    <dependencies>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-jms-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-artemis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.activemq</groupId>
            <artifactId>artemis-jms-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-hl7-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-jackson-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-mongodb-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-http-starter</artifactId>
        </dependency>

        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v21</artifactId>
            <version>${hapi.version}</version>
        </dependency>
        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v22</artifactId>
            <version>${hapi.version}</version>
        </dependency>
        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v23</artifactId>
            <version>${hapi.version}</version>
        </dependency>
        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v231</artifactId>
            <version>${hapi.version}</version>
        </dependency>
        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v24</artifactId>
            <version>${hapi.version}</version>
        </dependency>
        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v25</artifactId>
            <version>${hapi.version}</version>
        </dependency>
        <dependency>
            <groupId>ca.uhn.hapi</groupId>
            <artifactId>hapi-structures-v26</artifactId>
            <version>${hapi.version}</version>
        </dependency>
    </dependencies>

```

The *ActiveMQ Artemis* broker and MongoDB clients are configured via the following Spring properties.
Additionally we externalise the configuration of the medical records system service.

```properties
# to keep the JVM running
camel.springboot.main-run-controller = true

spring.artemis.mode=native
spring.artemis.broker-url=tcp://${ACTIVEMQ_HOST}:${ACTIVEMQ_PORT}
spring.artemis.user=${ACTIVEMQ_USER}
spring.artemis.password=${ACTIVEMQ_PASS}

spring.data.mongodb.uri=mongodb://${MONGODB_USER}:${MONGODB_PASS}@${MONGODB_HOST}:${MONGODB_PORT}/?authSource=admin
spring.data.mongodb.database=${MONGO_DB_NAME}

com.manning.liveproject.camel.medical-records-svc=${MEDRECORDS_SRV_HOST}:${MEDRECORDS_SRV_PORT}
```

#### 6. Deploy application to Kubernetes

The service distributable *Jar* file is built using the following command:

```shell
mvnw clean package
```

which creates the application Jar file under the `target` directory.

The service Docker image is created using a *Dockerfile* (below) together
with the service distributable Jar file.

```dockerfile
FROM openjdk:11

COPY target/outbound-jms-db-1.0-SNAPSHOT.jar /opt/app.jar

RUN chown -R 1001:0 /opt && chmod -R 775 /opt
USER 1001

EXPOSE 8080

CMD java -jar /opt/app.jar
```

Either use the IntelliJ IDEA Docker plugin (not shown) or command line tools (see below) to create
the Docker image.

```shell
tar cfz - ./Dockerfile ./target/outbound-jms-db-1.0-SNAPSHOT.jar | curl -i -v -T - -XPOST -H "Content-Type: application/x-tar" -H "content-transfer-encoding: binary" $KUBE_SERVER:2375/v1.40/build?t=localhost:32000/camel-outbound-jms-db
```

The service is deployed to Kubernetes using the Docker image created (and which is pushed
to the local Docker repository in the remote server).

Make sure that on the remote server we run the following command:
```shell
ssh $KUBE_SERVER docker push localhost:32000/camel-outbound-jms-db
```

Use Kubernetes deployment resource to deploy the service.

```shell
kubectl apply -f ./out-jms-db-deploy.yaml
```

where the deployment resource file contains the following:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: outbound-jms-db
spec:
  replicas: 1
  selector:
    matchLabels:
      app: outbound-jms-db
  template:
    metadata:
      labels:
        app: outbound-jms-db
    spec:
      containers:
        - name: outbound-jms-db
          image: localhost:32000/camel-outbound-jms-db
          imagePullPolicy: Always
          env:
            - name: ACTIVEMQ_HOST
              value: "broker"
            - name: ACTIVEMQ_PORT
              value: "61616"
            - name: ACTIVEMQ_USER
              value: "admin"
            - name: ACTIVEMQ_PASS
              value: "admin12#"
            - name: MONGODB_HOST
              value: "mongodb"
            - name: MONGODB_PORT
              value: "27017"
            - name: MONGODB_USER
              value: "health"
            - name: MONGODB_PASS
              value: "health12#"
            - name: MONGO_DB_NAME
              value: "LabResults"
            - name: MEDRECORDS_SRV_HOST
              value: "medical-records"
            - name: MEDRECORDS_SRV_PORT
              value: "8080"
          ports:
            - containerPort: 8080
```
To access the service from outside the Kubernetes cluster, expose the service through the *NodePort*:

```shell
kubectl apply -f ./out-jms-db-svc.yaml
```

where the service resource file contains the following:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: outbound-jms-db
spec:
  type: NodePort
  selector:
    app: outbound-jms-db
  ports:
    - port: 8080
      targetPort: 8080
```

## Milestone 2 -- The Deliverable

Using Camel to integrate systems, develop a solution where:

- **[REST API to Jms route]** patient registration and appointment messages are received from REST endpoints and sent to a message queue
- **[Jms to Http route]** messages are retrieved from a message queue and forwarded to the corresponding health care systems
  via HTTP endpoints

The solution consists of 2 Quarkus based applications.

### Inbound - Rest to Jms route

#### Assumptions

The *portal* or *registration system* will use a (non HL7) proprietary data format to send
messages to the *inbound* RESTful endpoints.

The inbound service will receive the simple messages and translate them to HL7
data format for further processing. *HAPI-FHIR* will be used for this.

> FHIR builds on previous standards, including HL7 V2, HL7 V3, and CDA (Clinical Document Architecture—part of HL7 V3).
> But unlike those other standards, FHIR employs RESTful web services and open web technologies,
> including not only XML (used by previous standards) but also JSON and RDF data formats.

The version of Apache Camel used supports the following FHIR specification versions: *DSTU2*,*DSTU3*,*R4*,*R5*

The solution will only support **R4**.

```properties
quarkus.camel.fhir.enable-dstu2=false
quarkus.camel.fhir.enable-dstu3=false
quarkus.camel.fhir.enable-r4=true
quarkus.camel.fhir.enable-r5=false
```

#### 1. Create a Java class that implements a Camel RouteBuilder
#### 2. Use REST DSL to expose a REST endpoint

```java
public class RestJmsInboundRoute extends RouteBuilder {

  @Override
  public void configure() throws Exception {

    restConfiguration().bindingMode(RestBindingMode.json)
            .dataFormatProperty("json.in.disableFeatures", "FAIL_ON_UNKNOWN_PROPERTIES");

    rest("/registration")
            .post().type(InboundMessage.class)
            .route().routeId("incoming-registration")
            .setHeader(INBOUND_EVENT_TYPE, constant(InboundEventType.Registration.name()))
            .to(direct("receive-message"))
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.CREATED.code()))
            .endRest()
    ;

    rest("/appointment")
            .post().type(InboundMessage.class)
            .route().routeId("incoming-appointment")
            .setHeader(INBOUND_EVENT_TYPE, constant(InboundEventType.Appointment.name()))
            .to(direct("receive-message"))
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.CREATED.code()))
            .endRest()
    ;

    from(direct("receive-message")).routeId("receive-message").description("Receive and format incoming message")
            .to("bean-validator:validateInboundMessage")
            .bean(InboundToHL7DataTransformer.class)
            .marshal().fhirJson(FhirVersionEnum.R4.name()).convertBodyTo(String.class) // convert to Json for sending to jms queue
            .to(direct("forward-message"))
    ;

    from(direct("forward-message")).routeId("forward-message")
            .removeHeaders("Camel*")
            .to(ExchangePattern.InOnly, "jms:Q.PATIENT.INBOUND")
    ;
  }
}
```

#### 3. Use the bean component to transform a message from JSON format to HL7

To transform the incoming JSON payload to an HL7 data format we use the
`InboundToHL7DataTransformer` bean. Depending on which REST endpoint the
message came through, a value will be set in a custom Camel header (`InboundEventType`) within the route and this value
determines the model objects to create and return from the transformer bean.

```java
@Slf4j
public class InboundToHL7DataTransformer {

  /**
   * We are only handling Registration and Appointment types.
   */
  @Handler
  public Resource toFhirResourceObject(
          @Body InboundMessage message,
          @Headers Map<String, String> headers
  ) {
    log.debug("Inbound message type received: {}", headers.get(INBOUND_EVENT_TYPE));

    Patient patient = new Patient();
    patient.addName().setFamily(message.getFamilyName()).addGiven(message.getGivenName());

    Resource result;
    if (Registration.name().equals(headers.get(INBOUND_EVENT_TYPE))) {
      result = patient;
    } else  {
      Appointment.AppointmentParticipantComponent participantComponent = new Appointment.AppointmentParticipantComponent();
      Reference reference = new Reference();
      reference.setResource(patient);
      participantComponent.setActor(reference);

      Appointment appointment = new Appointment();
      appointment.setParticipant(Lists.newArrayList(participantComponent));

      result = appointment;
    }

    return result;
  }
}
```

Constants and enums are defined to formalise the header name and the message types.

```java
public final class InboundFlowConstants {

  public static final String INBOUND_EVENT_TYPE = "InboundEventType";

  private InboundFlowConstants() {}
}
```

```java
public enum InboundEventType {
  Registration,
  Appointment;
}
```

#### 4. Implement a content-based route
I have left this for the next part.

#### 5. Add error handling.

A `defaultErrorHandler` is defined for handling all errors and exceptions on the route, unless specific `onException` clauses are
defined to deal with and process in a specific way certain known exceptions.

```java
public class RestJmsInboundRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        . . .

        errorHandler(defaultErrorHandler()
                .maximumRedeliveries(1)
                .redeliveryDelay(1000)  // 1 sec
                .logRetryAttempted(false)
        );


        . . .
        
        from(direct("receive-message")).routeId("receive-message").description("Receive and format incoming message")
                .onException(BeanValidationException.class)
                        .handled(true)
                        .maximumRedeliveries(0)
                        .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.BAD_REQUEST.code()))
                        .process(new BeanValidationFailureResponseProcessor())
                        .end()
                . . .
        ;

        from(direct("forward-message")).routeId("forward-message")
                .onException(JMSException.class)
                        .handled(true)
                        .maximumRedeliveries(0)
                        .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.SERVICE_UNAVAILABLE.code()))
                        .setBody(exceptionMessage())
                        .end()
                . . .
        ;
    }
}
```

A bean will map the set of properties we expect from the JSON payloads coming from the portal and
registration system. Then bean validation `javax.validation.constraints` is used to set some simple field validation rules.

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundMessage {

    @NotEmpty
    private String familyName;
    @NotEmpty
    private String givenName;
}
```

Note that for the purposes of this exercise only the *family name* and *given name* of a patient are specified. In a real
project we would expect a lot more properties to be defined, including appointment dates, and a more sophisticated model structure.

If either or both of the above name fields are missing or empty, a `BeanValidationException` will be thrown by the validation
framework, and a specific exception rule is defined to handle such occurrence and then format the error message appropriately
which is sent back to the calling systems.

```java
                .onException(BeanValidationException.class)
                        .handled(true)
                        .maximumRedeliveries(0)
                        .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.BAD_REQUEST.code()))
                        .process(new BeanValidationFailureResponseProcessor())
                        .end()
```

A custom processor bean is used to format the error message from the bean validation exception.

```java
public class BeanValidationFailureResponseProcessor implements Processor {

    @Override
    public void process(Exchange exchange) {
        BeanValidationException e = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, BeanValidationException.class);
        exchange.getIn().setBody("ERROR: " + e.getConstraintViolations());
    }
}
```

If an exception is thrown when interacting with the messaging broker then a specific `onException` clause is defined to return an *HTTP 503* status code back to the calling system.

```java
        from(direct("forward-message")).routeId("forward-message")
                .onException(JMSException.class)
                        .handled(true)
                        .maximumRedeliveries(0)
                        .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.SERVICE_UNAVAILABLE.code()))
                        .setBody(exceptionMessage())
                        .end()
```

#### 6. Run tests using Maven.

Tests are available and are run as part of creating the *native* executable.

    ./mvnw package -Pnative

There is a test class to test the inbound JSON messages for each of the REST endpoints.

```java
@QuarkusTestResource(value = MessageBrokerResource.class, restrictToAnnotatedClass = true)
@QuarkusTest
public class InboundMessageTest {

    @Test
    public void givenPatientRegistrationMessage_whenPostRequest_thenForwardMessage() {
        given()
                .contentType(ContentType.JSON)
                .body(newInboundMessage())
                .when()
                    .post("/registration")
                .then()
                    .statusCode(HttpStatus.SC_CREATED);
    }

    @Test
    public void givenPatientAppointmentMessage_whenPostRequest_thenForwardMessage() {
        given()
                .contentType(ContentType.JSON)
                .body(newInboundMessage())
                .when()
                    .post("/appointment")
                .then()
                    .statusCode(HttpStatus.SC_CREATED);
    }
}
```

For the above tests we rely on a Quarkus Test Resource. In this case a JMS message broker such as ActiveMQ Artemis. The test 
messaging broker will be created and run inside a Docker container for the duration of the tests.

```java
@Slf4j
public class MessageBrokerResource implements QuarkusTestResourceLifecycleManager {

    private static final Integer TCP_PORT = 61616;

    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new GenericContainer<>(DockerImageName.parse("vromero/activemq-artemis:latest"))
                .withExposedPorts(TCP_PORT)
                .withLogConsumer(new Slf4jLogConsumer(log))
                .waitingFor(Wait.forListeningPort());

        container.start();

        // Pass the configuration to the application under test
        return ImmutableMap.of(
                "quarkus.artemis.url", String.format("tcp://%s:%d", container.getContainerIpAddress(), container.getMappedPort(TCP_PORT)),
                // the following are the default values from https://github.com/vromero/activemq-artemis-docker
                "quarkus.artemis.username", "artemis",
                "quarkus.artemis.password", "simetraehcapa"
        );
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
```

> As stated earlier, my specific set-up includes a remote Docker deployment on a virtualized Ubuntu server. As such, tests
> will fail because by default the `QuarkusTestResouce`s look for a local Docker engine. To override this behaviour, in my case,
> both `surefire` and `failsafe` maven plugins can be configured to point to a remote Docker engine. See an example of `maven-surefire-plugin` below.
 
```xml
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire-plugin.version}</version>
        <configuration>
          <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
            <maven.home>${maven.home}</maven.home>
          </systemPropertyVariables>
          <!-- target remote deployment of Docker running on a virtualized Ubuntu Server -->
          <environmentVariables>
            <DOCKER_HOST>tcp://${KUBE_SERVER}:2375</DOCKER_HOST>
            <DOCKER_TLS_VERIFY>0</DOCKER_TLS_VERIFY>
          </environmentVariables>
        </configuration>
      </plugin>
```

There are also tests for the un-happy paths too.

```java
/**
 * Deliberate omission of @QuarkusTestResource(MessageBrokerResource.class)
 * for testing without a message broker.
 */
@QuarkusTest
public class RouteExceptionsTest {

    @Test
    public void givenMessageBrokerIsUnavailable_whenSendingRestMessage_thenReturnServiceUnavailable() {
        given()
                .contentType(ContentType.JSON)
                .body(newInboundMessage())
                .when()
                    .post("/registration")
                .then()
                    .statusCode(HttpStatus.SC_SERVICE_UNAVAILABLE)
                    .body(containsString("ActiveMQNotConnectedException[errorType=NOT_CONNECTED message=AMQ219007: Cannot connect to server(s). Tried with all available servers.]"));
    }

    @Test
    public void givenMessageWithMissingFields_whenSendingRestMessage_thenReturnBadRequest() {
        InboundMessage inboundMessage = newInboundMessage();
        inboundMessage.setFamilyName(null);

        given()
                .contentType(ContentType.JSON)
                .body(inboundMessage)
                .when()
                    .post("/registration")
                .then()
                    .statusCode(HttpStatus.SC_BAD_REQUEST);
    }
}
```

#### 7. Configure all the components used in the integration flow.

Add the Camel core and components as well as supporting test libraries.

```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-bom</artifactId>
        <version>${camel-quarkus.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      
      . . .
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-platform-http</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-rest</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-bean</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-direct</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-log</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-jackson</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-bean-validator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-jms</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-fhir</artifactId>
    </dependency>
    
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <version>${lombok.version}</version>
    </dependency>

    . . .

    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-artemis-jms</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-kubernetes</artifactId>
    </dependency>

    . . .

    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
```

Add application specific properties in the `application.properties` file.

```properties
quarkus.http.port=8080
%dev.quarkus.http.port=8282

quarkus.http.root-path=/inbound

quarkus.camel.fhir.enable-dstu2=false
quarkus.camel.fhir.enable-dstu3=false
quarkus.camel.fhir.enable-r4=true
quarkus.camel.fhir.enable-r5=false

quarkus.artemis.url=tcp://${ACTIVEMQ_HOST}:${ACTIVEMQ_PORT}
quarkus.artemis.username=${ACTIVEMQ_USER}
quarkus.artemis.password=${ACTIVEMQ_PASS}
```

To run locally create a `.env` file with the following contents:

```properties
ACTIVEMQ_HOST=localhost
ACTIVEMQ_PORT=61616
ACTIVEMQ_USER=
ACTIVEMQ_PASS=
```

When building and running a *native* Quarkus-based service executable, we need to register certain classes otherwise these will
be missed when compilation for a native executable finishes.

```properties
# when using Camel onException in native mode we need to register the exception classes for reflection
# here we include also our own classes too (however there is no need to add the index-dependency for local classes)
quarkus.camel.native.reflection.include-patterns=com.manning.liveproject.camel.model.*,com.manning.liveproject.camel.model.enums.*,com.manning.liveproject.camel.bean.*,com.manning.liveproject.camel.util.*,org.apache.camel.component.bean.validator.*Exception,javax.jms.*Exception

quarkus.index-dependency.camel-bean-validator.group-id = org.apache.camel
quarkus.index-dependency.camel-bean-validator.artifact-id = camel-bean-validator
quarkus.index-dependency.jms-specs.group-id = org.apache.geronimo.specs
quarkus.index-dependency.jms-specs.artifact-id = geronimo-jms_2.0_spec
```

#### 8. Deploy the application to Kubernetes

The service native executable is built using the following command:

    ./mvnw package -Pnative

However, as I don't have *GraalVM* installed locally, it will be built in a container on a remote Docker engine.
A *multi-staged* Dockerfile will be used to build the executable and then to build an image from it.

> Note that because the project has a parent pom file, I had to adjust this project's pom file and when building
> the image in the container, I use the *sed* command to remove the reference to the parent pom: `sed -i '5,9d' /project/pom.xml`
> (see listing below)

```dockerfile
## Stage 1 : build with maven builder image with native capabilities
FROM registry.access.redhat.com/ubi8/ubi-minimal:8.4 AS build
RUN microdnf install tar gzip gcc glibc-devel zlib-devel shadow-utils unzip gcc-c++ \
    glibc-langpack-en fontconfig freetype-devel

ENV JAVA_HOME=/opt/graalvm
ENV GRAALVM_HOME=/opt/graalvm
ENV GRAALVM_LABEL=vm-21.1.0
ENV GRAALVM_VERSION=java11-21.1.0
ENV FILENAME=graalvm-ce-java11-linux-amd64-21.1.0.tar.gz

RUN groupadd -r quarkus -g 1001 \
    && useradd -u 1001 -r -g 1001 -m -d /home/quarkus -s /sbin/nologin -c "Quarkus user" quarkus
RUN mkdir /project
RUN chown quarkus:quarkus /project

WORKDIR /tmp/artifacts
ADD https://github.com/graalvm/graalvm-ce-builds/releases/download/$GRAALVM_LABEL/$FILENAME /tmp/artifacts
RUN tar xzf $FILENAME -C /opt
RUN mv /opt/graalvm-ce-$GRAALVM_VERSION /opt/graalvm

RUN /opt/graalvm/bin/gu --auto-yes install native-image

COPY pom.xml /project
COPY mvnw /project/mvnw
COPY .mvn /project/.mvn

# remove reference to parent pom so that we can build the native image using the local pom
RUN sed -i '5,9d' /project/pom.xml

USER quarkus
WORKDIR /project
RUN ./mvnw -B org.apache.maven.plugins:maven-dependency-plugin:3.2.0:go-offline
COPY src /project/src
RUN printf 'ACTIVEMQ_HOST=localhost\nACTIVEMQ_PORT=61616\nACTIVEMQ_USER=\nACTIVEMQ_PASS=\n' > .env
RUN ./mvnw package -Pnative

## Stage 2 : create the docker final image
FROM registry.access.redhat.com/ubi8/ubi-minimal:8.4
WORKDIR /work/
COPY --from=build /project/target/*-runner /work/application

# set up permissions for user `1001`
RUN chmod 775 /work /work/application \
  && chown -R 1001 /work \
  && chmod -R "g+rwX" /work \
  && chown -R 1001:root /work

EXPOSE 8080
USER 1001

CMD ["./application", "-Dquarkus.http.host=0.0.0.0"]
```

Use the command line tool to build the native binary remotely.

```shell
tar cfz - ./pom.xml ./mvnw ./.mvn/ ./src/ | curl -i -v -T - -XPOST -H "Content-Type: application/x-tar" -H "content-transfer-encoding: binary" "$KUBE_SERVER:2375/v1.40/build?dockerfile=src/main/docker/Dockerfile.native-multistage&t=localhost:32000/camel-inbound-rest-jms-native"
```

The service is deployed to Kubernetes using the Docker image created (and which is pushed to the local Docker repository on the remote server).

Make sure that on the remote server we run the following command:

    ssh $KUBE_SERVER docker push localhost:32000/camel-inbound-rest-jms-native

Use Kubernetes deployment resource to deploy the service.

    kubectl apply -f ./in-rest-jms-deploy.yaml

where the deployment resource file contains the following:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inbound-rest-jms
spec:
  replicas: 1
  selector:
    matchLabels:
      app: inbound-rest-jms
  template:
    metadata:
      labels:
        app: inbound-rest-jms
    spec:
      containers:
        - name: inbound-rest-jms
          image: localhost:32000/camel-inbound-rest-jms-native:latest
          imagePullPolicy: Always
          env:
            - name: ACTIVEMQ_HOST
              value: "broker"
            - name: ACTIVEMQ_PORT
              value: "61616"
            - name: ACTIVEMQ_USER
              value: "admin"
            - name: ACTIVEMQ_PASS
              value: "admin12#"
          ports:
            - containerPort: 8080
```

To access the service from outside the Kubernetes cluster, expose the service through the NodePort:

    kubectl apply -f ./in-rest-jms-svc.yaml

where the service resource file contains the following:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: inbound-rest-jms
spec:
  type: NodePort
  selector:
    app: inbound-rest-jms
  ports:
    - port: 8080
      targetPort: 8080
```

### Inbound - Jms to Http route

#### 1. Create a Java class, which implements a Camel RouteBuilder
#### 2. Read a message from a JMS endpoint
#### 3. Use the Camel JSON path and the content based route to forward messages to corresponding HTTP endpoints
#### 4. Use Quarkus as the application runtime

```java
public class JmsHttpInboundRoute extends RouteBuilder {

    @Override
    public void configure() {
        errorHandler(deadLetterChannel("jms:DLQ").useOriginalMessage()
                .disableRedelivery()
                .onPrepareFailure(e -> {
                    Map<?,?> properties = e.getAllProperties();
                    e.getIn().setHeader(
                            "InboundExceptionCaught",
                            ofNullable((Exception) properties.get(Exchange.EXCEPTION_CAUGHT)).map(Throwable::getMessage)
                                    .orElse("unknown")
                    );
                    e.getIn().setHeader(
                            "InboundToEndpoint",
                            properties.get(Exchange.TO_ENDPOINT)
                    );
                })
                .log("Inbound message sent DLQ: Inbound message type, exception caught, and to endpoint data captured in their respective headers")
        );

        from("jms:Q.PATIENT.INBOUND")
                .choice()
                    .when().jsonpath("$.[?(@.resourceType == 'Patient')]")
                        .to("http:{{com.manning.liveproject.camel.practice-management-svc}}/practice-management/registration")
                    .when().jsonpath("$.[?(@.resourceType == 'Appointment')]")
                        .to("http:{{com.manning.liveproject.camel.practice-management-svc}}/practice-management/appointment")
                    .otherwise()
                        .throwException(UnknownInboundMessage.class, "Unknown inbound message: ${body}")
                .endChoice()
        ;
    }
}
```

Messages are read from the message broker's `Q.PATIENT.INBOUND` queue and then based on values in the message content, the `resourceType`,
the messages are then sent to the appropriate health care system using Camel HTTP component.

When a message is not recognisable or there are issues with sending the data to the external health care systems, the
message is moved to a dead letter queue. No process is defined or exists, yet, to deal with these messages.

#### 5. Configure all the components used in the integration flow

Add the Camel core and components as well as supporting test libraries.

```xml
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-jms</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-http</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-jsonpath</artifactId>
    </dependency>

    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <version>${lombok.version}</version>
    </dependency>

    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-artemis-jms</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-kubernetes</artifactId>
    </dependency>

    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
    
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>

```

Add application specific properties in the `application.properties` file.

```properties
quarkus.artemis.url=tcp://${ACTIVEMQ_HOST}:${ACTIVEMQ_PORT}
quarkus.artemis.username=${ACTIVEMQ_USER}
quarkus.artemis.password=${ACTIVEMQ_PASS}

com.manning.liveproject.camel.practice-management-svc=${PRACTICEMGT_SRV_HOST}:${PRACTICEMGT_SRV_PORT}
%dev.com.manning.liveproject.camel.practice-management-svc=localhost:8080
```

To run locally create a `.env` file with the following contents:

```properties
ACTIVEMQ_HOST=localhost
ACTIVEMQ_PORT=61616
ACTIVEMQ_USER=
ACTIVEMQ_PASS=
```

#### 6. Deploy application to Kubernetes

The way to build the native executable is the same as per above and the same multistage Dockerfile is used.

Use the command line tool to build the native binary remotely.

```shell
tar cfz - ./pom.xml ./mvnw ./.mvn/ ./src/ | curl -i -v -T - -XPOST -H "Content-Type: application/x-tar" -H "content-transfer-encoding: binary" "$KUBE_SERVER:2375/v1.40/build?dockerfile=src/main/docker/Dockerfile.native-multistage&t=localhost:32000/camel-inbound-jms-http-native"
```

The service is deployed to Kubernetes using the Docker image created (and which is pushed to the local Docker repository on the remote server).

Make sure that on the remote server we run the following command:

    ssh $KUBE_SERVER docker push localhost:32000/camel-inbound-jms-http-native

Use Kubernetes deployment resource to deploy the service.

    kubectl apply -f ./in-jms-http-deploy.yaml

where the deployment resource file contains the following:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inbound-jms-http
spec:
  replicas: 1
  selector:
    matchLabels:
      app: inbound-jms-http
  template:
    metadata:
      labels:
        app: inbound-jms-http
    spec:
      containers:
        - name: inbound-jms-http
          image: localhost:32000/camel-inbound-jms-http-native:latest
          imagePullPolicy: Always
          env:
            - name: ACTIVEMQ_HOST
              value: "broker"
            - name: ACTIVEMQ_PORT
              value: "61616"
            - name: ACTIVEMQ_USER
              value: "admin"
            - name: ACTIVEMQ_PASS
              value: "admin12#"
            - name: PRACTICEMGT_SRV_HOST
              value: "practice-management"
            - name: PRACTICEMGT_SRV_PORT
              value: "8080"
          ports:
            - containerPort: 8080
```

# Appendix

## HL7 Data Transformation Notes

HL7 message  -->  Java FHIR Client <--> FHIR Rest <--> Provider (FHIR Server and client) <--> FHIR Rest <--> Health Care Systems

Java client (FHIR message directly to Provider using):

- Open Source HAPI FHIR (http://hapifhir.io/)
- Apache HTTP Client (https://hc.apache.org/httpcomponents-client-ga/)

## K8S Notes

To delete Failed or Evicted Pods in Kubernetes:
```shell
microk8s kubectl get pods --all-namespaces --field-selector status.phase==Failed | awk '{ if(NR>1)print $2}' | xargs microk8s kubectl delete pods
```
