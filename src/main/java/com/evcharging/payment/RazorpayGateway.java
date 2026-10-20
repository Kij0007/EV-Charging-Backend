package com.evcharging.payment;

/** Development mock only. No Razorpay API request or money movement occurs. */
public class RazorpayGateway implements PaymentGateway {
    @Override
    public boolean pay(double amount) {
        return true;
    }
}
