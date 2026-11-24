import api from "./api";

export const getReplanAssessment = async (farmId) => {
    const response = await api.get(
        `/replanapi/farm/${farmId}/assessment`
    );

    return response.data;
};

export const replanFarm = async (farmId) => {
    const response = await api.post(
        `/replanapi/farm/${farmId}`
    );

    return response.data;
};

export const comparePlans = async (currentPlanId, newPlanId) => {
    const response = await api.get(
        "/replanapi/compare",
        { params: { currentPlanId, newPlanId } }
    );

    return response.data;
};
