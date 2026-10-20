package com.evcharging.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development mock only. No email is sent; message contents are never logged. */
public class EmailNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    @Override
    public void send(String message) {
        log.info("Mock email notification accepted; no email was sent");
    }
}
