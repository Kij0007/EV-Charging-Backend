package com.evcharging;

import com.evcharging.entity.*;
import com.evcharging.repository.*;
import com.evcharging.security.UserPrincipal;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:website-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WebsiteApiIntegrationTest extends PostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ChargingStationRepository stations;
    @Autowired BookingRepository bookings;
    @Autowired UserNotificationRepository inbox;
    @Autowired PasswordEncoder encoder;
    User customer,other,admin;
    ChargingStation station;

    @BeforeEach void setup() {
        inbox.deleteAll();bookings.deleteAll();users.deleteAll();stations.deleteAll();
        customer=account("customer@example.com","USER");other=account("other@example.com","USER");admin=account("admin@example.com","ADMIN");
        station=new ChargingStation();station.setStationName("Test Hub");station.setLocation("Pune");
        station.setTotalSlots(2);station.setAvaialableSlots(2);station=stations.saveAndFlush(station);
    }

    @Test void publicDirectoryAndStaticWebsiteWorkWithoutLogin() throws Exception {
        // MockMvc records a servlet forward without executing the target resource.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Voltway")));
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mvc.perform(get("/api/stations").param("query","pUnE")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stationName").value("Test Hub"));
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test void reservationUsesAuthenticatedIdentityAndNoOnlineCharge() throws Exception {
        var result=mvc.perform(as(customer,post("/api/bookings")).header("Idempotency-Key","owned-reservation")
                .content(body(Map.of("stationId",station.getId())))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(customer.getId())).andExpect(jsonPath("$.amount").value(0))
                .andExpect(jsonPath("$.stationName").value("Test Hub")).andExpect(jsonPath("$.createdAt").exists()).andReturn();
        long id=json.readTree(result.getResponse().getContentAsString()).get("bookingId").asLong();
        mvc.perform(as(customer,post("/api/bookings")).header("Idempotency-Key","owned-reservation")
                .content(body(Map.of("stationId",station.getId())))).andExpect(status().isCreated()).andExpect(jsonPath("$.bookingId").value(id));
        assertThat(bookings.count()).isEqualTo(1);assertThat(inbox.count()).isEqualTo(1);
    }

    @Test void requestCannotImpersonateAnotherAccount() throws Exception {
        mvc.perform(as(customer,post("/api/bookings")).content(body(Map.of("stationId",station.getId(),"userId",other.getId()))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(customer,post("/booking/createBooking")).content(body(Map.of("userId",other.getId(),"stationId",station.getId(),"amount",500))))
                .andExpect(status().isForbidden());
        assertThat(bookings.count()).isZero();
    }

    @Test void personalListsAndLegacyReadsEnforceOwnership() throws Exception {
        long id=reserve(customer);
        mvc.perform(as(other,get("/api/bookings"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(as(other,get("/booking/"+id))).andExpect(status().isForbidden());
        mvc.perform(as(other,get("/booking/user/"+customer.getId()))).andExpect(status().isForbidden());
        mvc.perform(as(other,get("/booking/all"))).andExpect(status().isForbidden());
        mvc.perform(as(admin,get("/api/bookings"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(admin,get("/booking/"+id))).andExpect(status().isOk());
    }

    @Test void onlyOwnerOrAdministratorCanCancelAndOnlyAdministratorCanComplete() throws Exception {
        long id=reserve(customer);
        mvc.perform(as(other,post("/api/bookings/"+id+"/cancel"))).andExpect(status().isForbidden());
        mvc.perform(as(customer,post("/api/bookings/"+id+"/complete"))).andExpect(status().isForbidden());
        mvc.perform(as(admin,post("/api/bookings/"+id+"/complete"))).andExpect(status().isOk()).andExpect(jsonPath("$.bookingStatus").value("Completed"));
        mvc.perform(as(customer,post("/api/bookings/"+id+"/cancel"))).andExpect(status().isConflict());
        assertThat(stations.findById(station.getId()).orElseThrow().getAvaialableSlots()).isEqualTo(2);
    }

    @Test void ownerCanCancelAndReadInboxButCannotReadAnotherNotice() throws Exception {
        long id=reserve(customer);
        mvc.perform(as(customer,post("/api/bookings/"+id+"/cancel"))).andExpect(status().isOk());
        var result=mvc.perform(as(customer,get("/api/notifications"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andReturn();
        long notice=json.readTree(result.getResponse().getContentAsString()).get(0).get("id").asLong();
        mvc.perform(as(other,put("/api/notifications/"+notice+"/read"))).andExpect(status().isNotFound());
        mvc.perform(as(customer,put("/api/notifications/"+notice+"/read"))).andExpect(status().isNoContent());
        mvc.perform(as(customer,get("/api/notifications"))).andExpect(jsonPath("$[0].read").value(true));
    }

    @Test void administrationAndLegacyStationMutationsRequireAdmin() throws Exception {
        String create=body(Map.of("stationName","New Hub","location","Mumbai","totalSlots",3,"avaialableSlots",3));
        mvc.perform(as(customer,post("/api/stations")).content(create)).andExpect(status().isForbidden());
        mvc.perform(as(customer,get("/api/admin/users"))).andExpect(status().isForbidden());
        mvc.perform(as(customer,get("/users/all"))).andExpect(status().isForbidden());
        mvc.perform(as(customer,put("/station/reduceSlot/"+station.getId()))).andExpect(status().isForbidden());
        mvc.perform(as(admin,post("/api/stations")).content(create)).andExpect(status().isCreated()).andExpect(jsonPath("$.stationName").value("New Hub"));
        mvc.perform(as(admin,get("/api/admin/users"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test void stationEditingAndDeletionPreserveHistoryAndCapacity() throws Exception {
        reserve(customer);
        mvc.perform(as(admin,put("/api/stations/"+station.getId())).content(body(Map.of("stationName","Renamed","location","Pune","totalSlots",3))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avaialableSlots").value(2));
        mvc.perform(as(admin,delete("/api/stations/"+station.getId()))).andExpect(status().isConflict());
        mvc.perform(as(admin,put("/api/stations/"+station.getId())).content(body(Map.of("stationName","EV","location","Pune","totalSlots",3,"avaialableSlots",3))))
                .andExpect(status().isBadRequest());
    }

    @Test void profileUpdateCannotChangeRoleOrAnotherAccount() throws Exception {
        mvc.perform(as(customer,put("/api/profile")).content(body(Map.of("name","New Name","email","NEW@example.com"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.com")).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(as(customer,put("/api/profile")).content(body(Map.of("name","Admin","email","x@example.com","role","ADMIN"))))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(other.getId()).orElseThrow().getEmail()).isEqualTo("other@example.com");
    }

    @Test void csrfAndSecurityHeadersAreEnforced() throws Exception {
        mvc.perform(post("/api/bookings").with(user(new UserPrincipal(customer))).contentType("application/json")
                .content(body(Map.of("stationId",station.getId())))).andExpect(status().isForbidden());
        mvc.perform(get("/")).andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
    }

    private MockHttpServletRequestBuilder as(User account,MockHttpServletRequestBuilder request) {
        return request.with(user(new UserPrincipal(account))).with(csrf()).contentType("application/json");
    }
    private long reserve(User account) throws Exception {
        var result=mvc.perform(as(account,post("/api/bookings")).content(body(Map.of("stationId",station.getId()))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("bookingId").asLong();
    }
    private User account(String email,String role) {
        User account=new User();account.setName("Account");account.setEmail(email);account.setRole(role);
        account.setPasswordHash(encoder.encode("test-account-password"));return users.saveAndFlush(account);
    }
    private String body(Object value){return json.writeValueAsString(value);}
}
