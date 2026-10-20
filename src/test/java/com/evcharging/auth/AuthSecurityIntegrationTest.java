package com.evcharging.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.evcharging.entity.User;
import com.evcharging.PostgresTestSupport;
import com.evcharging.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest extends PostgresTestSupport {
    private static final String PASSWORD = "correct-horse-battery";
    private static final AtomicInteger CLIENT = new AtomicInteger();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;

    @Test
    void anonymousRequestsRequireAuthenticationAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/users/all")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Guest", "email", email(), "password", PASSWORD))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        MvcResult site = mvc.perform(get("/api/site-info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").isBoolean()).andReturn();
        assertThat(site.getRequest().getSession(false)).isNull();
    }

    @Test
    void registrationPersistsOnlyHashAndNeverAcceptsAClientSuppliedRole() throws Exception {
        String email = email();
        Client client = csrf(null);
        mvc.perform(client.request(post("/api/auth/register"))
                .content(json(Map.of("name", "  New Customer  ", "email", email.toUpperCase(), "password", PASSWORD))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.name").value("New Customer"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
        User saved = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        mvc.perform(client.request(post("/api/auth/register"))
                .content(json(Map.of("name", "Admin?", "email", email(), "password", PASSWORD, "role", "ADMIN"))))
                .andExpect(status().isBadRequest());
        mvc.perform(client.request(post("/api/auth/register"))
                .content(json(Map.of("name", "Duplicate", "email", email, "password", PASSWORD))))
                .andExpect(status().isConflict());
    }

    @Test
    void loginRotatesSessionAndCsrfAndLogoutEndsAccess() throws Exception {
        User user = createUser("USER", PASSWORD);
        Client before = csrf(null);
        String previousSessionId = before.session.getId();
        MvcResult login = mvc.perform(before.request(post("/api/auth/login"))
                .content(json(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId())).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(previousSessionId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(before.request(post("/api/auth/logout"))).andExpect(status().isForbidden());
        Client after = csrf(session);
        assertThat(after.token).isNotEqualTo(before.token);
        mvc.perform(after.request(post("/api/auth/logout"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCredentialsAndLegacyAccountsReturnGenericErrors() throws Exception {
        User user = createUser("USER", PASSWORD);
        Client client = csrf(null);
        mvc.perform(client.request(post("/api/auth/login"))
                .content(json(Map.of("email", user.getEmail(), "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
        User legacy = new User();
        legacy.setName("Legacy"); legacy.setEmail(email()); users.saveAndFlush(legacy);
        mvc.perform(client.request(post("/api/auth/login"))
                .content(json(Map.of("email", legacy.getEmail(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
    }

    @Test
    void customerCannotUseAdminRoutesAndUnknownPathsAreDenied() throws Exception {
        Client customer = login(createUser("USER", PASSWORD), PASSWORD);
        mvc.perform(get("/users/all").session(customer.session)).andExpect(status().isForbidden());
        mvc.perform(customer.request(post("/station/add")).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/never-public").session(customer.session)).andExpect(status().isForbidden());
        Client admin = login(createUser("ADMIN", PASSWORD), PASSWORD);
        mvc.perform(get("/users/all").session(admin.session)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeRevokesAllSessionsAndRequiresNewPassword() throws Exception {
        User user = createUser("USER", PASSWORD);
        Client first = login(user, PASSWORD);
        Client other = login(user, PASSWORD);
        String replacement = "new-correct-password";
        mvc.perform(first.request(put("/api/auth/password"))
                .content(json(Map.of("currentPassword", PASSWORD, "newPassword", replacement))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(other.session)).andExpect(status().isUnauthorized());
        Client fresh = login(user, replacement);
        mvc.perform(get("/api/auth/me").session(fresh.session)).andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getCredentialVersion()).isEqualTo(1);
    }

    @Test
    void invalidPasswordChangesDoNotChangeCredentialsOrSession() throws Exception {
        User user = createUser("USER", PASSWORD);
        Client client = login(user, PASSWORD);
        mvc.perform(client.request(put("/api/auth/password"))
                .content(json(Map.of("currentPassword", "incorrect", "newPassword", "new-valid-password"))))
                .andExpect(status().isBadRequest());
        mvc.perform(client.request(put("/api/auth/password"))
                .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/me").session(client.session)).andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getCredentialVersion()).isZero();
    }

    private User createUser(String role, String password) {
        User user = new User();
        user.setName("Security Test"); user.setEmail(email());
        user.setRole(role); user.setPasswordHash(encoder.encode(password));
        return users.saveAndFlush(user);
    }

    private Client login(User user, String password) throws Exception {
        Client client = csrf(null);
        MvcResult result = mvc.perform(client.request(post("/api/auth/login"))
                .content(json(Map.of("email", user.getEmail(), "password", password))))
                .andExpect(status().isOk()).andReturn();
        return csrf((MockHttpSession) result.getRequest().getSession(false));
    }

    private Client csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (session != null) { request.session(session); }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        return new Client((MockHttpSession) result.getRequest().getSession(false),
                json.get("token").asText(), json.get("headerName").asText(),
                "198.51.100." + CLIENT.incrementAndGet());
    }

    private String json(Object value) { return mapper.writeValueAsString(value); }
    private static String email() { return "security-" + UUID.randomUUID() + "@example.test"; }

    private record Client(MockHttpSession session, String token, String header, String address) {
        MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder request) {
            return request.session(session).header(header, token).contentType(MediaType.APPLICATION_JSON)
                    .with(servlet -> { servlet.setRemoteAddr(address); return servlet; });
        }
    }
}
