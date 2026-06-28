package com.migration.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Shared {@link RestClient} used to reach Kafka Connect. CORS is configured in SecurityConfig. */
@Configuration
public class WebConfig {

    @Bean
    public RestClient connectRestClient(PlatformProperties props) {
        // Bounded timeouts so a slow/unreachable Connect can't hang an orchestrator thread or the
        // health check indefinitely (#177). Retries are layered on top in KafkaConnectClient.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.connect().baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json");
        // Authenticate to a secured Kafka Connect REST endpoint when credentials are configured (#45).
        String user = props.connect().username();
        if (StringUtils.hasText(user)) {
            builder.defaultHeaders(h -> h.setBasicAuth(user, props.connect().password()));
        }
        return builder.build();
    }
}
