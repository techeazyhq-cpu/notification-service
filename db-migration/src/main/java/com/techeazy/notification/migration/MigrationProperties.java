package com.techeazy.notification.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings, bound from {@code migration.*} (environment variables in the container, see application.yml). */
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {

    private String url = "jdbc:postgresql://localhost:5432/notification";
    // No credentials in code: they come from DB_USER / DB_PASSWORD (see application.yml).
    private String username;
    private String password;
    /** Classpath location of the master changelog. */
    private String changelog = "db/changelog/db.changelog-master.yaml";
    /** Liquibase contexts to run (comma separated); empty runs every changeset that has no context. */
    private String contexts = "";
    /** Tag the current state before applying pending changesets, so a bad release can be undone with rollback-tag. */
    private boolean tagBeforeUpdate = true;
    /** Rollbacks change or delete data, so they are refused unless explicitly enabled. */
    private boolean allowRollback = false;
    /** How long to keep trying to reach the database (it may still be starting) before giving up. */
    private int connectRetries = 30;
    private long connectRetryDelayMs = 2000;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public String getContexts() { return contexts; }
    public void setContexts(String contexts) { this.contexts = contexts; }
    public boolean isTagBeforeUpdate() { return tagBeforeUpdate; }
    public void setTagBeforeUpdate(boolean tagBeforeUpdate) { this.tagBeforeUpdate = tagBeforeUpdate; }
    public boolean isAllowRollback() { return allowRollback; }
    public void setAllowRollback(boolean allowRollback) { this.allowRollback = allowRollback; }
    public int getConnectRetries() { return connectRetries; }
    public void setConnectRetries(int connectRetries) { this.connectRetries = connectRetries; }
    public long getConnectRetryDelayMs() { return connectRetryDelayMs; }
    public void setConnectRetryDelayMs(long connectRetryDelayMs) { this.connectRetryDelayMs = connectRetryDelayMs; }
}
