package com.evcharging.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.evcharging.entity.Booking;

public interface BookingRepository extends JpaRepository<Booking,Long>{

}
