package com.techeazy.notification.adminapi;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Aggregations run on the primary database for now; move to a read replica or analytics store at scale. */
@RestController
@RequestMapping("/api/admin/dashboard")
class DashboardController {

    record Count(String channel, String status, long count) {}

    record Summary(int hours, List<Count> counts, long backlog, long requests) {}

    record Point(Instant bucket, String channel, String status, long count) {}

    private final JdbcTemplate jdbc;

    DashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    Summary summary(@RequestParam(defaultValue = "24") int hours) {
        Timestamp since = since(hours);
        List<Count> counts = jdbc.query(
                "select channel, status, count(*) from notification_message where created_at >= ? group by channel, status",
                (rs, i) -> new Count(rs.getString(1), rs.getString(2), rs.getLong(3)), since);
        Long backlog = jdbc.queryForObject(
                "select count(*) from notification_message where status in ('PENDING','QUEUED','PROCESSING','RETRYING')", Long.class);
        Long requests = jdbc.queryForObject("select count(*) from notification_request where created_at >= ?", Long.class, since);
        return new Summary(hours, counts, backlog == null ? 0 : backlog, requests == null ? 0 : requests);
    }

    @GetMapping("/timeseries")
    List<Point> timeseries(@RequestParam(defaultValue = "24") int hours,
                           @RequestParam(defaultValue = "hour") String bucket) {
        if (!Map.of("minute", 1, "hour", 1, "day", 1).containsKey(bucket)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bucket must be minute, hour or day");
        }
        return jdbc.query("select date_trunc('" + bucket + "', created_at) b, channel, status, count(*) "
                        + "from notification_message where created_at >= ? group by b, channel, status order by b",
                (rs, i) -> new Point(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getLong(4)),
                since(hours));
    }

    private static Timestamp since(int hours) {
        return Timestamp.from(Instant.now().minus(Math.min(Math.max(hours, 1), 24 * 90), ChronoUnit.HOURS));
    }
}
