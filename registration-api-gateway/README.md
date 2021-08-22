# Registration Api Gateway

## Milestone 4 - The Deliverable

Run Camel integration in the Cloud (Kubernetes) written in Camel DSL and using Camel K to deploy it.

The integration is an entrypoint for the Patient Portal and acts as Gateway API
and routes requests to various backend services.

### 1. Create a Java class that implements a Camel RouteBuilder

```java
public class RegistrationApiGatewayRoute extends RouteBuilder {

    @Override
    public void configure() {
    }
}
```
This is the typical way to implement Camel routes irrespective of how we choose to
run Camel.

### 2. Using Apache Camel REST DSL, expose the following API

```java
public class RegistrationApiGatewayRoute extends RouteBuilder {

    @Override
        public void configure() {
                registerCustomHttpClientConfigurer();

                rest("/appointments")
                        .get("{patientId}")
                        .route()
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()
                                .removeHeaders("Camel*")
                                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                                .toD("http:{{com.manning.liveproject.camel.practice-management-svc}}/practice-management/appointment/${header.patientId}?httpClientConfigurer=#myClientConfigurer")
                        .onFallback()
                                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                                .transform(constant("Unable to retrieve appointments. Please try again later."))
                        .end()
                ;

                rest("/results")
                        .get("{patientId}")
                        .route()
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()
                                .removeHeaders("Camel*")
                                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                                .toD("http:{{com.manning.liveproject.camel.medical-records-svc}}/medical-records/results/${header.patientId}?httpClientConfigurer=#myClientConfigurer")
                        .onFallback()
                                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                                .transform(constant("Unable to retrieve lab results. Please try again later."))
                        .end()
                ;

                rest("/availability")
                        .get()
                        .route()
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()
                                .removeHeaders("Camel*")
                                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                                .toD("http:{{com.manning.liveproject.camel.practice-management-svc}}/practice-management/availability?httpClientConfigurer=#myClientConfigurer")
                        .onFallback()
                                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                                .transform(constant("Unable to retrieve free appointment slots. Please try again later."))
                        .end()
                ;

                rest("/schedule")
                        .post().consumes("application/json")
                        .route()
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()
                                .removeHeaders("Camel*")
                                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                                .toD("http:inbound-rest-jms:8080/appointment?httpClientConfigurer=#myClientConfigurer")
                        .onFallback()
                                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                                .transform(constant("Unable to schedule an appointment. Please try again later."))
                        .end()
                ;

                rest("/prescriptions")
                        .get("{patientId}")
                        .route()
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()
                                .removeHeaders("Camel*")
                                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                                .toD("http:{{com.manning.liveproject.camel.medical-records-svc}}/medical-records/prescriptions/${header.patientId}?httpClientConfigurer=#myClientConfigurer")
                        .onFallback()
                                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                                .transform(constant("Unable to get prescriptions. Please try again later."))
                        .end()
                ;
        }
}
```

### 3. Surround each HTTP call with the Hystrix circuit breaker pattern. 
Open the circuit-breaker after 5 failed attempts. Check whether the circuit-breaker can be closed each half of a second.

