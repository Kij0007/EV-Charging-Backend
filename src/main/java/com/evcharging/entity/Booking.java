package com.evcharging.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;

import jakarta.persistence.*;

@Entity
@Table(name = "Booking")
public class Booking {
	
	public Booking() {
		// TODO Auto-generated constructor stub
	}
	
	public Booking(User user, ChargingStation station) {
		
		this.user = user;
		this.station = station;
	}
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@ManyToOne
	@JoinColumn(name = "user_Id", nullable = false)
	@JsonBackReference
	private  User user;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "station_Id", nullable = false)
	
	private ChargingStation station ;
	private String bookingStatus;
	private double amount;
	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public ChargingStation getStation() {
		return station;
	}

	public void setStation(ChargingStation station) {
		this.station = station;
	}

	public String getBookingStatus() {
		return bookingStatus;
	}

	public void setBookingStatus(String bookingStatus) {
		this.bookingStatus = bookingStatus;
	}

	public double getAmount() {
		return amount;
	}

	public void setAmount(double amount) {
		this.amount = amount;
	}
	
	
	
	
	

}
