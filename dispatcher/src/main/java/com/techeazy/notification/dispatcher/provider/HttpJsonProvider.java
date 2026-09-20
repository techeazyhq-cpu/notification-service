package com.techeazy.notification.dispatcher.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POSTs a JSON document to a gateway. Settings: url (required), authHeader (optional, sent as Authorization), timeoutMs.
 * Body: {messageId, channel, to, subject, body}. A JSON response containing "id" is kept as the provider message id.
 * 429 and 5xx are transient; other 4xx are permanent.
 */
@Component
public class HttpJsonProvider implements ChannelProvider {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpJsonProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProviderType type() {
        return ProviderType.HTTP_JSON;
    }

    @Override
    public SendResult send(ProviderConfig config, Outbound message) {
        Map<String, String> s = config.getSettings();
        String url = s.get("url");
        if (url == null || url.isBlank()) throw new TransientSendException("HTTP provider setting 'url' is missing");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.messageId());
        payload.put("channel", message.channel());
        payload.put("to", message.recipient());
        payload.put("subject", message.subject());
        payload.put("body", message.body());

        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Long.parseLong(s.getOrDefault("timeoutMs", "10000"))))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (s.get("authHeader") != null && !s.get("authHeader").isBlank()) req.header("Authorization", s.get("authHeader"));

            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code >= 200 && code < 300) return new SendResult(extractId(res.body()));
            String detail = "HTTP " + code + " from provider: " + abbreviate(res.body());
            if (code == 429 || code == 408 || code >= 500) throw new TransientSendException(detail);
            throw new PermanentSendException(detail);
        } catch (IOException e) {
            throw new TransientSendException("Provider call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientSendException("Interrupted while calling provider", e);
        } catch (IllegalArgumentException e) {
            throw new TransientSendException("Bad provider configuration: " + e.getMessage(), e);
        }
    }

    private String extractId(String body) {
        try {
            JsonNode id = mapper.readTree(body).get("id");
            return id == null || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
