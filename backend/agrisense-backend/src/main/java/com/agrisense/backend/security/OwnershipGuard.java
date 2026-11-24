package com.agrisense.backend.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.agrisense.backend.exception.AccessDeniedException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.Farmer;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.repository.FarmerRepository;

/*
 * One place for "does this resource belong to the signed-in farmer?".
 *
 * The caller comes from the request attributes set by
 * JwtAuthenticationFilter. Each require* method loads the resource,
 * throws ResourceNotFoundException (404) when it does not exist and
 * AccessDeniedException (403) when it belongs to another farmer.
 * A caller with the ADMIN role passes every ownership check.
 */
@Component
public class OwnershipGuard {

    public static final String ADMIN_ROLE = "ADMIN";

    private final FarmerRepository farmerRepository;
    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final ActionPlanRepository actionPlanRepository;

    public OwnershipGuard(FarmerRepository farmerRepository,
                          FarmRepository farmRepository,
                          FarmStateRepository farmStateRepository,
                          ActionPlanRepository actionPlanRepository) {
        this.farmerRepository = farmerRepository;
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.actionPlanRepository = actionPlanRepository;
    }

    // Id of the farmer the bearer token belongs to.
    public Long currentFarmerId() {

        Object value = attribute(JwtAuthenticationFilter.FARMER_ID_ATTR);

        if (value == null) {
            throw new AccessDeniedException("No authenticated farmer");
        }

        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("No authenticated farmer");
        }
    }

    public boolean isAdmin() {
        Object role = attribute(JwtAuthenticationFilter.FARMER_ROLE_ATTR);
        return role != null && ADMIN_ROLE.equalsIgnoreCase(role.toString());
    }

    // Only an ADMIN may change shared reference data.
    public void requireAdmin() {
        currentFarmerId();
        if (!isAdmin()) {
            throw new AccessDeniedException("Only an administrator can do this");
        }
    }

    // The signed-in farmer's own record (used as the owner of new farms).
    public Farmer currentFarmer() {
        Long id = currentFarmerId();
        return farmerRepository.findById(id)
                .orElseThrow(() -> new AccessDeniedException("No authenticated farmer"));
    }

    // A farmer record the caller may read or change: only their own.
    public Farmer requireFarmer(Long farmerId) {

        Farmer farmer = farmerRepository.findById(farmerId)
                .orElseThrow(() -> new ResourceNotFoundException("Farmer not found"));

        checkOwner(farmer.getId(), "farmer");
        return farmer;
    }

    public Farm requireFarm(Long farmId) {

        if (farmId == null) {
            throw new ResourceNotFoundException("Farm not found");
        }

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        checkOwner(ownerOf(farm), "farm");
        return farm;
    }

    public FarmState requireFarmState(Long farmStateId) {

        FarmState state = farmStateRepository.findById(farmStateId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm state not found"));

        checkOwner(ownerOf(state.getFarm()), "farm state");
        return state;
    }

    public ActionPlan requireActionPlan(Long planId) {

        ActionPlan plan = actionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Action plan not found"));

        // The plan's farm is lazy; load it by id to read its owner.
        Long farmId = plan.getFarm() == null ? null : plan.getFarm().getId();
        Farm farm = farmId == null ? null : farmRepository.findById(farmId).orElse(null);

        checkOwner(ownerOf(farm), "action plan");
        return plan;
    }

    private Long ownerOf(Farm farm) {
        return farm == null || farm.getFarmer() == null ? null : farm.getFarmer().getId();
    }

    private void checkOwner(Long ownerId, String what) {

        if (isAdmin()) {
            return;
        }

        if (ownerId == null || !ownerId.equals(currentFarmerId())) {
            throw new AccessDeniedException("You do not have access to this " + what);
        }
    }

    private Object attribute(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null
                ? null
                : attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    }
}
