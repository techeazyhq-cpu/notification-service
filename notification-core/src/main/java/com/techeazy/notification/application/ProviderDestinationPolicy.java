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

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Decides where providers may connect, so that whoever can edit a provider cannot point the platform at its own
 * internal network: databases, the broker, cloud metadata at {@code 169.254.169.254}, or other services (see ADR-022).
 *
 * <p>A trusted host, listed by the operator as an exact name or as {@code *.domain} for its subdomains, is allowed as
 * it is, internal or plain HTTP included: an on-premises gateway, or Mailpit and the catcher locally. Any other host
 * must be allowed in general ({@code otherPublicHostsAllowed}), must be reached over HTTPS when it is an HTTP gateway,
 * and must resolve only to public addresses. A missing destination is not decided here; the provider reports it.
 */
public final class ProviderDestinationPolicy {

    private static final String URL_SETTING = "url";
    private static final String HOST_SETTING = "host";
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String SUBDOMAIN_WILDCARD = "*.";

    private static final List<AddressBlock> NON_PUBLIC_BLOCKS = Stream.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8").map(AddressBlock::parse).toList();

    private final Set<String> trustedHosts;
    private final boolean otherPublicHostsAllowed;
    private final HostResolver resolver;

    public ProviderDestinationPolicy(Collection<String> trustedHosts, boolean otherPublicHostsAllowed,
                                     HostResolver resolver) {
        this.trustedHosts = trustedHosts.stream().filter(host -> host != null && !host.isBlank())
                .map(ProviderDestinationPolicy::normalize).collect(Collectors.toUnmodifiableSet());
        this.otherPublicHostsAllowed = otherPublicHostsAllowed;
        this.resolver = resolver;
    }

    /** @throws ProviderDestinationRefusedException if the provider's destination is not allowed */
    public void check(ProviderType type, Map<String, String> settings) {
        switch (type) {
            case HTTP_JSON -> checkGateway(settings.get(URL_SETTING));
            case SMTP -> checkMailServer(settings.get(HOST_SETTING));
        }
    }

    private void checkGateway(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        URI uri = parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
            throw new ProviderDestinationRefusedException("The provider URL must be an http or https URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ProviderDestinationRefusedException(
                    "The provider URL must not contain credentials; use the authHeader setting");
        }
        if (uri.getHost() == null) {
            throw new ProviderDestinationRefusedException("The provider URL has no host");
        }
        String host = normalize(uri.getHost());
        if (isTrusted(host)) {
            return;
        }
        if (!HTTPS.equals(scheme)) {
            throw new ProviderDestinationRefusedException(
                    "The provider URL must use https: " + host + " is not a trusted provider host");
        }
        checkUntrustedHost(host);
    }

    private void checkMailServer(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String normalized = normalize(host);
        if (!isTrusted(normalized)) {
            checkUntrustedHost(normalized);
        }
    }

    private void checkUntrustedHost(String host) {
        if (!otherPublicHostsAllowed) {
            throw new ProviderDestinationRefusedException(host + " is not one of the trusted provider hosts");
        }
        for (InetAddress address : resolve(host)) {
            if (NON_PUBLIC_BLOCKS.stream().anyMatch(block -> block.contains(address))) {
                throw new ProviderDestinationRefusedException(
                        host + " resolves to " + address.getHostAddress() + ", which is not a public address");
            }
        }
    }

    private List<InetAddress> resolve(String host) {
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            addresses = List.of();
        }
        if (addresses.isEmpty()) {
            throw new ProviderDestinationRefusedException(host + " could not be resolved");
        }
        return addresses;
    }

    private boolean isTrusted(String host) {
        return trustedHosts.stream().anyMatch(trusted -> trusted.startsWith(SUBDOMAIN_WILDCARD)
                ? host.endsWith(trusted.substring(1))
                : host.equals(trusted));
    }

    private static URI parse(String url) {
        try {
            return new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new ProviderDestinationRefusedException("The provider URL is not a valid URL");
        }
    }

    private static String normalize(String host) {
        String unbracketed = host.trim().replaceFirst("^\\[(.*)]$", "$1").toLowerCase(Locale.ROOT);
        return unbracketed.endsWith(".") ? unbracketed.substring(0, unbracketed.length() - 1) : unbracketed;
    }

    /** An address block in CIDR notation, such as {@code 10.0.0.0/8} or {@code fc00::/7}. */
    private record AddressBlock(byte[] network, int prefixLength) {

        static AddressBlock parse(String cidr) {
            String[] parts = cidr.split("/");
            try {
                return new AddressBlock(InetAddress.getByName(parts[0]).getAddress(), Integer.parseInt(parts[1]));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Not an address block: " + cidr, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
