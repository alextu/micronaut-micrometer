/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.configuration.metrics.binder.web

import groovy.transform.InheritConstructors
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.HistogramSnapshot
import io.micrometer.core.instrument.search.MeterNotFoundException
import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.Specification
import spock.lang.Unroll

import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_BINDERS
import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_ENABLED

class HttpMetricsSpec extends Specification {

    @Unroll
    void "test client / server metrics"() {
        when:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [(cfg): setting])
        def context = embeddedServer.getApplicationContext()
        TestClient client = context.getBean(TestClient)

        then:
        client.index() == 'ok'

        when:
        MeterRegistry registry = context.getBean(MeterRegistry)

        Timer serverTimer = registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri','/test-http-metrics').timer()
        Timer clientTimer = registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri','/test-http-metrics').timer()
        HistogramSnapshot serverSnapshot = serverTimer.takeSnapshot()
        HistogramSnapshot clientSnapshot = clientTimer.takeSnapshot()

        then:
        serverTimer != null
        serverTimer.count() == 1
        clientTimer.count() == 1
        serverSnapshot.percentileValues().length == serverPercentilesCount
        clientSnapshot.percentileValues().length == clientPercentilesCount

        when:"A request is sent to the root route"

        then:
        client.root() == 'root'
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri','root').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri','root').timer()

        when:"A request is sent with a uri template"
        def result = client.template("foo")

        then:
        result == 'ok foo'
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri','/test-http-metrics/{id}').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri','/test-http-metrics/{id}').timer()
		registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('host','localhost').timer()

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri','/test-http-metrics/foo').timer()

        then:
        thrown(MeterNotFoundException)

        when:"A request is made that returns an error response"
        client.error()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "409").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "409").timer()

        then:
        noExceptionThrown()

        when:"A request is made that throws an exception"
        client.throwable()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "500").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "500").timer()

        then:
        noExceptionThrown()

        when:"A request is made that throws an exception that is handled"
        client.exceptionHandling()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "400").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "400").timer()

        then:
        noExceptionThrown()

        when:"A request is made that does not match a route"
        HttpResponse response = client.notFound()

        then:
        noExceptionThrown()
        response.status() == HttpStatus.NOT_FOUND

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "404").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "404").timer()

        then:
        noExceptionThrown()

		cleanup:
        embeddedServer.close()

        where:
        cfg                                                   | setting       | serverPercentilesCount | clientPercentilesCount
        MICRONAUT_METRICS_BINDERS + ".web.client.percentiles" | "0.95,0.99"   | 0                      | 2
        MICRONAUT_METRICS_BINDERS + ".web.server.percentiles" | "0.95,0.99"   | 2                      | 0
	}

    @Unroll
    def "test getting the beans #cfg #setting"() {
        when:
        ApplicationContext context = ApplicationContext.run([(cfg): setting])

        then:
        context.findBean(ClientRequestMetricRegistryFilter).isPresent() == setting
        context.findBean(ServerRequestMeterRegistryFilter).isPresent() == setting

        cleanup:
        context.close()

        where:
        cfg                           | setting
        MICRONAUT_METRICS_ENABLED     | true
        MICRONAUT_METRICS_ENABLED     | false
        (WebMetricsPublisher.ENABLED) | true
        (WebMetricsPublisher.ENABLED) | false
    }

    @Client('/')
    static interface TestClient {
        @Get
        String root()

        @Get('/test-http-metrics')
        String index()

        @Get("/test-http-metrics/{id}")
        String template(String id)

        @Get("/test-http-metrics/error")
        HttpResponse error()

        @Get("/test-http-metrics/throwable")
        HttpResponse throwable()

        @Get("/test-http-metrics/exception-handling")
        HttpResponse exceptionHandling()

        @Get("/test-http-metrics-not-found")
        HttpResponse notFound()
    }

    @Controller('/')
    static class TestController {
        @Get
        String root() {
            return "root"
        }

        @Get('/test-http-metrics')
        String index() {
            return "ok"
        }

        @Get("/test-http-metrics/{id}")
        String template(String id) {
            return "ok " + id
        }

        @Get("/test-http-metrics/error")
        HttpResponse error() {
            HttpResponse.status(HttpStatus.CONFLICT)
        }

        @Get("/test-http-metrics/throwable")
        HttpResponse throwable() {
            throw new RuntimeException("error")
        }

        @Get("/test-http-metrics/exception-handling")
        HttpResponse exceptionHandling() {
            throw new MyException("my custom exception")
        }

        @Error(exception = MyException)
        HttpResponse<?> myExceptionHandler() {
            return HttpResponse.badRequest()
        }
    }

    @InheritConstructors
    static class MyException extends RuntimeException {

    }
}
