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

import com.techeazy.notification.application.PersonalData;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.FailureKind;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.LikePatterns;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Which failed messages a dead-letter view or a bulk reprocess is about. Reprocessing leaves out messages whose
 * personal data was erased (there is nobody left to send to) and, unless asked, messages that failed for a reason
 * that resending cannot fix.
 */
record DeadLetterFilter(UUID clientId, Channel channel, FailureKind kind, String errorContains, boolean retryableOnly) {

    private static final String FAILURE_KIND = "failureKind";

    Specification<NotificationMessage> viewSpecification() {
        return (root, query, cb) -> {
            List<Predicate> where = base(root, cb);
            if (kind != null) {
                where.add(cb.equal(root.get(FAILURE_KIND), kind));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    Specification<NotificationMessage> reprocessSpecification() {
        return (root, query, cb) -> {
            List<Predicate> where = base(root, cb);
            where.add(cb.notEqual(root.get("recipient"), PersonalData.ERASED));
            if (kind != null) {
                where.add(cb.equal(root.get(FAILURE_KIND), kind));
            } else if (retryableOnly) {
                where.add(cb.or(cb.isNull(root.get(FAILURE_KIND)), cb.notEqual(root.get(FAILURE_KIND), FailureKind.PERMANENT)));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    private List<Predicate> base(jakarta.persistence.criteria.Root<NotificationMessage> root, jakarta.persistence.criteria.CriteriaBuilder cb) {
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(root.get("status"), MessageStatus.FAILED));
        if (clientId != null) {
            where.add(cb.equal(root.get("clientId"), clientId));
        }
        if (channel != null) {
            where.add(cb.equal(root.get("channel"), channel));
        }
        if (errorContains != null && !errorContains.isBlank()) {
            where.add(cb.like(cb.lower(root.get("lastError")), LikePatterns.contains(errorContains), LikePatterns.ESCAPE));
        }
        return where;
    }
}
