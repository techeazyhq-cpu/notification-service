/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.dispatcher.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.domain.FcmEndpoints;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a push through Firebase Cloud Messaging's HTTP v1 API ({@code POST /v1/projects/{projectId}/messages:send})
 * to the device token in the recipient: a web browser, or an Android or iOS app.
 *
 * <p>Settings: {@code projectId}, {@code clientEmail} and {@code privateKey} from a Google service account key with
 * the Firebase Cloud Messaging API Admin role ({@code privateKey} is the PKCS #8 PEM, with real or {@code \n} line
 * breaks, and is stored encrypted); optional {@code link} (an https URL a web notification opens when clicked),
 * {@code timeoutMs}, and {@code url} / {@code tokenUrl} to replace the Google endpoints ({@link FcmEndpoints}).
 *
 * <p>The provider signs a JWT with the key and exchanges it for an OAuth 2.0 access token (the service-account flow),
 * caching the token until a minute before it expires; a 401 from FCM drops it so the next attempt gets a fresh one.
 * The subject becomes the notification title and the body its text; the message id travels in {@code data.messageId}.
 *
 * <p>Classification: a token FCM no longer knows ({@code UNREGISTERED}, 404), a malformed request or token
 * ({@code INVALID_ARGUMENT}) and a token from another Firebase project ({@code SENDER_ID_MISMATCH}) are permanent;
 * throttling, 5xx, a refused or failed token exchange, and a 401/403 for the service account itself are transient, so
 * they count against the provider's circuit breaker and fail over (ADR-032, ADR-038). The device token never appears
 * in an error message.
 */
