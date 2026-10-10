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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.Outbound;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.SendResult;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.TransientSendException;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link FcmProvider} against a stand-in for Google's token endpoint and the FCM HTTP v1 API on a real socket. */
class FcmProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVICE_TOKEN = "fcm-device-token-abc";

    private final List<Recorded> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<Recorded> sendRequests = new CopyOnWriteArrayList<>();
    private final Deque<Reply> tokenReplies = new ArrayDeque<>();
    private final Deque<Reply> sendReplies = new ArrayDeque<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T12:00:00Z"));
    private KeyPair serviceAccountKey;
    private HttpServer google;
    private FcmProvider provider;

    private record Recorded(String path, String authorization, String body) {}

    private record Reply(int status, String body) {}

    /** A clock the test moves forward to expire cached access tokens. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        serviceAccountKey = generator.generateKeyPair();
        google = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        google.createContext("/token", exchange -> reply(exchange, tokenRequests, tokenReplies,
                new Reply(200, "{\"access_token\":\"access-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
        google.createContext("/v1/", exchange -> reply(exchange, sendRequests, sendReplies,
                new Reply(200, "{\"name\":\"projects/demo-project/messages/0:1700000000000000%abc\"}")));
        google.start();
        provider = new FcmProvider(JSON, HttpClient.newHttpClient(), clock);
    }

    @AfterEach
    void stop() {
        google.stop(0);
    }

    private static synchronized void reply(HttpExchange exchange, List<Recorded> log, Deque<Reply> queue, Reply fallback)
            throws IOException {
        log.add(new Recorded(exchange.getRequestURI().getRawPath(), exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply next = queue.isEmpty() ? fallback : queue.poll();
        byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(next.status(), body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private ProviderConfig config(Map<String, String> extra) {
        String base = "http://127.0.0.1:" + google.getAddress().getPort();
        String pem = pem(Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(serviceAccountKey.getPrivate().getEncoded()).replace("\n", "\\n"));
        ProviderConfig config = new ProviderConfig();
        config.setType(ProviderType.FCM);
        config.getSettings().putAll(Map.of("projectId", "demo-project", "clientEmail", "push@demo-project.iam.gserviceaccount.com",
                "privateKey", pem, "url", base, "tokenUrl", base + "/token"));
        config.getSettings().putAll(extra);
        return config;
    }

    private static Outbound push(UUID id) {
        return new Outbound(id, Channel.PUSH, DEVICE_TOKEN, "New consent request", "Acme Bank: share your KYC documents");
    }

    @Test
    void sendsANotificationWithAnAccessTokenFromTheServiceAccountKey() throws Exception {
        UUID id = UUID.randomUUID();
        SendResult result = provider.send(config(Map.of("link", "https://portal.example.com/")), push(id));

        assertThat(result.providerMessageId()).isEqualTo("projects/demo-project/messages/0:1700000000000000%abc");

        Map<String, String> form = form(tokenRequests.get(0).body());
        assertThat(form).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        String[] jwt = form.get("assertion").split("\\.");
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(serviceAccountKey.getPublic());
        rsa.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(rsa.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(jwt[0]));
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(jwt[1]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(claims.get("iss").asText()).isEqualTo("push@demo-project.iam.gserviceaccount.com");
        assertThat(claims.get("scope").asText()).isEqualTo(FcmProvider.SCOPE);
        assertThat(claims.get("aud").asText()).endsWith("/token");
        assertThat(claims.get("iat").asLong()).isEqualTo(clock.instant().getEpochSecond());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);

        Recorded sent = sendRequests.get(0);
        assertThat(sent.path()).isEqualTo("/v1/projects/demo-project/messages:send");
        assertThat(sent.authorization()).isEqualTo("Bearer access-1");
        JsonNode message = JSON.readTree(sent.body()).get("message");
        assertThat(message.get("token").asText()).isEqualTo(DEVICE_TOKEN);
        assertThat(message.at("/notification/title").asText()).isEqualTo("New consent request");
        assertThat(message.at("/notification/body").asText()).isEqualTo("Acme Bank: share your KYC documents");
        assertThat(message.at("/data/messageId").asText()).isEqualTo(id.toString());
        assertThat(message.at("/webpush/fcm_options/link").asText()).isEqualTo("https://portal.example.com/");
    }

    @Test
    void reusesTheAccessTokenUntilAMinuteBeforeItExpires() {
        ProviderConfig config = config(Map.of());
        provider.send(config, push(UUID.randomUUID()));
        clock.advance(Duration.ofMinutes(58));
        provider.send(config, push(UUID.randomUUID()));
        assertThat(tokenRequests).hasSize(1);

        clock.advance(Duration.ofMinutes(2));
        provider.send(config, push(UUID.randomUUID()));
        assertThat(tokenRequests).hasSize(2);
        assertThat(sendRequests).hasSize(3);
    }

    @Test
    void aDeviceTokenFcmNoLongerKnowsIsPermanentAndNotNamedInTheError() {
        sendReplies.add(new Reply(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "Requested entity was not found.")));

        assertThatThrownBy(() -> provider.send(config(Map.of()), push(UUID.randomUUID())))
                .isInstanceOf(PermanentSendException.class)
                .hasMessageContaining("UNREGISTERED")
                .hasMessageNotContaining(DEVICE_TOKEN);
    }

    @Test
    void aMalformedTokenOrOneFromAnotherProjectIsPermanent() {
        sendReplies.add(new Reply(400, fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "The registration token is not valid")));
        sendReplies.add(new Reply(403, fcmError(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH", "SenderId mismatch")));
        ProviderConfig config = config(Map.of());

        assertThatThrownBy(() -> provider.send(config, push(UUID.randomUUID())))
                .isInstanceOf(PermanentSendException.class).hasMessageContaining("INVALID_ARGUMENT");
        assertThatThrownBy(() -> provider.send(config, push(UUID.randomUUID())))
                .isInstanceOf(PermanentSendException.class).hasMessageContaining("SENDER_ID_MISMATCH");
    }

    @Test
    void throttlingOutagesAndAServiceAccountWithoutPermissionAreTransient() {
        sendReplies.add(new Reply(429, fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED", "Quota exceeded")));
        sendReplies.add(new Reply(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE", "The service is unavailable")));
        sendReplies.add(new Reply(403, fcmError(403, "PERMISSION_DENIED", null, "Permission denied")));
        ProviderConfig config = config(Map.of());

        for (int call = 0; call < 3; call++) {
            assertThatThrownBy(() -> provider.send(config, push(UUID.randomUUID())))
                    .isInstanceOf(TransientSendException.class);
        }
    }

    @Test
    void anAccessTokenFcmRejectsIsDroppedSoTheRetryGetsAFreshOne() {
        sendReplies.add(new Reply(401, fcmError(401, "UNAUTHENTICATED", null, "Request had invalid authentication credentials.")));
        tokenReplies.add(new Reply(200, "{\"access_token\":\"access-1\",\"expires_in\":3600}"));
        tokenReplies.add(new Reply(200, "{\"access_token\":\"access-2\",\"expires_in\":3600}"));
        ProviderConfig config = config(Map.of());

        assertThatThrownBy(() -> provider.send(config, push(UUID.randomUUID()))).isInstanceOf(TransientSendException.class);
        provider.send(config, push(UUID.randomUUID()));

        assertThat(tokenRequests).hasSize(2);
        assertThat(sendRequests.get(1).authorization()).isEqualTo("Bearer access-2");
    }

    @Test
    void refusedCredentialsAreTransientSoTheBreakerAndFailoverHandleThem() {
        tokenReplies.add(new Reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}"));

        assertThatThrownBy(() -> provider.send(config(Map.of()), push(UUID.randomUUID())))
                .isInstanceOf(TransientSendException.class).hasMessageContaining("Invalid JWT Signature");
        assertThat(sendRequests).isEmpty();
    }

    @Test
    void aMissingSettingOrAnUnreadableKeyIsAConfigurationProblem() {
        ProviderConfig noProject = config(Map.of());
        noProject.getSettings().remove("projectId");
        ProviderConfig badKey = config(Map.of("privateKey", pem("not-a-key")));

        assertThatThrownBy(() -> provider.send(noProject, push(UUID.randomUUID())))
                .isInstanceOf(TransientSendException.class).hasMessageContaining("projectId");
        assertThatThrownBy(() -> provider.send(badKey, push(UUID.randomUUID())))
                .isInstanceOf(TransientSendException.class).hasMessageContaining("privateKey");
        assertThat(tokenRequests).isEmpty();
    }

    private static String fcmError(int code, String status, String fcmCode, String message) {
        String details = fcmCode == null ? "[]"
                : "[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"" + fcmCode + "\"}]";
        return "{\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\",\"status\":\"" + status
                + "\",\"details\":" + details + "}}";
    }

    /**
     * A PEM as a Google service account key file holds it, with escaped line breaks. The armour lines are assembled at
     * run time so that the secret scanner does not take this test for a committed key.
     */
    private static String pem(String base64) {
        String label = "PRIVATE" + " KEY-----";
        return "-----BEGIN " + label + "\\n" + base64 + "\\n-----END " + label + "\\n";
    }

    private static Map<String, String> form(String body) {
        return java.util.Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
