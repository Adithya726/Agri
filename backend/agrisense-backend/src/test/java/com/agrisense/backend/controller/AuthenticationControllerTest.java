package com.agrisense.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.agrisense.backend.dto.FarmerRegisterRequest;
import com.agrisense.backend.dto.LoginRequest;
import com.agrisense.backend.dto.LoginResponse;
import com.agrisense.backend.service.AuthenticationService;

class AuthenticationControllerTest {

    private final AtomicInteger registerCalls = new AtomicInteger();

    private final AuthenticationController controller =
            new AuthenticationController(new AuthenticationService() {
                @Override
                public LoginResponse registerFarmer(FarmerRegisterRequest request) {
                    registerCalls.incrementAndGet();
                    return new LoginResponse(true, "ok", 9L, request.getName(), request.getEmail(), "FARMER");
                }

                @Override
                public LoginResponse loginFarmer(LoginRequest request) {
                    throw new UnsupportedOperationException();
                }
            });

    private static FarmerRegisterRequest request(String name, String email, String password) {
        FarmerRegisterRequest request = new FarmerRegisterRequest();
        request.setName(name);
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    @Test
    void rejectsMissingFieldsMalformedEmailAndShortPassword() {
        assertEquals(400, controller.registerFarmer(request(" ", "a@b.co", "secret1")).getStatusCode().value());
        assertEquals(400, controller.registerFarmer(request("Ravi", "not-an-email", "secret1")).getStatusCode().value());
        assertEquals(400, controller.registerFarmer(request("Ravi", "ravi@example.com", "123")).getStatusCode().value());

        ResponseEntity<LoginResponse> response =
                controller.registerFarmer(request("Ravi", null, "secret1"));
        assertFalse(response.getBody().isSuccess());
        assertNull(response.getBody().getToken());
        assertEquals(0, registerCalls.get());
    }

    @Test
    void acceptsValidRegistrationAndTrimsEmail() {
        ResponseEntity<LoginResponse> response =
                controller.registerFarmer(request(" Ravi ", " ravi@example.com ", "secret1"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("ravi@example.com", response.getBody().getEmail());
        assertEquals("Ravi", response.getBody().getName());
        assertEquals(1, registerCalls.get());
    }
}
