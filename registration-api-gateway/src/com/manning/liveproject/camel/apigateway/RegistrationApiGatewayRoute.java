package com.manning.liveproject.camel.apigateway;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;

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
