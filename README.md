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
