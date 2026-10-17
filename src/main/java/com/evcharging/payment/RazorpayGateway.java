package com.evcharging.payment;

public class RazorpayGateway implements PaymentGateway{
	
	@Override
	public boolean pay(double amount) {
		System.out.println("Payment is done using rozarpay");
		return true;
	}
	
	
	

}
