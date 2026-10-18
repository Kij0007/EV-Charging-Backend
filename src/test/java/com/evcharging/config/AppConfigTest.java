package com.evcharging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.evcharging.notification.EmailNotificationSender;
import com.evcharging.notification.SmsNotificationSender;
import com.evcharging.payment.RazorpayGateway;
import com.evcharging.payment.StripeGateway;

class AppConfigTest {
    @Test
    void acceptsNormalizedKnownMockSelections() {
        AppConfig config = new AppConfig(" MOCK ", " StRiPe ", " Mock ", " EMAIL ");
        assertThat(config.paymentGateway()).isInstanceOf(StripeGateway.class);
        assertThat(config.notificationSender()).isInstanceOf(EmailNotificationSender.class);
    }

    @Test
    void selectsOtherSupportedMocks() {
        AppConfig config = new AppConfig("mock", "razorpay", "mock", "sms");
        assertThat(config.paymentGateway()).isInstanceOf(RazorpayGateway.class);
        assertThat(config.notificationSender()).isInstanceOf(SmsNotificationSender.class);
    }

    @Test
    void rejectsUnknownPaymentGateway() {
        assertThatThrownBy(() -> new AppConfig("mock", "typo", "mock", "email").paymentGateway())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payment.gateway");
    }

    @Test
    void rejectsUnknownNotificationType() {
        assertThatThrownBy(() -> new AppConfig("mock", "stripe", "mock", "typo").notificationSender())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notification.type");
    }

    @Test
    void rejectsUnimplementedRealPaymentMode() {
        assertThatThrownBy(() -> new AppConfig("live", "stripe", "mock", "email").paymentGateway())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no real provider");
    }

    @Test
    void rejectsUnimplementedRealNotificationMode() {
        assertThatThrownBy(() -> new AppConfig("mock", "stripe", "live", "email").notificationSender())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no real provider");
    }
}
