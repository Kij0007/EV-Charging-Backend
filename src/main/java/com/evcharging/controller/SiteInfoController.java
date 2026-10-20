package com.evcharging.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes only intentional visitor information, never deployment configuration or credentials. */
@RestController
public class SiteInfoController {
    private static final String DEMO_NOTICE = "Public demo: use test details only. "
            + "No online charges are collected and no physical chargers are activated. "
            + "Demo data may reset, and the free database may expire.";

    private final boolean demoMode;

    public SiteInfoController(@Value("${APP_DEMO_MODE:false}") boolean demoMode) {
        this.demoMode = demoMode;
    }

    @GetMapping("/api/site-info")
    public ResponseEntity<SiteInfo> siteInfo() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SiteInfo(demoMode, demoMode ? DEMO_NOTICE : ""));
    }

    public record SiteInfo(boolean demo, String notice) {}
}
