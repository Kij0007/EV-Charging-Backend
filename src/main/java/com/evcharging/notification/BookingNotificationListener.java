package com.evcharging.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.evcharging.service.NotificationService;

@Component
@org.springframework.context.annotation.Profile("integration-demo")
public class BookingNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationListener.class);
    private final NotificationService notificationService;

    public BookingNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        try {
            notificationService.sendBookingConfirmation("Booking confirmed successfully");
        } catch (RuntimeException exception) {
            // The booking is already committed. Do not expose provider messages or fail its response.
            log.warn("Mock booking notification failed after commit");
        }
    }
}
