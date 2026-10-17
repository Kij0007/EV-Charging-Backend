package com.evcharging.service.impl;

import java.util.*;

import org.springframework.stereotype.Service;

import com.evcharging.entity.User;
import com.evcharging.exception.UserNotFoundException;
import com.evcharging.repository.UserRepository;
import com.evcharging.service.UserService;

@Service
public class UserServiceImpl implements UserService{ 
	
	
	
	public UserServiceImpl(UserRepository userRepository) {
		super();
		this.userRepository = userRepository;
	}

	private final UserRepository userRepository;
	
	
	
	public void registerUser(User user)
	{
		userRepository.save(user);
		System.out.println("Registration completed succesfully");
	}
	public User getUserById(Long id)
	{
		Optional<User> optionalUser = userRepository.findById(id);
		
		if(optionalUser.isPresent())
		{
			return optionalUser.get();
		}
		
		throw new UserNotFoundException("User not found with ID : " + id);
	}
	
	public List<User> getAllUsers()
	{
		
		return userRepository.findAll();
	}
	
	
	
	
	
	
	
	
	
	

}