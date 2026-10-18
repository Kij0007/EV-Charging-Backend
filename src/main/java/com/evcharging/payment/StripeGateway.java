package com.evcharging.payment;

/** Development mock only. No Stripe API request or money movement occurs. */
public class StripeGateway implements PaymentGateway {
    @Override
    public boolean pay(double amount) {
        return true;
    }
}
