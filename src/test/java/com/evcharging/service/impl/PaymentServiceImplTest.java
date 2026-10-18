package com.evcharging.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.PaymentFailedException;
import com.evcharging.payment.PaymentGateway;
import com.evcharging.payment.RazorpayGateway;
import com.evcharging.payment.StripeGateway;

class PaymentServiceImplTest {
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(gateway);

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidAmountsWithoutCallingProvider(double amount) {
        assertThatThrownBy(() -> service.processPayment(amount)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void forwardsValidAmountAndSuccess() {
        when(gateway.pay(500)).thenReturn(true);
        assertThat(service.processPayment(500)).isTrue();
        verify(gateway).pay(500);
    }

    @Test
    void preservesProviderDeclineForBookingServiceToHandle() {
        when(gateway.pay(500)).thenReturn(false);
        assertThat(service.processPayment(500)).isFalse();
    }

    @Test
    void sanitizesProviderFailure() {
        when(gateway.pay(500)).thenThrow(new IllegalStateException("sensitive-provider-details"));
        assertThatThrownBy(() -> service.processPayment(500)).isInstanceOf(PaymentFailedException.class)
                .hasMessage("Mock payment processing failed").hasNoCause();
    }

    @Test
    void bothNamedAdaptersAreDeterministicDevelopmentMocks() {
        assertThat(new StripeGateway().pay(500)).isTrue();
        assertThat(new RazorpayGateway().pay(500)).isTrue();
    }
}
