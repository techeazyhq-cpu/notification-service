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
package com.techeazy.notification.adminapi;

import java.util.Optional;
import com.techeazy.notification.application.ProviderDestinationPolicy;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ProviderSecrets;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ProvidersControllerTest {

    private final ProviderConfigRepository repository = mock(ProviderConfigRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ProviderDestinationPolicy destinations = new ProviderDestinationPolicy(Set.of("catcher"), true,
                host -> List.of(InetAddress.getAllByName(host)));
        ProvidersController controller = new ProvidersController(repository,
                new ProviderSecrets(new AesGcmCipher("test-key")), destinations);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AdminErrorHandler(Optional::empty)).build();
        when(repository.save(any(ProviderConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MockHttpServletResponse createGateway(String url) throws Exception {
        return mvc.perform(post("/api/admin/providers").contentType(MediaType.APPLICATION_JSON).content("""
                {"channel":"SMS","name":"sms-gateway","type":"HTTP_JSON","enabled":true,"settings":{"url":"%s"}}
                """.formatted(url))).andReturn().getResponse();
    }

    @Test
    void aProviderPointedAtAnInternalAddressIsRefusedWithTheReasonAndNotSaved() throws Exception {
        MockHttpServletResponse response = createGateway("https://169.254.169.254/latest/meta-data/");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"PROVIDER_DESTINATION_REFUSED\"")
                .contains("not a public address");
        verify(repository, never()).save(any());
    }

    @Test
    void aProviderOnATrustedHostIsSaved() throws Exception {
        assertThat(createGateway("http://catcher:9000/sms").getStatus()).isEqualTo(201);
    }
}
