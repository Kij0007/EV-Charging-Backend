package com.evcharging.service.impl;

import org.springframework.stereotype.Service;

import com.evcharging.payment.PaymentGateway;
import com.evcharging.service.PaymentService;


@Service
public class PaymentServiceImpl implements PaymentService {

	private final PaymentGateway gateway;
	public PaymentServiceImpl(PaymentGateway gateway) {
		this.gateway = gateway;
		
		
	}
	
	@Override
	public boolean processPayment(double amount) {
		
		System.out.println("Processing payment for rs:"+amount);
		
		return gateway.pay(amount);
		
		
	}
}
