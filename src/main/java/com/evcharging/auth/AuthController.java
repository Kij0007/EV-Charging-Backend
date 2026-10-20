package com.evcharging.auth;

import java.util.Map;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.evcharging.security.CurrentUser;
import com.evcharging.security.UserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    private final AuthenticationManager authenticator;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contexts;
    private final CurrentUser currentUser;
    private final AuthenticationAttemptLimiter limiter;

    public AuthController(AuthService service, AuthenticationManager authenticator,
            SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contexts,
            CurrentUser currentUser, AuthenticationAttemptLimiter limiter) {
        this.service = service;
        this.authenticator = authenticator;
        this.sessionStrategy = sessionStrategy;
        this.contexts = contexts;
        this.currentUser = currentUser;
        this.limiter = limiter;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUserDTO register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        limiter.check("register", request.getRemoteAddr());
        return service.register(body);
    }

    @PostMapping("/login")
    public AuthUserDTO login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        limiter.check("login", request.getRemoteAddr());
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Email or password is incorrect");
        }
        Authentication authentication = authenticator.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.email().strip(), body.password()));
        sessionStrategy.onAuthentication(authentication, request, response);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        principal.erasePassword();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return service.me(principal.getUserId());
    }

    @GetMapping("/me")
    public AuthUserDTO me() { return service.me(currentUser.id()); }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        limiter.check("password", request.getRemoteAddr());
        service.changePassword(currentUser.id(), body);
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
    }
}
