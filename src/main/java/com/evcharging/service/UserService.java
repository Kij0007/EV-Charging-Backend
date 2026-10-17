package com.evcharging.service;

import java.util.List;

import com.evcharging.entity.User;

public interface UserService {
	
	public void registerUser(User user);
	
	public User getUserById(Long id);
	
	List<User> getAllUsers();

}
