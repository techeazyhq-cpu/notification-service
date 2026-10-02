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
package com.techeazy.notification.application;

import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.port.HostResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderDestinationPolicyTest {

    private static final Map<String, List<String>> DNS = Map.of(
            "gateway.example.com", List.of("93.184.216.34"),
            "smtp.example.com", List.of("93.184.216.35"),
            "internal.example.com", List.of("10.1.2.3"),
            "rebound.example.com", List.of("93.184.216.36", "127.0.0.1"),
            "catcher", List.of("172.18.0.7"),
            "sms.corp.example", List.of("192.168.10.20"));

    private static final HostResolver FAKE_DNS = host -> {
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return List.of(InetAddress.getAllByName(host));
        }
        List<String> addresses = DNS.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        List<InetAddress> resolved = new ArrayList<>();
        for (String address : addresses) {
            resolved.add(InetAddress.getByName(address));
        }
        return resolved;
    };

    private final ProviderDestinationPolicy publicOnly = new ProviderDestinationPolicy(Set.of(), true, FAKE_DNS);

    private static Map<String, String> gateway(String url) {
        return Map.of("url", url);
    }

    private static Map<String, String> smtp(String host) {
        return Map.of("host", host, "port", "587");
    }

    @Test
    void anHttpsGatewayOnAPublicHostIsAllowed() {
        assertThatCode(() -> publicOnly.check(ProviderType.HTTP_JSON, gateway("https://gateway.example.com/send")))
                .doesNotThrowAnyException();
    }

    @Test
    void plainHttpToAHostNobodyVouchedForIsRefused() {
        assertThatThrownBy(() -> publicOnly.check(ProviderType.HTTP_JSON, gateway("http://gateway.example.com/send")))
                .isInstanceOf(ProviderDestinationRefusedException.class).hasMessageContaining("https");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/", "https://10.0.0.5/", "https://172.16.4.4/", "https://192.168.1.1/",
            "https://169.254.169.254/latest/meta-data/", "https://100.64.0.1/", "https://0.0.0.0/",
            "https://[::1]/", "https://[fd12:3456::1]/", "https://[fe80::1]/", "https://[::ffff:127.0.0.1]/",
            "https://224.0.0.1/", "https://internal.example.com/", "https://catcher/"})
    void anInternalDestinationIsRefusedWhetherWrittenAsAnAddressOrReachedThroughDns(String url) {
        assertThatThrownBy(() -> publicOnly.check(ProviderType.HTTP_JSON, gateway(url)))
                .isInstanceOf(ProviderDestinationRefusedException.class).hasMessageContaining("not a public address");
    }

    @Test
    void aHostWithEvenOneInternalAddressIsRefused() {
        assertThatThrownBy(() -> publicOnly.check(ProviderType.HTTP_JSON, gateway("https://rebound.example.com/")))
                .isInstanceOf(ProviderDestinationRefusedException.class).hasMessageContaining("127.0.0.1");
    }

    @Test
    void aHostThatDoesNotResolveIsRefused() {
        assertThatThrownBy(() -> publicOnly.check(ProviderType.HTTP_JSON, gateway("https://nowhere.example.com/")))
                .isInstanceOf(ProviderDestinationRefusedException.class).hasMessageContaining("could not be resolved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://gateway.example.com/", "gopher://gateway.example.com/",
            "https://user:secret@gateway.example.com/", "not a url", "https:///no-host", "/relative/path"})
    void anythingButAPlainHttpUrlIsRefused(String url) {
        assertThatThrownBy(() -> publicOnly.check(ProviderType.HTTP_JSON, gateway(url)))
                .isInstanceOf(ProviderDestinationRefusedException.class);
    }

    @Test
    void aTrustedHostMayBeInternalAndUsePlainHttp() {
        ProviderDestinationPolicy policy = new ProviderDestinationPolicy(Set.of("catcher"), true, FAKE_DNS);

        assertThatCode(() -> policy.check(ProviderType.HTTP_JSON, gateway("http://catcher:9000/sms")))
                .doesNotThrowAnyException();
    }

    @Test
    void aWildcardTrustsSubdomainsOnlyAndMatchingIgnoresCaseAndATrailingDot() {
        ProviderDestinationPolicy policy = new ProviderDestinationPolicy(Set.of("*.CORP.example"), false, FAKE_DNS);

        assertThatCode(() -> policy.check(ProviderType.HTTP_JSON, gateway("http://SMS.corp.example./send")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check(ProviderType.HTTP_JSON, gateway("https://corp.example/")))
                .isInstanceOf(ProviderDestinationRefusedException.class);
        assertThatThrownBy(() -> policy.check(ProviderType.HTTP_JSON, gateway("https://evilcorp.example/")))
                .isInstanceOf(ProviderDestinationRefusedException.class);
    }

    @Test
    void whenOnlyTrustedHostsAreAllowedEvenAPublicHttpsHostIsRefused() {
        ProviderDestinationPolicy trustedOnly = new ProviderDestinationPolicy(Set.of("catcher"), false, FAKE_DNS);

        assertThatThrownBy(() -> trustedOnly.check(ProviderType.HTTP_JSON, gateway("https://gateway.example.com/")))
                .isInstanceOf(ProviderDestinationRefusedException.class).hasMessageContaining("trusted");
    }

    @Test
    void anSmtpHostIsCheckedTheSameWay() {
        ProviderDestinationPolicy policy = new ProviderDestinationPolicy(Set.of("mailpit"), true, FAKE_DNS);

        assertThatCode(() -> policy.check(ProviderType.SMTP, smtp("smtp.example.com"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(ProviderType.SMTP, smtp("mailpit"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check(ProviderType.SMTP, smtp("internal.example.com")))
                .isInstanceOf(ProviderDestinationRefusedException.class);
        assertThatThrownBy(() -> policy.check(ProviderType.SMTP, smtp("169.254.169.254")))
                .isInstanceOf(ProviderDestinationRefusedException.class);
    }

    @Test
    void aMissingDestinationIsLeftForTheProviderToReport() {
        assertThatCode(() -> publicOnly.check(ProviderType.HTTP_JSON, Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> publicOnly.check(ProviderType.SMTP, Map.of("port", "25"))).doesNotThrowAnyException();
    }
}