> Note that the `Hystrix` implementation is now deprecated and that the solution uses the [Fault Tolerance](https://camel.apache.org/components/latest/eips/fault-tolerance-eip.html) 
> implementation instead

We surround calls to remote HTTP service calls, which could potentially fail or become non responsive, with a mechanism that fails fast
and protects these remote function calls from being invoked until such time that they become available again.

Camel encapsulates the [Circuit Breaker](https://camel.apache.org/components/latest/eips/circuitBreaker-eip.html) pattern
and provides an easy to use and to understand idiomatic expression in several programming languages (here we use Java DSL). 
The same constructs apply irrespective of which underlying circuit breaker pattern library implementation we choose to use.

```java
                        . . .
        
                        .route()
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()

                                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                                .toD("http:. . .")
        
                        .onFallback()
                            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                            .transform(constant("Unable to get . . . Please try again later."))
                        .end();
```
The protected function is the `toD("http:. . .")`  producer endpoint.

Anything surrounded by `.circuitBreaker()` and `.end()` is protected and is subject to the fail fast mechanism.

The circuit breaker can be configured per route, as shown below:
```java
                        .circuitBreaker()
                                .faultToleranceConfiguration()
                                        .timeoutEnabled(true).timeoutDuration(1500)
                                .end()

                                . . .

```
Here we enable the *timeout* on the circuit breaker, and this means that if the remote network response is not received within
the timeout duration the circuit is opened and does not forward nor retry any more requests, until it is closed again.

On the event that an error or timeout occurs and the circuit opens, we need to somehow send back to the client
something that is a little more "user" friendly than just a *generic* server error response (i.e. HTTP 500).

We can either use the circuit breaker `onFallback()` or Camel's *Error Handler* to handle the error.

The solution uses the `onFallback()` to locally transform the fall back message to something more friendly for the client.
```java
                        . . .
        
                        .onFallback()
                            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpResponseStatus.GATEWAY_TIMEOUT.code()))
                            .transform(constant("Unable to get . . . Please try again later."))
                        .end();
```
Here we set the HTTP header to *504* Gateway Timeout, and return a generic message response stating that the function
could not be performed and to try again later.

Alternatively, we could use Camel Error Handler to handle the errors and timeouts and perhaps the message could be redelivered
at a later time (e.g., persist it to a message queue). All we need to do is to enable the Camel Error Handler on the circuit breaker.

```java
                        .circuitBreaker().inheritErrorHandler(true)
                                . . .
```
> Note that `onFallback()` should not be used when the Error Handler is turned on.

#### Open the circuit-breaker after 5 failed attempts
This can be configured in the [HttpClient](https://hc.apache.org/httpcomponents-client-4.5.x/index.html) framework via 
Camel [http](https://camel.apache.org/components/latest/http-component.html#_component_options) component's `httpClientConfigurer` option.

What we want to achieve is __5__ retry attempts at the remote network resource before giving up and for the circuit to open.

First, register our custom `HttpClientConfigurer` bean which sets the retry count to 5;

```java
public class RegistrationApiGatewayRoute extends RouteBuilder {

        /**
         * the number of retries for failed http connection attempts before opening the circuit
         */
        private static final int RETRY_COUNT = 5;

        /**
         * true if it's OK to retry non-idempotent requests that have been sent
         */
        private static final boolean REQUEST_SENT_RETRY_ENABLED = false;

        /**
         * Need to configure the custom retry handler here as we are using Camel K, and it is not straightforward
         * configuring a custom "httpClientConfigurer" as a http component property without publishing our
         * HttpClientConfigurer class as a dependency, so for this module just register it as bean within the route
         * and use the http endpoint URI option instead (see .toD(...) in the routes below)
         */
        private void registerCustomHttpClientConfigurer() {
                getContext().getRegistry().bind("myClientConfigurer", new HttpClientConfigurer() {

                        @Override
                        public void configureHttpClient(HttpClientBuilder clientBuilder) {
                                clientBuilder.setRetryHandler(new DefaultHttpRequestRetryHandler(RETRY_COUNT, REQUEST_SENT_RETRY_ENABLED));
                        }
                });
        }

        @Override
        public void configure() {
                registerCustomHttpClientConfigurer();

                . . .

        }
}
```

then, we refer to it within the `http` endpoints using the URI `httpClientConfigurer` option.

```java
.toD("http:{{com.manning.liveproject.camel.medical-records-svc}}/medical-records/prescriptions/${header.patientId}?httpClientConfigurer=#myClientConfigurer")
```

### 4. Deploy the application
The integration service is built and deployed to Kubernetes by using the [Camel K](https://camel.apache.org/camel-k/latest/index.html) framework.

There is no need for a Maven `pom.xml` file or to prepare a distributable binary file. All we need is to tell Camel K the 
Java file that contains our integration code and Camel K does the rest.

To run the integration service in *development mode* on the Kubernetes cluster, use the following command from the `src` directory:

        kamel run -t logging.level=DEBUG --property file:application.properties ./com/manning/liveproject/camel/apigateway/RegistrationApiGatewayRoute.java --dev

where some properties are in the following file so as not to clutter the command.

```properties
camel.beans.http.connectTimeout=0

com.manning.liveproject.camel.practice-management-svc=practice-management:8080
com.manning.liveproject.camel.medical-records-svc=medical-records:8080
```

To deploy and run the integration service in __production mode__ on the Kubernetes cluster, use the following command:

        kamel run ./com/manning/liveproject/camel/apigateway/RegistrationApiGatewayRoute.java

