import api from "./api";

export const generateActionPlan = async (farmId) => {
    const response = await api.post(
        `/actionplanapi/generate/${farmId}`
    );

    return response.data;
};

// The plan for the farm as it is now: the backend makes a new draft
// when the farm state was saved after the latest plan.
export const getCurrentActionPlan = async (farmId) => {
    const response = await api.get(
        `/actionplanapi/current/${farmId}`
    );

    return response.data;
};

export const getActionPlanById = async (planId) => {
    const response = await api.get(
        `/actionplanapi/${planId}`
    );

    return response.data;
};

export const getActionPlansByFarmId = async (farmId) => {
    const response = await api.get(
        `/actionplanapi/farm/${farmId}`
    );

    return response.data;
};

export const reviewActionPlan = async (planId, decision) => {
    const response = await api.put(
        `/actionplanapi/${planId}/review`,
        null,
        { params: { decision } }
    );

    return response.data;
};

// Whole-season plan, re-decided from the farm's latest state.
export const getSeasonPlan = async (farmId) => {
    const response = await api.get(
        `/actionplanapi/season/${farmId}`
    );

    return response.data;
};
