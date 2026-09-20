package com.techeazy.notification.application;

import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recovers messages that fell between database commit and broker publish (PENDING), and messages
 * whose worker died mid-send (PROCESSING). Delivery is therefore at-least-once; workers make
 * redelivery safe by atomically claiming a message before sending.
 */
@Component
@ConditionalOnProperty(prefix = "notification.sweeper", name = "enabled", havingValue = "true")
public class OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

    private final NotificationMessageRepository messages;
    private final OutboxPublisher outbox;
    private final NotificationProperties props;

    public OutboxSweeper(NotificationMessageRepository messages, OutboxPublisher outbox, NotificationProperties props) {
        this.messages = messages;
        this.outbox = outbox;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${notification.sweeper.interval-ms:10000}")
    @Transactional
    public void sweep() {
        var cfg = props.getSweeper();
        Instant now = Instant.now();
        republish(messages.lockStale(MessageStatus.PENDING.name(), now.minus(Duration.ofSeconds(cfg.getPendingAgeSeconds())),
                cfg.getBatchSize()), "pending");
        republish(messages.lockStale(MessageStatus.PROCESSING.name(), now.minus(Duration.ofSeconds(cfg.getProcessingTimeoutSeconds())),
                cfg.getBatchSize()), "stuck processing");
    }

    private void republish(List<NotificationMessage> stale, String what) {
        if (stale.isEmpty()) return;
        Set<UUID> ok = new HashSet<>(outbox.publishOnly(stale));
        Instant now = Instant.now();
        for (NotificationMessage m : stale) {
            if (ok.contains(m.getId())) m.setStatus(MessageStatus.QUEUED);
            else m.setStatus(MessageStatus.PENDING);
            m.setUpdatedAt(now);
        }
        log.info("Sweeper republished {}/{} {} messages", ok.size(), stale.size(), what);
    }
}
