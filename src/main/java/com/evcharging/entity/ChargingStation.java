package com.evcharging.entity;

import org.hibernate.annotations.Check;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Charging_Stations")
@Check(constraints = "total_slots > 0 AND avaialable_slots >= 0 AND avaialable_slots <= total_slots")
public class ChargingStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String stationName;

    @Column(nullable = false, length = 255)
    private String location;

    @Column(name = "total_slots", nullable = false)
    private int totalSlots;

    // Preserve the existing API and column spelling for compatibility.
    @Column(name = "avaialable_slots", nullable = false)
    private int avaialableSlots;

    public ChargingStation() {
    }

    public Long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public int getTotalSlots() {
        return totalSlots;
    }

    public void setTotalSlots(int totalSlots) {
        this.totalSlots = totalSlots;
    }

    public int getAvaialableSlots() {
        return avaialableSlots;
    }

    public void setAvaialableSlots(int avaialableSlots) {
        this.avaialableSlots = avaialableSlots;
    }
}
