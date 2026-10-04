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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.Outbound;
import com.techeazy.notification.dispatcher.provider.ChannelProvider.PermanentSendException;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The destination policy checks the configured URL only, so the provider must never follow a redirect: otherwise an
 * allowed public gateway could answer {@code 302 Location: http://169.254.169.254/} and get past it.
 */
class HttpJsonProviderTest {

    private final AtomicInteger internalHits = new AtomicInteger();
    private HttpServer internalService;
    private HttpServer redirectingGateway;

    @BeforeEach
    void startServers() throws IOException {
        internalService = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        internalService.createContext("/", exchange -> {
            internalHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        internalService.start();
        redirectingGateway = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirectingGateway.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + internalService.getAddress().getPort() + "/secrets");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirectingGateway.start();
    }

    @AfterEach
    void stopServers() {
        redirectingGateway.stop(0);
        internalService.stop(0);
    }

    @Test
    void aRedirectFromTheGatewayIsNeverFollowed() {
        ProviderConfig gateway = new ProviderConfig();
        gateway.setType(ProviderType.HTTP_JSON);
        gateway.getSettings().put("url", "http://127.0.0.1:" + redirectingGateway.getAddress().getPort() + "/send");
        Outbound message = new Outbound(UUID.randomUUID(), Channel.SMS, "+14155550123", null, "hi");

        HttpJsonProvider provider = new HttpJsonProvider(new ObjectMapper());
        assertThatThrownBy(() -> provider.send(gateway, message))
                .isInstanceOf(PermanentSendException.class).hasMessageContaining("HTTP 302");
        assertThat(internalHits.get()).isZero();
    }
}
