package com.evcharging.service.impl;

import org.springframework.stereotype.Service;

import com.evcharging.notification.NotificationSender;
import com.evcharging.service.NotificationService;

@Service
@org.springframework.context.annotation.Profile("integration-demo")
public class NotificationServiceImpl implements NotificationService {
    private final NotificationSender notificationSender;

    public NotificationServiceImpl(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    @Override
    public void sendBookingConfirmation(String message) {
        notificationSender.send(message);
    }
}
