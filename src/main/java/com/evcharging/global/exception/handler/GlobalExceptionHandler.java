package com.evcharging.global.exception.handler;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import com.evcharging.exception.BookingNotFoundException;
import com.evcharging.exception.UserNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler
{
	@ExceptionHandler(BookingNotFoundException.class)
	
	public ResponseEntity<String> handleNotFound(BookingNotFoundException ex)
	{
		return new ResponseEntity<>(
				ex.getMessage(), HttpStatus.NOT_FOUND
				);
	}
	
	@ExceptionHandler(UserNotFoundException.class)
	public ResponseEntity<String>  handleUserNotFound(UserNotFoundException ex)
	{
		
		return new ResponseEntity<>
		(ex.getMessage(), HttpStatus.NOT_FOUND);
	}
	
	
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException ex)
	{
		Map<String, String> errors = new HashMap<>();
		
		for(FieldError err : ex.getBindingResult().getFieldErrors())
		{
			errors.put(err.getField(), err.getDefaultMessage());
		}
		
		return new ResponseEntity<>(errors,HttpStatus.BAD_REQUEST);
	}
	
	
}