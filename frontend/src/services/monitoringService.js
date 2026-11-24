import api from "./api";

export const getFarmMonitoring = async (farmId) => {
    const response = await api.get(
        `/monitoringapi/farm/${farmId}`
    );

    return response.data;
};

export const reportActionExecution = async (itemId, report) => {
    const response = await api.put(
        `/monitoringapi/items/${itemId}/execution`,
        report
    );

    return response.data;
};

export const clearActionExecution = async (itemId) => {
    const response = await api.delete(
        `/monitoringapi/items/${itemId}/execution`
    );

    return response.data;
};
