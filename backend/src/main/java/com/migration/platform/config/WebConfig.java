package com.migration.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Shared {@link RestClient} used to reach Kafka Connect. CORS is configured in SecurityConfig. */
@Configuration
public class WebConfig {

    @Bean
    public RestClient connectRestClient(PlatformProperties props) {
        RestClient.Builder builder = RestClient.builder()
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
