package com.evcharging.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development mock only. No SMS is sent; message contents are never logged. */
public class SmsNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(SmsNotificationSender.class);

    @Override
    public void send(String message) {
        log.info("Mock SMS notification accepted; no SMS was sent");
    }
}
