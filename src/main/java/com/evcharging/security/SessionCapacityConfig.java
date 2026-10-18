package com.evcharging.security;

import org.apache.catalina.Session;
import org.apache.catalina.session.StandardManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionCapacityConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedSessions(
            @Value("${app.security.max-active-sessions:1000}") int maximum) {
        if (maximum <= 0) { throw new IllegalArgumentException("Maximum active sessions must be positive"); }
        return factory -> factory.addContextCustomizers(context -> {
            BoundedSessionManager manager = new BoundedSessionManager();
            manager.setMaxActiveSessions(maximum);
            manager.setPathname(null); // Keep the existing in-memory, nonpersistent session model.
            context.setManager(manager);
        });
    }

    static class BoundedSessionManager extends StandardManager {
        // Tomcat's default check followed by insert is not atomic across concurrent creates.
        @Override
        public synchronized Session createSession(String requestedId) {
            return super.createSession(requestedId);
        }

        // Login rotation temporarily removes then re-adds a map entry. Keep that
        // whole remapping under the same monitor as admission, so it is never
        // mistaken for a free place by a concurrent new-session request.
        @Override
        protected synchronized void changeSessionId(Session session, String newId,
                boolean notifySessionListeners, boolean notifyContainerListeners) {
            super.changeSessionId(session, newId, notifySessionListeners, notifyContainerListeners);
        }
    }
}
