package com.evcharging.global.exception.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.PaymentFailedException;
import com.evcharging.exception.StationNotFoundException;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorTestController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void missingResourceHasConsistent404Body() throws Exception {
        mvc.perform(get("/errors/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Station not found"))
                .andExpect(jsonPath("$.path").value("/errors/not-found"))
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    void domainConflictUses409() throws Exception {
        mvc.perform(get("/errors/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_CONFLICT"));
    }

    @Test
    void badRequestUses400() throws Exception {
        mvc.perform(get("/errors/bad-request")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void paymentFailureUsesSanitized502() throws Exception {
        mvc.perform(get("/errors/payment")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Mock payment processing failed"));
    }

    @Test
    void databaseConflictDoesNotExposeSql() throws Exception {
        mvc.perform(get("/errors/database")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request conflicts with existing data or a database constraint"));
    }

    @Test
    void lockContentionProducesRetryableConflict() throws Exception {
        mvc.perform(get("/errors/lock")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalDetails() throws Exception {
        mvc.perform(get("/errors/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected server error occurred"));
    }

    @Test
    void malformedJsonUses400() throws Exception {
        mvc.perform(post("/errors/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void fieldValidationIncludesFieldErrors() throws Exception {
        mvc.perform(post("/errors/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void missingParameterUses400() throws Exception {
        mvc.perform(get("/errors/parameter")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors.id").value("Required"));
    }

    @Test
    void wrongParameterTypeUses400() throws Exception {
        mvc.perform(get("/errors/id/not-a-number")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void mvcMethodNotAllowedKeepsStatusAndAllowHeader() throws Exception {
        mvc.perform(post("/errors/parameter")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @RestController
    static class ErrorTestController {
        @GetMapping("/errors/{kind}")
        public void fail(@PathVariable String kind) {
            switch (kind) {
                case "not-found" -> throw new StationNotFoundException("Station not found");
                case "conflict" -> throw new DomainConflictException("No slots available");
                case "bad-request" -> throw new BadRequestException("Invalid request");
                case "payment" -> throw new PaymentFailedException("private-provider-data");
                case "database" -> throw new DataIntegrityViolationException("private-sql");
                case "lock" -> throw new CannotAcquireLockException("private-sql");
                default -> throw new IllegalStateException("private-details");
            }
        }

        @PostMapping("/errors/validate")
        public void validate(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/errors/parameter")
        public void parameter(@RequestParam Long id) {
        }

        @GetMapping("/errors/id/{id}")
        public void id(@PathVariable Long id) {
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
