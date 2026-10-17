package com.evcharging.config;

import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.evcharging.notification.EmailNotificationSender;
import com.evcharging.notification.NotificationSender;
import com.evcharging.notification.SmsNotificationSender;
import com.evcharging.payment.PaymentGateway;
import com.evcharging.payment.RazorpayGateway;
import com.evcharging.payment.StripeGateway;

@Configuration
public class AppConfig {
	
	@Value("${payment.gateway}")
	private String paymentGateway;
	
	@Bean
	public PaymentGateway paymentGateway()
	{
		if(paymentGateway.equals("razorpay"))
		{
			return new RazorpayGateway();
			
		}
		
		return new StripeGateway();
	}
	
	
	@Value("${notification.type}")
	private String notificationType;
	
	@Bean
	public NotificationSender NotificationSender()
	{
		if(notificationType.equals("email"))
		{
			return new EmailNotificationSender();
		}
		
		return new SmsNotificationSender();
		
	}
	
	

}
