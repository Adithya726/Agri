package com.agrisense.backend.controller;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.agrisense.backend.dto.FarmerRegisterRequest;
import com.agrisense.backend.dto.LoginRequest;
import com.agrisense.backend.dto.LoginResponse;
import com.agrisense.backend.service.AuthenticationService;

@RestController
@RequestMapping("/farmerapi")
@CrossOrigin(origins = "*")
public class AuthenticationController {

    // A simple shape check; the address is not verified.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    static final int MIN_PASSWORD_LENGTH = 6;

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> registerFarmer(
            @RequestBody FarmerRegisterRequest request) {

        String problem = registrationProblem(request);

        if (problem != null) {
            return ResponseEntity
                    .badRequest()
                    .body(new LoginResponse(false, problem, null, null, null, null));
        }

        request.setEmail(request.getEmail().trim());
        request.setName(request.getName().trim());

        LoginResponse response =
                authenticationService.registerFarmer(request);

        if (!response.isSuccess()) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(response);
        }

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> loginFarmer(
            @RequestBody LoginRequest request) {

        LoginResponse response =
                authenticationService.loginFarmer(request);

        if (!response.isSuccess()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    // Reject registrations the farmers table cannot hold or that could
    // never be used to sign in (blank fields, malformed email, short password).
    static String registrationProblem(FarmerRegisterRequest request) {

        if (request == null
                || isBlank(request.getName())
                || isBlank(request.getEmail())
                || isBlank(request.getPassword())) {
            return "Name, email and password are required";
        }

        if (!EMAIL_PATTERN.matcher(request.getEmail().trim()).matches()) {
            return "Enter a valid email address";
        }

        if (request.getPassword().length() < MIN_PASSWORD_LENGTH) {
            return "Password must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }

        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
