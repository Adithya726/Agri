package com.agrisense.backend.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.agrisense.backend.dto.FarmerRegisterRequest;
import com.agrisense.backend.dto.LoginRequest;
import com.agrisense.backend.dto.LoginResponse;
import com.agrisense.backend.model.Farmer;
import com.agrisense.backend.repository.FarmerRepository;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private final FarmerRepository farmerRepository;

    // BCrypt hashes passwords before they are stored.
    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    public AuthenticationServiceImpl(FarmerRepository farmerRepository) {
        this.farmerRepository = farmerRepository;
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

        Farmer savedFarmer = farmerRepository.save(farmer);

        return new LoginResponse(
                true,
                "Farmer registered successfully",
                savedFarmer.getId(),
                savedFarmer.getName(),
                savedFarmer.getEmail(),
                "FARMER"
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

        return new LoginResponse(
                true,
                "Login successful",
                farmer.getId(),
                farmer.getName(),
                farmer.getEmail(),
                "FARMER"
        );
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
