import api from "./api";

export const getFarmDecision = async (farmId) => {
    const response = await api.get(`/decisionapi/farm/${farmId}`);

    return response.data;
};
