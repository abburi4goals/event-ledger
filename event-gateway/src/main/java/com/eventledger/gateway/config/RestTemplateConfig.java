package com.eventledger.gateway.config;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate accountServiceRestTemplate(RestTemplateBuilder builder, Tracer tracer) {
        return builder
                // Micrometer auto-propagates W3C traceparent; this adds the explicit X-Trace-Id
                // header required by T-10 and CLAUDE.md, carrying the same trace id.
                .additionalInterceptors((req, body, ex) -> {
                    var span = tracer.currentSpan();
                    if (span != null) {
                        req.getHeaders().add("X-Trace-Id", span.context().traceId());
                    }
                    return ex.execute(req, body);
                })
                .build();
    }
}
