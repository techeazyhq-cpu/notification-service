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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.TemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCacheTest {

    private final TemplateRepository repository = mock(TemplateRepository.class);
    private final UUID client = UUID.randomUUID();

    private Template template(String body) {
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setName("otp");
        t.setChannel(Channel.SMS);
        t.setBody(body);
        return t;
    }

    @Test
    void aFoundTemplateIsReadFromTheDatabaseOnceWithinTheLifetime() {
        when(repository.findByClientIdAndName(client, "otp")).thenReturn(Optional.of(template("v1")));
        TemplateCache cache = new TemplateCache(repository, 60);

        assertThat(cache.find(client, "otp")).get().extracting(TemplateCache.TemplateContent::body).isEqualTo("v1");
        assertThat(cache.find(client, "otp")).isPresent();

        verify(repository, times(1)).findByClientIdAndName(client, "otp");
    }

    @Test
    void aMissingTemplateIsNeverCachedSoANewOneIsFoundAtOnce() {
        when(repository.findByClientIdAndName(client, "new")).thenReturn(Optional.empty());
        when(repository.findByClientIdIsNullAndName("new")).thenReturn(Optional.empty());
        TemplateCache cache = new TemplateCache(repository, 60);
        assertThat(cache.find(client, "new")).isEmpty();

        when(repository.findByClientIdAndName(client, "new")).thenReturn(Optional.of(template("created")));

        assertThat(cache.find(client, "new")).isPresent();
    }

    @Test
    void theOwnTemplateWinsOverASharedOne() {
        when(repository.findByClientIdAndName(client, "otp")).thenReturn(Optional.of(template("own")));
        when(repository.findByClientIdIsNullAndName("otp")).thenReturn(Optional.of(template("shared")));

        assertThat(new TemplateCache(repository, 60).find(client, "otp")).get().extracting(TemplateCache.TemplateContent::body).isEqualTo("own");
    }

    @Test
    void invalidatingAClientDropsOnlyItsEntries() {
        UUID other = UUID.randomUUID();
        when(repository.findByClientIdAndName(client, "otp")).thenReturn(Optional.of(template("v1")));
        when(repository.findByClientIdAndName(other, "otp")).thenReturn(Optional.of(template("v1")));
        TemplateCache cache = new TemplateCache(repository, 60);
        cache.find(client, "otp");
        cache.find(other, "otp");

        cache.invalidateAfterCommit(client);
        when(repository.findByClientIdAndName(client, "otp")).thenReturn(Optional.of(template("v2")));

        assertThat(cache.find(client, "otp")).get().extracting(TemplateCache.TemplateContent::body).isEqualTo("v2");
        cache.find(other, "otp");
        verify(repository, times(1)).findByClientIdAndName(other, "otp");
    }

    @Test
    void aZeroLifetimeDisablesCaching() {
        when(repository.findByClientIdAndName(client, "otp")).thenReturn(Optional.of(template("v1")));
        TemplateCache cache = new TemplateCache(repository, 0);

        cache.find(client, "otp");
        cache.find(client, "otp");

        verify(repository, times(2)).findByClientIdAndName(client, "otp");
    }
}
