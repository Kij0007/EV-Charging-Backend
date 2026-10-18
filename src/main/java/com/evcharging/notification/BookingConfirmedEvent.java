package com.evcharging.notification;

/** A local, in-memory event; it is not a durable delivery or payment record. */
public record BookingConfirmedEvent(long bookingId) {
}
