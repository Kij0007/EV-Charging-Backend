package com.evcharging.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.evcharging.dto.UserRequestDTO;
import com.evcharging.entity.User;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.UserNotFoundException;
import com.evcharging.global.exception.handler.GlobalExceptionHandler;
import com.evcharging.service.UserService;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {
    @Mock private UserService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .build();
    }

    @Test
    void registrationRetainsExistingSuccessContractAndNormalizesRequest() throws Exception {
        mvc.perform(post("/users/reg").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" Ada \",\"email\":\" ADA@example.com \"}"))
                .andExpect(status().isOk()).andExpect(content().string("User Registered Successfully"));
        ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
        verify(service).registerUser(captured.capture());
        assertThat(captured.getValue().getEmail()).isEqualTo("ada@example.com");
        assertThat(captured.getValue().getId()).isNull();
    }

    @Test
    void invalidRegistrationReturnsFieldErrorsWithoutCallingService() throws Exception {
        mvc.perform(post("/users/reg").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/users/reg"))
                .andExpect(jsonPath("$.fieldErrors", hasKey("name")))
                .andExpect(jsonPath("$.fieldErrors", hasKey("email")));
        verifyNoInteractions(service);
    }

    @Test
    void requestCannotSupplyPersistenceIdentity() throws Exception {
        mvc.perform(post("/users/reg").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":2,\"name\":\"Ada\",\"email\":\"ada@example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        verifyNoInteractions(service);
    }

    @Test
    void userReadReturnsBoundedDtoWithoutBookingGraph() throws Exception {
        when(service.getUserById(2L)).thenReturn(user());
        mvc.perform(get("/users/2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2)).andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.bookings").doesNotExist());
    }

    @Test
    void userListUsesSameResponseContract() throws Exception {
        when(service.getAllUsers()).thenReturn(List.of(user()));
        mvc.perform(get("/users/all")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ada"))
                .andExpect(jsonPath("$[0].bookings").doesNotExist());
    }

    @Test
    void missingUserReturnsConsistent404() throws Exception {
        when(service.getUserById(2L)).thenThrow(new UserNotFoundException("User not found"));
        mvc.perform(get("/users/2")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void updateReturnsUpdatedDto() throws Exception {
        UserRequestDTO request = new UserRequestDTO("Ada", "ada@example.com");
        when(service.updateUser(2L, request)).thenReturn(user());
        mvc.perform(put("/users/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\",\"email\":\"ada@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(2));
    }

    @Test
    void deletionReturns204() throws Exception {
        mvc.perform(delete("/users/2")).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).deleteUser(2L);
    }

    @Test
    void deletionWithHistoryReturns409() throws Exception {
        doThrow(new DomainConflictException("User has booking history and cannot be deleted"))
                .when(service).deleteUser(2L);
        mvc.perform(delete("/users/2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_CONFLICT"));
    }

    private static User user() {
        User user = new User();
        user.setId(2L);
        user.setName("Ada");
        user.setEmail("ada@example.com");
        return user;
    }
}
