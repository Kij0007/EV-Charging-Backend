package com.evcharging.payment;

public class StripeGateway implements PaymentGateway{
	
	@Override
	public boolean pay(double amount) {
		System.out.println("Payment is done using stripe");
		return true;
	}

}
