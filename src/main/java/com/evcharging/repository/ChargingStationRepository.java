package com.evcharging.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.evcharging.entity.ChargingStation;

public interface ChargingStationRepository extends JpaRepository<ChargingStation, Long>{

}
