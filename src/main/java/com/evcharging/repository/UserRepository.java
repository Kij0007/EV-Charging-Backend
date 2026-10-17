package com.evcharging.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.evcharging.entity.User;

public interface UserRepository extends JpaRepository<User, Long>{}