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

package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    Optional<Template> findByClientIdAndName(UUID clientId, String name);

    Optional<Template> findByClientIdIsNullAndName(String name);

    /** What a client can see and use: its own templates plus the shared ones. */
    List<Template> findByClientIdOrClientIdIsNullOrderByNameAsc(UUID clientId);

    List<Template> findByClientIdIsNullOrderByNameAsc();

    long countByClientId(UUID clientId);
}
