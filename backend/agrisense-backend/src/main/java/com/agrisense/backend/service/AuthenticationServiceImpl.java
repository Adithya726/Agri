package com.agrisense.backend.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.agrisense.backend.dto.FarmerRegisterRequest;
import com.agrisense.backend.dto.LoginRequest;
import com.agrisense.backend.dto.LoginResponse;
import com.agrisense.backend.model.Farmer;
import com.agrisense.backend.repository.FarmerRepository;
import com.agrisense.backend.security.JwtService;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String DEFAULT_ROLE = "FARMER";

    private final FarmerRepository farmerRepository;

    // BCrypt hashes passwords before they are stored.
    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    private final JwtService jwtService;

    public AuthenticationServiceImpl(FarmerRepository farmerRepository,
                                     JwtService jwtService) {
        this.farmerRepository = farmerRepository;
        this.jwtService = jwtService;
    }

    @Override
    public LoginResponse registerFarmer(FarmerRegisterRequest request) {

        if (farmerRepository.existsByEmail(request.getEmail())) {
            return new LoginResponse(
                    false,
                    "Email already registered",
                    null,
                    null,
                    null,
                    null
            );
        }

        Farmer farmer = new Farmer();

        farmer.setName(request.getName());
        farmer.setEmail(request.getEmail());
        farmer.setPassword(passwordEncoder.encode(request.getPassword()));
        farmer.setPhone(request.getPhone());
        farmer.setLocation(request.getLocation());
        farmer.setRole(DEFAULT_ROLE);

        Farmer savedFarmer = farmerRepository.save(farmer);

        return successResponse(
                "Farmer registered successfully",
                savedFarmer
        );
    }

    @Override
    public LoginResponse loginFarmer(LoginRequest request) {

        Farmer farmer = farmerRepository
                .findByEmail(request.getEmail())
                .orElse(null);

        if (farmer == null) {
            return new LoginResponse(
                    false,
                    "Invalid email or password",
                    null,
                    null,
                    null,
                    null
            );
        }

        if (!passwordMatches(farmer, request.getPassword())) {
            return new LoginResponse(
                    false,
                    "Invalid email or password",
                    null,
                    null,
                    null,
                    null
            );
        }

        return successResponse("Login successful", farmer);
    }

    private LoginResponse successResponse(String message, Farmer farmer) {

        String role = farmer.getRole() != null
                ? farmer.getRole()
                : DEFAULT_ROLE;

        LoginResponse response = new LoginResponse(
                true,
                message,
                farmer.getId(),
                farmer.getName(),
                farmer.getEmail(),
                role
        );

        response.setToken(
                jwtService.generateToken(farmer.getId(), farmer.getEmail(), role)
        );

        return response;
    }

    /*
     * Accounts created before hashing was added still hold a
     * plain-text password. Accept it once, then replace it
     * with a BCrypt hash so it is never stored in plain text again.
     */
    private boolean passwordMatches(Farmer farmer, String rawPassword) {

        String stored = farmer.getPassword();

        if (stored == null || rawPassword == null) {
            return false;
        }

        if (stored.matches("^\\$2[aby]\\$\\d{2}\\$.{53}$")) {
            return passwordEncoder.matches(rawPassword, stored);
        }

        if (stored.equals(rawPassword)) {
            farmer.setPassword(passwordEncoder.encode(rawPassword));
            farmerRepository.save(farmer);
            return true;
        }

        return false;
    }
}
