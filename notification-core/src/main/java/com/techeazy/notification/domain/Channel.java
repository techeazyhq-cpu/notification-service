package com.techeazy.notification.domain;

public enum Channel {
    EMAIL, SMS, WHATSAPP, PUSH;

    public String topicSuffix() {
        return name().toLowerCase();
    }
}
