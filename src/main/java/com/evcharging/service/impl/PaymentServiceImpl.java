package com.evcharging.service.impl;

import org.springframework.stereotype.Service;

import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.PaymentFailedException;
import com.evcharging.payment.PaymentGateway;
import com.evcharging.service.PaymentService;

@Service
@org.springframework.context.annotation.Profile("integration-demo")
public class PaymentServiceImpl implements PaymentService {
    private final PaymentGateway gateway;

    public PaymentServiceImpl(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean processPayment(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            throw new BadRequestException("Payment amount must be finite and greater than zero");
        }
        try {
            return gateway.pay(amount);
        } catch (RuntimeException exception) {
            // Provider exception messages may contain credentials or payment details.
            throw new PaymentFailedException("Mock payment processing failed");
        }
    }
}
