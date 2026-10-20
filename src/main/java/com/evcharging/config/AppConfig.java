package com.evcharging.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.evcharging.notification.EmailNotificationSender;
import com.evcharging.notification.NotificationSender;
import com.evcharging.notification.SmsNotificationSender;
import com.evcharging.payment.PaymentGateway;
import com.evcharging.payment.RazorpayGateway;
import com.evcharging.payment.StripeGateway;

@Configuration
@org.springframework.context.annotation.Profile("integration-demo")
public class AppConfig {
    private final String paymentMode;
    private final String paymentGateway;
    private final String notificationMode;
    private final String notificationType;

    public AppConfig(@Value("${payment.mode:mock}") String paymentMode,
                     @Value("${payment.gateway:stripe}") String paymentGateway,
                     @Value("${notification.mode:mock}") String notificationMode,
                     @Value("${notification.type:email}") String notificationType) {
        this.paymentMode = normalize(paymentMode);
        this.paymentGateway = normalize(paymentGateway);
        this.notificationMode = normalize(notificationMode);
        this.notificationType = normalize(notificationType);
    }

    @Bean
    public PaymentGateway paymentGateway() {
        requireMockMode(paymentMode, "payment.mode");
        return switch (paymentGateway) {
            case "razorpay" -> new RazorpayGateway();
            case "stripe" -> new StripeGateway();
            default -> throw new IllegalArgumentException("payment.gateway must be stripe or razorpay");
        };
    }

    @Bean
    public NotificationSender notificationSender() {
        requireMockMode(notificationMode, "notification.mode");
        return switch (notificationType) {
            case "email" -> new EmailNotificationSender();
            case "sms" -> new SmsNotificationSender();
            default -> throw new IllegalArgumentException("notification.type must be email or sms");
        };
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireMockMode(String mode, String property) {
        if (!"mock".equals(mode)) {
            throw new IllegalArgumentException(property + " must be mock; no real provider is implemented");
        }
    }
}
