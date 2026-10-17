package com.evcharging.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.evcharging.entity.User;
import com.evcharging.service.UserService;

@RestController
@RequestMapping("/users")
public class UserController{
	
	//Dependency
	private final UserService userService;
	
	//constructor injection
	public UserController(UserService userService) {
		
		this.userService = userService;
	}
	
	@PostMapping("/reg")
	public String reisterUser(@RequestBody User user)
	{
		userService.registerUser(user);
		
		return "User Registered Successfully";
	}
	
	@GetMapping("/{id}")
	public User getUserById(@PathVariable Long id)
	{
		return userService.getUserById(id);
	}
	
	@GetMapping("/all")
	public List<User> getAllUsers()
	{
		return userService.getAllUsers();
	}
	
	
	
	
	
}