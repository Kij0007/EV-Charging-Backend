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

/** Uses the actual Tomcat valve and PC-tunnel profile; no live tunnel is required. */
class TunnelProxyBoundaryTest {
    @Test
    void loopbackAgentSuppliesSeparateClientAddressesAndHttps() throws Exception {
        Observed first = throughValve("127.0.0.1", "198.51.100.11", "https");
        Observed second = throughValve("127.0.0.1", "198.51.100.12", "https");
        assertThat(first.address()).isEqualTo("198.51.100.11");
        assertThat(second.address()).isEqualTo("198.51.100.12");
        assertThat(first.secure()).isTrue();
        assertThat(first.scheme()).isEqualTo("https");
    }

    @Test
    void ipv6LoopbackAgentCanSupplyAnIpv6Visitor() throws Exception {
        Observed result = throughValve("::1", "2001:db8::25", "https");
        assertThat(result.address()).isEqualTo("2001:db8::25");
        assertThat(result.secure()).isTrue();
    }

    @Test
    void lanAndPublicPeersCannotForgeClientIdentityOrHttps() throws Exception {
        for (String peer : new String[] {"10.2.3.4", "172.16.2.3", "192.168.1.9", "198.51.100.99"}) {
            Observed result = throughValve(peer, "203.0.113.7", "https");
            assertThat(result.address()).isEqualTo(peer);
            assertThat(result.secure()).isFalse();
            assertThat(result.scheme()).isEqualTo("http");
        }
    }

    @Test
    void appendedActualVisitorWinsOverForgedLeftmostPrefix() throws Exception {
        Observed result = throughValve("127.0.0.1", "203.0.113.7, 198.51.100.44", "https");
        assertThat(result.address()).isEqualTo("198.51.100.44");
    }

    @Test
    void privateForwardedAddressStopsTraversalInsteadOfTrustingTheLan() throws Exception {
        Observed result = throughValve("127.0.0.1", "203.0.113.7, 10.9.8.7", "https");
        assertThat(result.address()).isEqualTo("10.9.8.7");
    }

    @Test
    void forwardedHttpCannotBeUpgradedByAnEarlierHttpsToken() throws Exception {
        Observed plain = throughValve("127.0.0.1", "198.51.100.44", "http");
        Observed spoof = throughValve("127.0.0.1", "198.51.100.44", "https, http");
        assertThat(plain.secure()).isFalse();
        assertThat(spoof.secure()).isFalse();
        assertThat(spoof.scheme()).isEqualTo("http");
    }

    @Test
    void mixedProtocolChainIsConservativeAndAllHttpsChainIsSecure() throws Exception {
        assertThat(throughValve("127.0.0.1", "198.51.100.44", "http, https").secure()).isFalse();
        assertThat(throughValve("127.0.0.1", "198.51.100.44", "https, https").secure()).isTrue();
    }

    @Test
    void tunnelProfileKeepsSecureCookiesAndDoesNotChangeDatabaseOrNormalLocalMode() throws Exception {
        Properties tunnel = properties("/application-tunnel.properties");
        assertThat(tunnel.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(tunnel.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(tunnel.getProperty("server.tomcat.remoteip.internal-proxies"))
                .isEqualTo("127.0.0.1/32,::1/128");
        assertThat(tunnel.getProperty("server.tomcat.remoteip.trusted-proxies")).isEmpty();
        assertThat(tunnel.getProperty("server.tomcat.remoteip.host-header")).isEmpty();
        assertThat(tunnel.getProperty("server.tomcat.remoteip.port-header")).isEmpty();
        assertThat(tunnel.stringPropertyNames()).noneMatch(name -> name.startsWith("spring.datasource")
                || name.startsWith("spring.flyway") || name.startsWith("app.admin") || name.equals("APP_DEMO_MODE"));
        assertThat(properties("/application.properties").getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(properties("/application-local.properties").getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("false");
        assertThat(properties("/application-prod.properties").getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("true");
    }

    private Observed throughValve(String peer, String forwarded, String protocol) throws Exception {
        Properties tunnel = properties("/application-tunnel.properties");
        assertThat(tunnel.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(tunnel.getProperty("server.tomcat.remoteip.internal-proxies"));
        valve.setTrustedProxies(tunnel.getProperty("server.tomcat.remoteip.trusted-proxies"));
        valve.setRemoteIpHeader(tunnel.getProperty("server.tomcat.remoteip.remote-ip-header"));
        valve.setProtocolHeader(tunnel.getProperty("server.tomcat.remoteip.protocol-header"));
        valve.setHostHeader(tunnel.getProperty("server.tomcat.remoteip.host-header"));
        valve.setPortHeader(tunnel.getProperty("server.tomcat.remoteip.port-header"));
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
        try (var stream = TunnelProxyBoundaryTest.class.getResourceAsStream(resource)) {
            if (stream == null) { throw new AssertionError("Missing configuration " + resource); }
            properties.load(stream);
        }
        return properties;
    }

    private record Observed(String address, boolean secure, String scheme) { }
}
