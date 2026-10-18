package com.evcharging;

import com.evcharging.dto.BookingResponseDTO;
import com.evcharging.dto.StationUpdateRequestDTO;
import com.evcharging.entity.ChargingStation;
import com.evcharging.entity.User;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.repository.*;
import com.evcharging.service.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:booking-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class BookingIntegrationTest extends PostgresTestSupport {
    @Autowired BookingService bookings;
    @Autowired UserService users;
    @Autowired StationService stations;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    @Autowired ChargingStationRepository stationRepository;
    @Autowired UserNotificationRepository notifications;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoSpyBean InboxService inbox;
    private InboxService inboxSpy;
    private User user;
    private ChargingStation station;

    @BeforeEach void setup() {
        // Stub the spy behind Spring's transaction proxy. Calling MANDATORY methods on
        // the proxy during Mockito setup would require a transaction before stubbing ends.
        inboxSpy = AopTestUtils.getUltimateTargetObject(inbox);
        reset(inboxSpy);
        notifications.deleteAll(); bookingRepository.deleteAll(); userRepository.deleteAll(); stationRepository.deleteAll();
        user = newUser("learner@example.com");
        station = new ChargingStation(); station.setStationName("Central EV"); station.setLocation("Pune");
        station.setTotalSlots(1); station.setAvaialableSlots(1);
        station = stationRepository.saveAndFlush(station);
    }

    @Test void lifecycleReadsDtosFiltersAndCancelsOnlyOnce() {
        var created = bookings.createBooking(user.getId(), station.getId(), null);
        assertThat(created.getAmount()).isZero();
        assertThat(created.getStationName()).isEqualTo("Central EV");
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getBookingStatus()).isEqualTo("Confirmed");
        assertThat(available()).isZero();
        assertThat(bookings.getAllBookings()).hasSize(1);
        assertThat(bookings.getBookingsByUser(user.getId())).hasSize(1);
        assertThat(bookings.getBookingsByStation(station.getId())).hasSize(1);
        bookings.cancelBooking(created.getBookingId()); bookings.cancelBooking(created.getBookingId());
        assertThat(bookings.getBookingById(created.getBookingId()).getBookingStatus()).isEqualTo("Cancelled");
        assertThat(available()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test void completionReleasesCapacityOnceAndCannotBeCancelled() {
        var id = bookings.createBooking(user.getId(), station.getId(), null).getBookingId();
        bookings.completeBooking(id); bookings.completeBooking(id);
        assertThat(available()).isEqualTo(1);
        assertThat(bookings.getBookingById(id).getBookingStatus()).isEqualTo("Completed");
        assertThatThrownBy(() -> bookings.cancelBooking(id)).isInstanceOf(DomainConflictException.class);
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test void inboxFailureRollsBackBookingAndCapacity() {
        doThrow(new IllegalStateException("test storage failure")).when(inboxSpy).bookingNotice(any(), anyString());
        assertThatThrownBy(() -> bookings.createBooking(user.getId(), station.getId(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bookingRepository.count()).isZero();
        assertThat(notifications.count()).isZero();
        assertThat(available()).isEqualTo(1);
    }

    @Test void outerRollbackUndoesSavedBookingCounterAndInbox() {
        transaction.executeWithoutResult(status -> {
            bookings.createBooking(user.getId(), station.getId(), null); status.setRollbackOnly();
        });
        assertThat(bookingRepository.count()).isZero();
        assertThat(notifications.count()).isZero();
        assertThat(available()).isEqualTo(1);
    }

    @Test void inboxIsInvisibleToIndependentConnectionUntilCommit() {
        transaction.executeWithoutResult(status -> {
            bookings.createBooking(user.getId(), station.getId(), null);
            try (Connection connection=dataSource.getConnection(); var statement=connection.createStatement();
                 var result=statement.executeQuery("select count(*) from user_notifications")) {
                result.next(); assertThat(result.getInt(1)).isZero();
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        });
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test void concurrentLastSlotRequestsHaveExactlyOneWinner() throws Exception {
        Long other = newUser("other@example.com").getId();
        var results=concurrently(List.of(() -> createResult(user.getId()), () -> createResult(other)));
        assertThat(results).containsExactlyInAnyOrder("created","full");
        assertThat(bookingRepository.count()).isEqualTo(1); assertThat(available()).isZero();
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test void concurrentRequestsRespectMultipleSlotCapacity() throws Exception {
        station.setTotalSlots(2); station.setAvaialableSlots(2); stationRepository.saveAndFlush(station);
        List<Callable<String>> requests=new ArrayList<>();
        for(int i=0;i<6;i++) { Long id=newUser("parallel"+i+"@example.com").getId(); requests.add(() -> createResult(id)); }
        assertThat(concurrently(requests)).containsExactlyInAnyOrder("created","created","full","full","full","full");
        assertThat(bookingRepository.count()).isEqualTo(2); assertThat(available()).isZero();
    }

    @Test void secondTransactionWaitsWhileFirstOwnsStationLock() throws Exception {
        Long other=newUser("waiting@example.com").getId();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var secondStarted=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        doAnswer(invocation -> {
            if(calls.incrementAndGet()==1) {
                entered.countDown();
                if(!release.await(5,TimeUnit.SECONDS)) throw new TimeoutException("Gate timed out");
            }
            return invocation.callRealMethod();
        }).when(inboxSpy).bookingNotice(any(),anyString());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<String> first=pool.submit(() -> createResult(user.getId()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            Future<String> second=pool.submit(() -> { secondStarted.countDown(); return createResult(other); });
            assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo("created");
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo("full");
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void concurrentCancellationReleasesExactlyOneSlot() throws Exception {
        Long id=bookings.createBooking(user.getId(),station.getId(),null).getBookingId();
        assertThat(concurrently(List.of(() -> {bookings.cancelBooking(id); return "ok";},
                () -> {bookings.cancelBooking(id);return "ok";}))).containsOnly("ok").hasSize(2);
        assertThat(available()).isEqualTo(1); assertThat(notifications.count()).isEqualTo(2);
    }

    @Test void cancellationRacingCompletionReleasesOneSlot() throws Exception {
        Long id=bookings.createBooking(user.getId(),station.getId(),null).getBookingId();
        var results=concurrently(List.of(() -> finish(id,false), () -> finish(id,true)));
        assertThat(results).containsExactlyInAnyOrder("closed","conflict");
        assertThat(available()).isEqualTo(1); assertThat(notifications.count()).isEqualTo(2);
    }

    @Test void concurrentRetryUsesSameBookingAndOneNotice() throws Exception {
        var ids=concurrently(List.of(
                () -> bookings.createBooking(user.getId(),station.getId(),"retry").getBookingId().toString(),
                () -> bookings.createBooking(user.getId(),station.getId(),"retry").getBookingId().toString()));
        assertThat(ids.get(0)).isEqualTo(ids.get(1)); assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test void differentUserKeyReuseConflictsAndCancelledKeyStaysCancelled() {
        var booking=bookings.createBooking(user.getId(),station.getId(),"retry-2");
        Long other=newUser("other@example.com").getId();
        assertThatThrownBy(() -> bookings.createBooking(other,station.getId(),"retry-2")).isInstanceOf(DomainConflictException.class);
        bookings.cancelBooking(booking.getBookingId());
        assertThat(bookings.createBooking(user.getId(),station.getId(),"retry-2").getBookingStatus()).isEqualTo("Cancelled");
    }

    @Test void bookingHistoryPreventsUserAndStationDeletion() {
        Long id=bookings.createBooking(user.getId(),station.getId(),null).getBookingId(); bookings.cancelBooking(id);
        assertThatThrownBy(() -> users.deleteUser(user.getId())).isInstanceOf(DomainConflictException.class);
        assertThatThrownBy(() -> stations.deleteStation(station.getId())).isInstanceOf(DomainConflictException.class);
    }

    @Test void stationResizePreservesOccupiedCapacity() {
        station.setTotalSlots(2);station.setAvaialableSlots(2);stationRepository.saveAndFlush(station);
        bookings.createBooking(user.getId(),station.getId(),null);bookings.createBooking(user.getId(),station.getId(),null);
        assertThatThrownBy(() -> stations.updateStation(station.getId(),new StationUpdateRequestDTO("EV","Pune",1)))
                .isInstanceOf(DomainConflictException.class);
        stations.updateStation(station.getId(),new StationUpdateRequestDTO("EV","Pune",3));
        assertThat(available()).isEqualTo(1);
    }

    @Test void databaseEnforcesInventoryEmailAndStateConstraints() {
        assertThatThrownBy(() -> jdbc.update("update charging_stations set avaialable_slots=2 where id=?",station.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> newUser("learner@example.com")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void inboxReadsAreOwnedAndMarkingReadIsIdempotent() {
        bookings.createBooking(user.getId(),station.getId(),null);
        var notice=inbox.list(user.getId()).get(0);
        assertThat(notice.read()).isFalse();
        Long other=newUser("other@example.com").getId(); assertThat(inbox.list(other)).isEmpty();
        assertThatThrownBy(() -> inbox.markRead(other,notice.id())).isInstanceOf(com.evcharging.exception.BookingNotFoundException.class);
        inbox.markRead(user.getId(),notice.id());inbox.markRead(user.getId(),notice.id());
        assertThat(inbox.list(user.getId()).get(0).read()).isTrue();
    }

    private int available(){return stationRepository.findById(station.getId()).orElseThrow().getAvaialableSlots();}
    private User newUser(String email){User u=new User();u.setName("Learner");u.setEmail(email);return userRepository.saveAndFlush(u);}
    private String createResult(Long id){try{bookings.createBooking(id,station.getId(),null);return "created";}
        catch(DomainConflictException conflict){return "full";}}
    private String finish(Long id,boolean complete){try{if(complete)bookings.completeBooking(id);else bookings.cancelBooking(id);return "closed";}
        catch(DomainConflictException conflict){return "conflict";}}
    private List<String> concurrently(List<Callable<String>> tasks)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(tasks.size()); var ready=new CountDownLatch(tasks.size());var start=new CountDownLatch(1);
        try{
            List<Future<String>> futures=new ArrayList<>();
            for(var task:tasks)futures.add(pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new TimeoutException();return task.call();}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            List<String> values=new ArrayList<>();for(var future:futures)values.add(future.get(20,TimeUnit.SECONDS));return values;
        }finally{start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
}
