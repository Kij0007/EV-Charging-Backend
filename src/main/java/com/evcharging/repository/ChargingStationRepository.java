package com.evcharging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.evcharging.entity.ChargingStation;

public interface ChargingStationRepository extends JpaRepository<ChargingStation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChargingStation s where s.id = :id")
    Optional<ChargingStation> findByIdForUpdate(@Param("id") Long id);

    List<ChargingStation> findByStationNameContainingIgnoreCaseOrLocationContainingIgnoreCase(
            String stationName, String location);
}