@Component
public class FcmProvider implements ChannelProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private static final String PROJECT_ID = "projectId";
    private static final String CLIENT_EMAIL = "clientEmail";
    private static final String PRIVATE_KEY = "privateKey";
    private static final String LINK = "link";
    private static final String URL = "url";
    private static final String TIMEOUT_MS = "timeoutMs";
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(1);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;
    private final Map<String, AccessToken> tokens = new ConcurrentHashMap<>();

    private record AccessToken(String value, Instant refreshAfter) {}

    private record ServiceAccount(String projectId, String clientEmail, PrivateKey key, String tokenUrl) {

        String cacheKey() {
            return clientEmail + "|" + tokenUrl + "|" + Base64.getEncoder().encodeToString(key.getEncoded()).hashCode();
        }
    }

    @Autowired
    public FcmProvider(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Clock.systemUTC());
    }

    FcmProvider(ObjectMapper mapper, HttpClient http, Clock clock) {
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public ProviderType type() {
        return ProviderType.FCM;
    }

    @Override
    public SendResult send(ProviderConfig config, Outbound message) {
        Map<String, String> settings = config.getSettings();
        ServiceAccount account = serviceAccount(settings);
        Duration timeout = Duration.ofMillis(Long.parseLong(settings.getOrDefault(TIMEOUT_MS, "10000")));
        String accessToken = accessToken(account, timeout);
        URI endpoint = URI.create(trimTrailingSlash(settings.getOrDefault(URL, FcmEndpoints.DEFAULT_URL))
                + "/v1/projects/" + URLEncoder.encode(account.projectId(), StandardCharsets.UTF_8) + "/messages:send");
        HttpResponse<String> response = post(HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(payload(message, settings.get(LINK)))))
                .build());
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return new SendResult(field(response.body(), "name"));
        }
        if (code == 401) {
            tokens.remove(account.cacheKey());
        }
        throw classify(code, response.body());
    }

    private ServiceAccount serviceAccount(Map<String, String> settings) {
        String projectId = required(settings, PROJECT_ID);
        String clientEmail = required(settings, CLIENT_EMAIL);
        PrivateKey key = privateKey(required(settings, PRIVATE_KEY));
        String tokenUrl = settings.getOrDefault(FcmEndpoints.TOKEN_URL_SETTING, FcmEndpoints.DEFAULT_TOKEN_URL);
        return new ServiceAccount(projectId, clientEmail, key, tokenUrl);
    }

    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new TransientSendException("FCM provider setting '" + key + "' is missing");
        }
        return value.trim();
    }

    private static PrivateKey privateKey(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TransientSendException("Bad provider configuration: 'privateKey' is not a PKCS #8 RSA private key", e);
        }
    }

    private String accessToken(ServiceAccount account, Duration timeout) {
        AccessToken cached = tokens.get(account.cacheKey());
        if (cached != null && clock.instant().isBefore(cached.refreshAfter())) {
            return cached.value();
        }
        AccessToken fresh = exchange(account, timeout);
        tokens.put(account.cacheKey(), fresh);
        return fresh.value();
    }

    private AccessToken exchange(ServiceAccount account, Duration timeout) {
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion(account), StandardCharsets.UTF_8);
        HttpResponse<String> response = post(HttpRequest.newBuilder(URI.create(account.tokenUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
        if (response.statusCode() != 200) {
            throw new TransientSendException("FCM access token request failed: HTTP " + response.statusCode() + " "
                    + abbreviate(field(response.body(), "error_description")));
        }
        String value = field(response.body(), "access_token");
        if (value == null) {
            throw new TransientSendException("FCM access token response had no access_token");
        }
        long expiresIn = number(response.body(), "expires_in", TOKEN_LIFETIME.toSeconds());
        return new AccessToken(value, clock.instant().plusSeconds(expiresIn).minus(TOKEN_REFRESH_MARGIN));
    }

    private String assertion(ServiceAccount account) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", account.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", account.tokenUrl());
        claims.put("iat", now);
        claims.put("exp", now + TOKEN_LIFETIME.toSeconds());
        Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = base64Url.encodeToString(json(Map.of("alg", "RS256", "typ", "JWT")).getBytes(StandardCharsets.UTF_8))
                + "." + base64Url.encodeToString(json(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(account.key());
            rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url.encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new TransientSendException("Could not sign the FCM access token request", e);
        }
    }

    private static Map<String, Object> payload(Outbound message, String link) {
        Map<String, Object> notification = new LinkedHashMap<>();
        if (message.subject() != null && !message.subject().isBlank()) {
            notification.put("title", message.subject());
        }
        notification.put("body", message.body());
        Map<String, Object> fcmMessage = new LinkedHashMap<>();
        fcmMessage.put("token", message.recipient());
        fcmMessage.put("notification", notification);
        fcmMessage.put("data", Map.of("messageId", message.messageId().toString()));
        if (link != null && !link.isBlank()) {
            fcmMessage.put("webpush", Map.of("fcm_options", Map.of("link", link.trim())));
        }
        return Map.of("message", fcmMessage);
    }

    private RuntimeException classify(int code, String body) {
        JsonNode error = tree(body).path("error");
        String status = error.path("status").asText("");
        String fcmCode = "";
        for (JsonNode detail : error.path("details")) {
            if (detail.hasNonNull("errorCode")) {
                fcmCode = detail.get("errorCode").asText();
            }
        }
        String reason = fcmCode.isEmpty() ? status : fcmCode;
        String detail = "HTTP " + code + " from FCM" + (reason.isEmpty() ? "" : " (" + reason + ")") + ": "
                + abbreviate(error.path("message").asText(""));
        boolean recipientProblem = "UNREGISTERED".equals(fcmCode) || "SENDER_ID_MISMATCH".equals(fcmCode)
                || "INVALID_ARGUMENT".equals(status) || code == 404;
        if (recipientProblem) {
            return new PermanentSendException(detail);
        }
        if (code == 401 || code == 403 || code == 408 || code == 429 || code >= 500) {
            return new TransientSendException(detail);
        }
        return new PermanentSendException(detail);
    }

    private HttpResponse<String> post(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TransientSendException("FCM call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientSendException("Interrupted while calling FCM", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialise an FCM request", e);
        }
    }

    private JsonNode tree(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    private String field(String body, String name) {
        JsonNode value = tree(body).get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private long number(String body, String name, long fallback) {
        JsonNode value = tree(body).get(name);
        return value != null && value.canConvertToLong() ? value.asLong() : fallback;
    }

    private static String trimTrailingSlash(String url) {
        return url.trim().replaceAll("/+$", "");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
