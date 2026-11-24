package com.agrisense.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.Farmer;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.repository.FarmerRepository;

@Service
public class FarmServiceImpl implements FarmService {

    private final FarmRepository farmRepository;
    private final FarmerRepository farmerRepository;
    private final FarmStateRepository farmStateRepository;
    private final ActionPlanRepository actionPlanRepository;

    public FarmServiceImpl(FarmRepository farmRepository,
                           FarmerRepository farmerRepository,
                           FarmStateRepository farmStateRepository,
                           ActionPlanRepository actionPlanRepository) {
        this.farmRepository = farmRepository;
        this.farmerRepository = farmerRepository;
        this.farmStateRepository = farmStateRepository;
        this.actionPlanRepository = actionPlanRepository;
    }

    @Override
    public Farm addFarm(Farm farm) {
        return farmRepository.save(farm);
    }

    @Override
    public List<Farm> getAllFarms() {
        return farmRepository.findAll();
    }

    @Override
    public Farm getFarmById(Long id) {
        return farmRepository.findById(id).orElse(null);
    }

    @Override
    public List<Farm> getFarmsByFarmerId(Long farmerId) {

        Farmer farmer = farmerRepository.findById(farmerId).orElse(null);

        if (farmer == null) {
            return List.of();
        }

        return farmRepository.findByFarmer(farmer);
    }

    @Override
    public Farm updateFarm(Long id, Farm farm) {

        Farm existingFarm = farmRepository.findById(id).orElse(null);

        if (existingFarm == null) {
            return null;
        }

        existingFarm.setFarmName(farm.getFarmName());
        existingFarm.setLocation(farm.getLocation());
        existingFarm.setArea(farm.getArea());
        existingFarm.setCrop(farm.getCrop());
        existingFarm.setVariety(farm.getVariety());
        existingFarm.setGrowthStage(farm.getGrowthStage());

        return farmRepository.save(existingFarm);
    }

    /*
     * A farm's state and action plans reference it by foreign key, so
     * they are removed with it (plan items cascade from their plan).
     * Without this, deleting a farm that has data failed with a 500.
     */
    @Override
    @Transactional
    public boolean deleteFarm(Long id) {

        Farm farm = farmRepository.findById(id).orElse(null);

        if (farm == null) {
            return false;
        }

        actionPlanRepository.deleteAll(
                actionPlanRepository.findByFarmIdOrderByCreatedAtDesc(id));

        farmStateRepository.findByFarm(farm)
                .ifPresent(farmStateRepository::delete);

        farmRepository.delete(farm);
        return true;
    }
}