package com.evcharging.dto;

public class BookingResponseDTO
{
	private Long bookingId;
	private Long userId;
	private Long stationId;
	private String bookingStatus;
	private double amount;
	private String stationName;
	private String location;
	private java.time.Instant createdAt;

    public BookingResponseDTO(Long bookingId, Long userId, Long stationId, String bookingStatus, double amount,
                              String stationName, String location, java.time.Instant createdAt) {
        this(bookingId, userId, stationId, bookingStatus, amount);
        this.stationName = stationName;
        this.location = location;
        this.createdAt = createdAt;
    }
    public String getStationName() { return stationName; }
    public String getLocation() { return location; }
    public java.time.Instant getCreatedAt() { return createdAt; }
	
	public BookingResponseDTO() {
		// TODO Auto-generated constructor stub
	}
	
	public BookingResponseDTO(Long bookingId, Long userId, Long stationId, String bookingStatus, double amount) {
		super();
		this.bookingId = bookingId;
		this.userId = userId;
		this.stationId = stationId;
		this.bookingStatus = bookingStatus;
		this.amount = amount;
	}
	public Long getBookingId() {
		return bookingId;
	}
	public void setBookingId(Long bookingId) {
		this.bookingId = bookingId;
	}
	public Long getUserId() {
		return userId;
	}
	public void setUserId(Long userId) {
		this.userId = userId;
	}
	public Long getStationId() {
		return stationId;
	}
	public void setStationId(Long stationId) {
		this.stationId = stationId;
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
