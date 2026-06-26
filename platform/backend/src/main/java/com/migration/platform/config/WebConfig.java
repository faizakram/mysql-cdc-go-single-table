package com.migration.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the SPA and the shared {@link RestClient} used to reach Kafka Connect. */
@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(PlatformProperties props) {
        String[] origins = props.cors().allowedOrigins().split(",");
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }

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
