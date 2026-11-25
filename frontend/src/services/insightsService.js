import api from "./api";

// Timeline of one farm. params: { type, from, to } (all optional).
export const getFarmHistory = async (farmId, params = {}) => {
    const response = await api.get(`/historyapi/farm/${farmId}`, { params });
    return response.data;
};

// Run the pipeline on an edited copy of the farm state. Nothing is saved.
export const runWhatIf = async (farmId, changes) => {
    const response = await api.post(`/whatifapi/farm/${farmId}`, changes);
    return response.data;
};

export const askFarmAssistant = async (farmId, question) => {
    const response = await api.post(`/assistantapi/farm/${farmId}`, { question });
    return response.data;
};
