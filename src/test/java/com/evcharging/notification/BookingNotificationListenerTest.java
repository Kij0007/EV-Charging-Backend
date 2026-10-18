package com.evcharging.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.evcharging.service.NotificationService;
import com.evcharging.service.impl.NotificationServiceImpl;

@ExtendWith(OutputCaptureExtension.class)
class BookingNotificationListenerTest {
    @Test
    void listenerDelegatesCommittedConfirmation() {
        NotificationService service = mock(NotificationService.class);
        new BookingNotificationListener(service).onBookingConfirmed(new BookingConfirmedEvent(1));
        verify(service).sendBookingConfirmation("Booking confirmed successfully");
    }

    @Test
    void listenerDoesNotFailBookingResponseOrLogProviderDetails(CapturedOutput output) {
        NotificationService service = mock(NotificationService.class);
        doThrow(new IllegalStateException("sensitive-provider-details"))
                .when(service).sendBookingConfirmation("Booking confirmed successfully");
        assertThatCode(() -> new BookingNotificationListener(service)
                .onBookingConfirmed(new BookingConfirmedEvent(42))).doesNotThrowAnyException();
        assertThat(output).contains("Mock booking notification failed after commit")
                .doesNotContain("sensitive-provider-details");
    }

    @Test
    void notificationServiceDelegatesToSelectedSender() {
        NotificationSender sender = mock(NotificationSender.class);
        new NotificationServiceImpl(sender).sendBookingConfirmation("confirmation");
        verify(sender).send("confirmation");
    }

    @Test
    void mockSendersClearlyLabelTheirBehaviorAndDoNotLogContents(CapturedOutput output) {
        new EmailNotificationSender().send("private-recipient-or-message");
        new SmsNotificationSender().send("private-recipient-or-message");
        assertThat(output).contains("Mock email notification", "Mock SMS notification")
                .doesNotContain("private-recipient-or-message");
    }
}
