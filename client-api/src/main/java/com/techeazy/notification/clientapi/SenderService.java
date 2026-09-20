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

package com.techeazy.notification.clientapi;

import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.domain.Channel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the e-mail sender addresses a client may send from.
 *
 * <p>An address is usable only after its owner opens the link mailed to it (single use, 24 hours), so a client cannot
 * send as someone else. Limits keep the confirmation mail from being used as a spam relay: a client may hold a
 * bounded number of addresses, and a confirmation can be re-sent at most once a minute. The address in use for a
 * message is chosen at ingest and copied onto the request, like the content, so later changes never affect a request
 * already accepted.
 */
@Service
public class SenderService {

    static final Duration TOKEN_LIFETIME = Duration.ofHours(24);
    static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final SenderRepository senders;
    private final VerificationMailer mailer;
    private final Clock clock;
    private final String publicBaseUrl;
    private final int maxSendersPerClient;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SenderService(SenderRepository senders, VerificationMailer mailer,
                         @Value("${client-api.public-base-url:http://localhost:8080}") String publicBaseUrl,
                         @Value("${client-api.max-senders-per-client:10}") int maxSendersPerClient) {
        this(senders, mailer, Clock.systemUTC(), publicBaseUrl, maxSendersPerClient);
    }

    SenderService(SenderRepository senders, VerificationMailer mailer, Clock clock, String publicBaseUrl, int maxSendersPerClient) {
        this.senders = senders;
        this.mailer = mailer;
        this.clock = clock;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.maxSendersPerClient = maxSendersPerClient;
    }

    public List<SenderAddress> list(AuthenticatedClient client) {
        return senders.findByClient(client.id());
    }

    public SenderAddress add(AuthenticatedClient client, String email, String displayName) {
        requireEmailChannel(client);
        String address = normalise(email);
        String reason = RecipientValidator.check(Channel.EMAIL, address);
        if (reason != null) {
            throw ApiException.badRequest("email: " + reason);
        }
        if (senders.countByClient(client.id()) >= maxSendersPerClient) {
            throw ApiException.badRequest("At most " + maxSendersPerClient + " sender addresses per client");
        }
        if (senders.emailExists(client.id(), address)) {
            throw ApiException.conflict("SENDER_EXISTS", "That address is already registered");
        }
        Instant now = clock.instant();
        String token = newToken();
        SenderAddress sender = new SenderAddress(UUID.randomUUID(), client.id(), address, clean(displayName),
                SenderAddress.Status.PENDING, false, now, null, now);
        try {
            senders.insert(sender, ApiKeys.hash(token), now.plus(TOKEN_LIFETIME));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SENDER_EXISTS", "That address is already registered");
        }
        try {
            mailer.send(address, client.name(), verificationUrl(token));
        } catch (ApiException e) {
            senders.delete(client.id(), sender.id());
            throw e;
        }
        return sender;
    }

    public SenderAddress resend(AuthenticatedClient client, UUID id) {
        SenderAddress sender = require(client, id);
        if (sender.verified()) {
            throw ApiException.conflict("INVALID_STATE", "That address is already verified");
        }
        Instant now = clock.instant();
        if (sender.verificationSentAt() != null && sender.verificationSentAt().plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Wait a minute before asking for another confirmation e-mail");
        }
        String token = newToken();
        mailer.send(sender.email(), client.name(), verificationUrl(token));
        senders.replaceToken(sender.id(), ApiKeys.hash(token), now.plus(TOKEN_LIFETIME), now);
        return require(client, id);
    }

    /** Called from the link in the e-mail, which carries no API key; the token is the credential. */
    public Optional<SenderAddress> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return senders.verifyByToken(ApiKeys.hash(token.trim()), clock.instant());
    }

    @Transactional
    public SenderAddress makeDefault(AuthenticatedClient client, UUID id) {
        SenderAddress sender = require(client, id);
        if (!sender.verified()) {
            throw ApiException.conflict("SENDER_NOT_VERIFIED", "Only a verified address can be the default");
        }
        senders.clearDefault(client.id());
        senders.markDefault(client.id(), id);
        return require(client, id);
    }

    public void delete(AuthenticatedClient client, UUID id) {
        if (!senders.delete(client.id(), id)) {
            throw ApiException.notFound("Sender address not found");
        }
    }

    /**
     * The sender for a message: the requested {@code from} address (which must be verified for this client), else
     * the client's default, else none, meaning the platform's own address is used.
     */
    public Optional<SenderAddress> resolve(AuthenticatedClient client, Channel channel, String from) {
        if (from != null && !from.isBlank()) {
            if (channel != Channel.EMAIL) {
                throw ApiException.badRequest("from is only supported for the EMAIL channel");
            }
            return Optional.of(senders.findVerifiedByEmail(client.id(), normalise(from)).orElseThrow(() -> new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "SENDER_NOT_VERIFIED",
                    "The from address is not a verified sender of this client; register and confirm it first")));
        }
        return channel == Channel.EMAIL ? senders.findVerifiedDefault(client.id()) : Optional.empty();
    }

    private SenderAddress require(AuthenticatedClient client, UUID id) {
        return senders.find(client.id(), id).orElseThrow(() -> ApiException.notFound("Sender address not found"));
    }

    private static void requireEmailChannel(AuthenticatedClient client) {
        if (!client.allows(Channel.EMAIL)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_NOT_ALLOWED", "Client is not allowed to use channel EMAIL");
        }
    }

    private String verificationUrl(String token) {
        return publicBaseUrl + "/v1/senders/verify?token=" + token;
    }

    private String newToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String clean(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return displayName.trim().replaceAll("[\\r\\n<>\"]", "");
    }
}
