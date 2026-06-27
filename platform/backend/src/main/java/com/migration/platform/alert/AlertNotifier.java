package com.migration.platform.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Sends alert notifications: always logs; posts to a Slack/Teams/custom webhook when configured. */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    private final AlertProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AlertNotifier(AlertProperties props) {
        this.props = props;
    }

    public void notify(Alert alert) {
        log.warn("ALERT [{}] {} — {}", alert.getSeverity(), alert.getType(), alert.getMessage());
        String url = props.webhookUrl();
        if (!StringUtils.hasText(url)) return;
        try {
            String text = String.format("[%s] %s: %s", alert.getSeverity(), alert.getType(), alert.getMessage());
            String body = "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> { log.warn("Alert webhook failed: {}", ex.getMessage()); return null; });
        } catch (Exception e) {
            log.warn("Alert webhook error: {}", e.getMessage());
        }
    }
}
