import api from "./api";

export const generateActionPlan = async (farmId) => {
    const response = await api.post(
        `/actionplanapi/generate/${farmId}`
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
