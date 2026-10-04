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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fingerprints the recipients of messages accepted before fingerprints existed, so they too can be found for a
 * recipient activity report (ADR-035). New messages are fingerprinted when accepted, so once the backlog is done each
 * run finds nothing, through a partial index that is empty. Messages already erased cannot be fingerprinted: their
 * address is gone.
 */
@Component
@ConditionalOnProperty(prefix = "notification.fingerprint-backfill", name = "enabled", havingValue = "true")
public class RecipientFingerprintBackfill {

    private static final Logger log = LoggerFactory.getLogger(RecipientFingerprintBackfill.class);

    private record Unfingerprinted(UUID id, String recipient) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final RecipientFingerprints fingerprints;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public RecipientFingerprintBackfill(JdbcClient jdbc, JdbcTemplate batch, RecipientFingerprints fingerprints,
                                        @Value("${notification.fingerprint-backfill.batch-size:1000}") int batchSize,
                                        @Value("${notification.fingerprint-backfill.max-batches-per-run:50}")
                                        int maxBatchesPerRun) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.fingerprints = fingerprints;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /** @return how many messages this run fingerprinted */
    @Scheduled(fixedDelayString = "${notification.fingerprint-backfill.interval-ms:60000}")
    public int run() {
        int total = 0;
        for (int round = 0; round < maxBatchesPerRun; round++) {
            List<Unfingerprinted> rows = jdbc.sql("""
                    SELECT id, recipient FROM notification_message
                    WHERE recipient_fingerprint IS NULL AND erased_at IS NULL
                    ORDER BY created_at LIMIT :batch""")
                    .param("batch", batchSize)
                    .query((rs, n) -> new Unfingerprinted(rs.getObject("id", UUID.class), rs.getString("recipient")))
                    .list();
            if (rows.isEmpty()) {
                break;
            }
            batch.batchUpdate("""
                    UPDATE notification_message SET recipient_fingerprint = ?
                    WHERE id = ? AND recipient_fingerprint IS NULL AND erased_at IS NULL""",
                    rows.stream().map(row -> new Object[] {fingerprints.of(row.recipient()), row.id()}).toList());
            total += rows.size();
            if (rows.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Fingerprinted the recipients of {} earlier message(s)", total);
        }
        return total;
    }
}
