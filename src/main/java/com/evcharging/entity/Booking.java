package com.evcharging.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "Booking", indexes = {
        @Index(name = "idx_booking_user", columnList = "user_Id"),
        @Index(name = "idx_booking_station", columnList = "station_Id")
})
@Check(constraints = "amount >= 0 and booking_status in ('Confirmed', 'Cancelled', 'Completed')")
public class Booking {

    public static final String CONFIRMED = "Confirmed";
    public static final String CANCELLED = "Cancelled";
    public static final String COMPLETED = "Completed";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Instant getCreatedAt() { return createdAt; }
	
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
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_Id", nullable = false)
	private  User user;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "station_Id", nullable = false)
	
	private ChargingStation station ;
	@Column(nullable = false)
	private String bookingStatus;
	@Column(nullable = false)
	private double amount;
	@Column(name = "request_key", length = 64, unique = true)
	private String requestKey;

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }
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
