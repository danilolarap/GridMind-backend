package com.gridmind.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridmind.backend.model.User;
import com.gridmind.backend.repository.UserRepository;
import com.gridmind.backend.security.JwtService;
import com.gridmind.backend.service.PredictiveService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired com.gridmind.backend.repository.AlertRepository alerts;
    @MockitoBean PredictiveService predictions;

    private String newEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    private JsonNode register(String email) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/users/register")
                .with(request -> { request.setRemoteAddr(email); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "name", "Test User", "email", email, "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.resetToken").doesNotExist())
                .andExpect(jsonPath("$.resetTokenExpiry").doesNotExist())
                .andReturn().getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        register(email);
        return "Bearer " + jwt.generateToken(email);
    }

    private JsonNode device(String email) throws Exception {
        String authorization = token(email);
        return json.readTree(mvc.perform(post("/api/v1/devices")
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "name", "Sensor", "esp32Id", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andReturn().getResponse().getContentAsString());
    }

    @Test void registrationLoginAndProtectedProfile() throws Exception {
        String email = newEmail();
        register(email);
        String response = mvc.perform(post("/api/v1/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + json.readTree(response).get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test void duplicateRegistrationReturnsConflict() throws Exception {
        String email = newEmail();
        register(email);
        mvc.perform(post("/api/v1/users/register")
                .with(request -> { request.setRemoteAddr(email); return request; }).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "name", "Test User", "email", email, "password", "password123"))))
                .andExpect(status().isConflict());
    }

    @Test void invalidLoginReturnsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/users/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", newEmail(), "password", "password123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test void invalidAndMissingTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test void resetTokensAreHiddenAndSingleUse() throws Exception {
        String email = newEmail();
        String auth = token(email);
        User user = users.findByEmail(email).orElseThrow();
        user.setResetToken("valid-reset-token");
        user.setResetTokenExpiry(LocalDateTime.now().plusMinutes(5));
        users.save(user);
        mvc.perform(get("/api/v1/users/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").doesNotExist())
                .andExpect(jsonPath("$.resetTokenExpiry").doesNotExist());
        String body = json.writeValueAsString(Map.of(
                "token", "valid-reset-token", "newPassword", "newpassword123"));
        mvc.perform(post("/api/v1/users/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/users/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());
        assertNull(users.findByEmail(email).orElseThrow().getResetToken());
    }

    @Test void resetWithMissingExpiryReturnsBadRequest() throws Exception {
        String email = newEmail();
        register(email);
        User user = users.findByEmail(email).orElseThrow();
        user.setResetToken("no-expiry");
        users.save(user);
        mvc.perform(post("/api/v1/users/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"no-expiry\",\"newPassword\":\"password456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void telemetryRequiresAllMeasurementsAndValidKey() throws Exception {
        mvc.perform(post("/api/v1/iot/telemetry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"unknown\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/iot/telemetry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"unknown\",\"voltage\":120,\"current\":1,\"power\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/iot/telemetry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"unknown\",\"voltage\":120,\"current\":1,\"power\":120}"))
                .andExpect(status().isForbidden());
    }

    @Test void validTelemetryIsPersistedAndVisibleToOwnerOnly() throws Exception {
        String email = newEmail();
        JsonNode device = device(email);
        when(predictions.getForecast(anyString())).thenReturn(Map.of("prediction", 1.0));
        mvc.perform(post("/api/v1/iot/telemetry").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "apiKey", device.get("apiKey").asText(), "voltage", 120, "current", 1, "power", 120))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/energy/" + device.get("id").asLong())
                .header("Authorization", "Bearer " + jwt.generateToken(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].consumption").isNumber());
        mvc.perform(get("/api/v1/analytics/daily")
                .header("Authorization", "Bearer " + jwt.generateToken(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalKwh").isNumber());
        mvc.perform(get("/api/v1/energy/" + device.get("id").asLong())
                .header("Authorization", token(newEmail())))
                .andExpect(status().isForbidden());
    }

    @Test void nullRelayStateReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/devices/1/relay")
                .header("Authorization", token(newEmail()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"state\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test void malformedJsonAndMissingApiKeyReturnBadRequest() throws Exception {
        mvc.perform(post("/api/v1/iot/telemetry").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/iot/relay-status")).andExpect(status().isBadRequest());
    }

    @Test void negativeConsumptionIsRejected() throws Exception {
        mvc.perform(post("/api/v1/energy/1").param("consumption", "-1")
                .header("Authorization", token(newEmail())))
                .andExpect(status().isBadRequest());
    }

    @Test void blankDeviceIsRejected() throws Exception {
        mvc.perform(post("/api/v1/devices").header("Authorization", token(newEmail()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void signingKeyMustBeStrongAndTokensCannotBeForged() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("short"));
        String signed = jwt.generateToken("test@example.com");
        assertTrue(jwt.validateToken(signed));
        assertEquals("test@example.com", jwt.extractUsername(signed));
        JwtService other = new JwtService("different-secret-of-at-least-thirty-two-bytes");
        assertThrows(io.jsonwebtoken.JwtException.class, () -> other.extractUsername(signed));
    }
    @Test void deletingDeviceAlsoDeletesItsReadings() throws Exception {
        String email = newEmail();
        JsonNode d = device(email);
        String auth = "Bearer " + jwt.generateToken(email);
        mvc.perform(post("/api/v1/energy/" + d.get("id").asLong())
                .param("consumption", "0.1").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/devices/" + d.get("id").asLong())
                .header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/" + d.get("id").asLong())
                .header("Authorization", auth)).andExpect(status().isNotFound());
    }

    @Test void incorrectCurrentPasswordReturnsBadRequest() throws Exception {
        mvc.perform(put("/api/v1/users/change-password")
                .header("Authorization", token(newEmail()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "currentPassword", "incorrect123", "newPassword", "newpassword123"))))
                .andExpect(status().isBadRequest());
    }

    @Test void expiredResetTokenIsRejected() throws Exception {
        String email = newEmail();
        register(email);
        User user = users.findByEmail(email).orElseThrow();
        user.setResetToken("expired-token");
        user.setResetTokenExpiry(LocalDateTime.now().minusMinutes(1));
        users.save(user);
        mvc.perform(post("/api/v1/users/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "token", "expired-token", "newPassword", "newpassword123"))))
                .andExpect(status().isBadRequest());
    }

    @Test void loginRateLimitReturnsTooManyRequests() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/v1/users/login")
                    .with(request -> { request.setRemoteAddr("192.0.2.99"); return request; })
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/users/login")
                .with(request -> { request.setRemoteAddr("192.0.2.99"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test void billsWithoutGeminiReturnServiceUnavailable() throws Exception {
        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "bill.png", "image/png", new byte[] {1, 2, 3});
        mvc.perform(multipart("/api/v1/bills/upload").file(image)
                .header("Authorization", token(newEmail())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void alertsCanOnlyBeMarkedReadByTheirOwner() throws Exception {
        String email = newEmail();
        String auth = token(email);
        com.gridmind.backend.model.Alert alert = new com.gridmind.backend.model.Alert();
        alert.setUser(users.findByEmail(email).orElseThrow());
        alert.setMessage("Test alert");
        alert = alerts.save(alert);
        mvc.perform(patch("/api/v1/alerts/" + alert.getId() + "/read")
                .header("Authorization", token(newEmail())))
                .andExpect(status().isForbidden());
        assertFalse(alerts.findById(alert.getId()).orElseThrow().getRead());
        mvc.perform(patch("/api/v1/alerts/" + alert.getId() + "/read")
                .header("Authorization", auth)).andExpect(status().isOk());
        assertTrue(alerts.findById(alert.getId()).orElseThrow().getRead());
    }

}
