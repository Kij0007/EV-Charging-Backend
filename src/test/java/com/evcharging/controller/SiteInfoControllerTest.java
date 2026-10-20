package com.evcharging.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

class SiteInfoControllerTest {
    private MockMvc controller(boolean demo) {
        return MockMvcBuilders.standaloneSetup(new SiteInfoController(demo))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .build();
    }

    @Test
    void normalModeHasNoDemoNoticeAndOnlyTheTwoPublicFields() throws Exception {
        controller(false).perform(get("/api/site-info"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.demo").value(false))
                .andExpect(jsonPath("$.notice").value(""))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void publicDemoDescribesRealLimitationsWithoutExposingConfiguration() throws Exception {
        controller(true).perform(get("/api/site-info"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.notice", containsString("use test details only")))
                .andExpect(jsonPath("$.notice", containsString("No online charges")))
                .andExpect(jsonPath("$.notice", containsString("no physical chargers")))
                .andExpect(jsonPath("$.notice", containsString("may reset")))
                .andExpect(jsonPath("$.notice", containsString("database may expire")))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
