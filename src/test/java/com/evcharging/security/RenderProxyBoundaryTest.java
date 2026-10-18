package com.evcharging.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;

/** Exercises the actual Tomcat valve using the checked-in Render profile, not a custom parser. */
class RenderProxyBoundaryTest {
    @Test
    void trustedPrivateProxySuppliesClientIdentityAndHttps() throws Exception {
        Observed first = throughValve("10.2.3.4", "198.51.100.11", "https");
        Observed second = throughValve("10.2.3.4", "198.51.100.12", "https");
        assertThat(first.address()).isEqualTo("198.51.100.11");
        assertThat(second.address()).isEqualTo("198.51.100.12");
        assertThat(first.secure()).isTrue();
        assertThat(first.scheme()).isEqualTo("https");
    }

    @Test
    void untrustedPublicPeerCannotSpoofClientOrSecureTransport() throws Exception {
        Observed result = throughValve("198.51.100.99", "203.0.113.7", "https");
        assertThat(result.address()).isEqualTo("198.51.100.99");
        assertThat(result.secure()).isFalse();
        assertThat(result.scheme()).isEqualTo("http");
    }

    @Test
    void traversalStopsAtRightmostUntrustedHopInsteadOfAcceptingLeftmostSpoof() throws Exception {
        Observed result = throughValve("10.2.3.4", "203.0.113.7, 198.51.100.44, 10.9.8.7", "https");
        assertThat(result.address()).isEqualTo("198.51.100.44");
    }

    @Test
    void baseConfigurationDoesNotEnableForwardedHeaders() throws Exception {
        Properties base = properties("/application.properties");
        assertThat(base.getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    private Observed throughValve(String peer, String forwarded, String protocol) throws Exception {
        Properties render = properties("/application-render.properties");
        assertThat(render.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(render.getProperty("server.tomcat.remoteip.internal-proxies"));
        valve.setTrustedProxies(render.getProperty("server.tomcat.remoteip.trusted-proxies"));
        valve.setRemoteIpHeader(render.getProperty("server.tomcat.remoteip.remote-ip-header"));
        valve.setProtocolHeader(render.getProperty("server.tomcat.remoteip.protocol-header"));
        valve.setHostHeader(render.getProperty("server.tomcat.remoteip.host-header"));
        valve.setPortHeader(render.getProperty("server.tomcat.remoteip.port-header"));
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.scheme().setString("http");
        coyote.serverName().setString("example.test");
        coyote.protocol().setString("HTTP/1.1");
        coyote.getMimeHeaders().addValue("X-Forwarded-For").setString(forwarded);
        coyote.getMimeHeaders().addValue("X-Forwarded-Proto").setString(protocol);
        Request request = new Request(new Connector(), coyote);
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        request.setServerPort(8081);
        request.setSecure(false);
        AtomicReference<Observed> observed = new AtomicReference<>();
        valve.setNext(new ValveBase() {
            @Override public void invoke(Request current, Response response) {
                observed.set(new Observed(current.getRemoteAddr(), current.isSecure(), current.getScheme()));
            }
        });
        valve.invoke(request, new Response(new org.apache.coyote.Response()));
        return observed.get();
    }

    private static Properties properties(String resource) throws Exception {
        Properties properties = new Properties();
        try (var stream = RenderProxyBoundaryTest.class.getResourceAsStream(resource)) {
            if (stream == null) { throw new AssertionError("Missing configuration " + resource); }
            properties.load(stream);
        }
        return properties;
    }

    private record Observed(String address, boolean secure, String scheme) { }
}
