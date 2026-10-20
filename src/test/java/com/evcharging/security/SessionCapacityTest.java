package com.evcharging.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.Session;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.TooManyActiveSessionsException;
import org.apache.catalina.util.StandardSessionIdGenerator;
import org.junit.jupiter.api.Test;

class SessionCapacityTest {
    @Test
    void loginSessionIdRotationCannotTemporarilyOpenAnExtraAdmissionPlace() throws Exception {
        var removed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pause = new AtomicBoolean();
        var manager = new SessionCapacityConfig.BoundedSessionManager() {
            @Override public void remove(Session session) {
                super.remove(session);
                if (pause.compareAndSet(true, false)) {
                    removed.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Rotation gate timed out"); }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
            }
        };
        StandardContext context = new StandardContext();
        context.setSessionTimeout(30);
        manager.setContext(context);
        manager.setMaxActiveSessions(1);
        manager.setSessionIdGenerator(new StandardSessionIdGenerator());
        Session current = manager.createSession("original");
        pause.set(true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> rotation = pool.submit(() -> manager.rotateSessionId(current));
            assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Session> admission = pool.submit(() -> manager.createSession("extra"));
            assertThatThrownBy(() -> admission.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            String rotatedId = rotation.get(10, TimeUnit.SECONDS);
            assertThat(rotatedId).isNotEqualTo("original");
            assertThatThrownBy(() -> admission.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TooManyActiveSessionsException.class);
            assertThat(manager.getActiveSessions()).isEqualTo(1);
            assertThat(manager.findSession(rotatedId)).isSameAs(current);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentCreationHonorsHardCapAndExpiredSessionReleasesCapacity() throws Exception {
        StandardContext context = new StandardContext();
        context.setSessionTimeout(30);
        SessionCapacityConfig.BoundedSessionManager manager = new SessionCapacityConfig.BoundedSessionManager();
        manager.setContext(context);
        manager.setMaxActiveSessions(2);
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String id = "test-" + i;
                attempts.add(pool.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Start gate timed out"); }
                    try { manager.createSession(id); return true; }
                    catch (TooManyActiveSessionsException capacity) { return false; }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(10, TimeUnit.SECONDS)) { accepted++; }
            }
            assertThat(accepted).isEqualTo(2);
            assertThat(manager.getActiveSessions()).isEqualTo(2);
            manager.findSessions()[0].expire();
            manager.createSession("replacement");
            assertThat(manager.getActiveSessions()).isEqualTo(2);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
