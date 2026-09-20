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

import com.techeazy.notification.domain.NotificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository
        extends JpaRepository<NotificationRequest, UUID>, JpaSpecificationExecutor<NotificationRequest> {

    Optional<NotificationRequest> findByIdAndClientId(UUID id, UUID clientId);

    Optional<NotificationRequest> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

    long countByClientIdAndCreatedAtAfter(UUID clientId, Instant since);
}
