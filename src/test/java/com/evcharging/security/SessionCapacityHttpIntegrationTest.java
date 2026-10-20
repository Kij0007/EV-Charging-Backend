package com.evcharging.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.catalina.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.evcharging.PostgresTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.max-active-sessions=2",
        "app.security.session-admission.max-per-window=30",
        "spring.datasource.url=jdbc:h2:mem:session-http-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionCapacityHttpIntegrationTest extends PostgresTestSupport {
    @LocalServerPort private int port;
    @Autowired private ServletWebServerApplicationContext application;

    @Test
    void realTomcatReturnsSafeCapacityErrorButExistingSessionsAndReleasedCapacityWork() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> first = csrf(client, null);
        assertThat(first.statusCode()).isEqualTo(200);
        String cookie = first.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertThat(csrf(client, null).statusCode()).isEqualTo(200);
        HttpResponse<String> full = csrf(client, null);
        assertThat(full.statusCode()).isEqualTo(503);
        assertThat(full.headers().firstValue("Retry-After")).contains("60");
        assertThat(full.body()).contains("SESSION_CAPACITY_REACHED").doesNotContain("Exception", "stackTrace");
        assertThat(full.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(csrf(client, cookie).statusCode()).isEqualTo(200);

        TomcatWebServer server = (TomcatWebServer) application.getWebServer();
        Context context = (Context) server.getTomcat().getHost().findChildren()[0];
        context.getManager().findSession(cookie.substring(cookie.indexOf('=') + 1)).expire();
        assertThat(csrf(client, null).statusCode()).isEqualTo(200);
        assertThat(context.getManager().getActiveSessions()).isEqualTo(2);
    }

    private HttpResponse<String> csrf(HttpClient client, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (cookie != null) { request.header("Cookie", cookie); }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
