package com.evcharging.repository;

import com.evcharging.entity.Booking;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Override
    @EntityGraph(attributePaths = {"user", "station"})
    List<Booking> findAll();

    @EntityGraph(attributePaths = {"user", "station"})
    List<Booking> findByUserId(Long userId);

    @EntityGraph(attributePaths = {"user", "station"})
    List<Booking> findByStationId(Long stationId);

    Optional<Booking> findByRequestKey(String requestKey);
    boolean existsByUserId(Long userId);
    boolean existsByStationId(Long stationId);

    @Query("select b.station.id from Booking b where b.id = :id")
    Optional<Long> findStationIdById(@Param("id") Long id);
}
