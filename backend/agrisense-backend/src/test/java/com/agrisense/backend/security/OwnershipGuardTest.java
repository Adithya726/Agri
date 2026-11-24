package com.agrisense.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.agrisense.backend.exception.AccessDeniedException;
import com.agrisense.backend.exception.ApiExceptionHandler;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.Farmer;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.repository.FarmerRepository;

class OwnershipGuardTest {

    private final FarmerRepository farmers = mock(FarmerRepository.class);
    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final ActionPlanRepository plans = mock(ActionPlanRepository.class);

    private final OwnershipGuard guard =
            new OwnershipGuard(farmers, farms, states, plans);

    private Farmer owner;
    private Farmer other;
    private Farm farm;

    @BeforeEach
    void setUp() {
        owner = farmer(1L);
        other = farmer(2L);

        farm = new Farm();
        farm.setId(10L);
        farm.setFarmer(owner);

        when(farmers.findById(1L)).thenReturn(Optional.of(owner));
        when(farmers.findById(2L)).thenReturn(Optional.of(other));
        when(farms.findById(10L)).thenReturn(Optional.of(farm));
        when(farms.findById(99L)).thenReturn(Optional.empty());

        FarmState state = new FarmState();
        state.setId(20L);
        state.setFarm(farm);
        when(states.findById(20L)).thenReturn(Optional.of(state));

        ActionPlan plan = new ActionPlan();
        plan.setFarm(farm);
        when(plans.findById(30L)).thenReturn(Optional.of(plan));
        when(plans.findById(98L)).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static Farmer farmer(Long id) {
        Farmer f = new Farmer();
        f.setId(id);
        return f;
    }

    private void signIn(String farmerId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.FARMER_ID_ATTR, farmerId);
        request.setAttribute(JwtAuthenticationFilter.FARMER_ROLE_ATTR, role);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void ownerCanReachTheirFarmStateAndPlan() {
        signIn("1", "FARMER");
        assertSame(farm, guard.requireFarm(10L));
        assertEquals(20L, guard.requireFarmState(20L).getId());
        guard.requireActionPlan(30L);
        assertSame(owner, guard.requireFarmer(1L));
        assertSame(owner, guard.currentFarmer());
    }

    @Test
    void otherFarmerGetsAccessDenied() {
        signIn("2", "FARMER");
        assertThrows(AccessDeniedException.class, () -> guard.requireFarm(10L));
        assertThrows(AccessDeniedException.class, () -> guard.requireFarmState(20L));
        assertThrows(AccessDeniedException.class, () -> guard.requireActionPlan(30L));
        assertThrows(AccessDeniedException.class, () -> guard.requireFarmer(1L));
    }

    @Test
    void missingResourcesGetNotFound() {
        signIn("1", "FARMER");
        when(farmers.findById(77L)).thenReturn(Optional.empty());
        when(states.findById(97L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> guard.requireFarm(99L));
        assertThrows(ResourceNotFoundException.class, () -> guard.requireFarm(null));
        assertThrows(ResourceNotFoundException.class, () -> guard.requireFarmState(97L));
        assertThrows(ResourceNotFoundException.class, () -> guard.requireActionPlan(98L));
        assertThrows(ResourceNotFoundException.class, () -> guard.requireFarmer(77L));
    }

    @Test
    void adminPassesOwnershipChecks() {
        signIn("2", "ADMIN");
        assertTrue(guard.isAdmin());
        assertSame(farm, guard.requireFarm(10L));
        assertSame(owner, guard.requireFarmer(1L));
    }

    @Test
    void noAuthenticatedFarmerIsDenied() {
        assertThrows(AccessDeniedException.class, guard::currentFarmerId);
        signIn("not-a-number", "FARMER");
        assertThrows(AccessDeniedException.class, guard::currentFarmerId);
    }

    @Test
    void handlerMapsExceptionsToStatusCodes() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        var forbidden = handler.forbidden(new AccessDeniedException("no"));
        var missing = handler.notFound(new ResourceNotFoundException("gone"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(false, forbidden.getBody().get("success"));
        assertEquals("gone", missing.getBody().get("message"));
    }
}
