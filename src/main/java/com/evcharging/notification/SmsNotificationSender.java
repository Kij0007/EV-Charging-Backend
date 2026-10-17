package com.evcharging.notification;

public class SmsNotificationSender implements NotificationSender {

	@Override
	public void send(String message) {
		
		System.out.println("Sms sent : " + message);
		
	}
}
