import { useEffect, useState } from "react";

import { getFarmsByFarmerId } from "../services/farmService";

// The signed-in farmer's farms, with the first one selected.
// farmsError is set when the list could not be loaded (not the same as
// having no farms); reloadFarms tries again.
export default function useFarmerFarms(farmerId, onError) {

    const [farms, setFarms] = useState([]);
    const [selectedFarmId, setSelectedFarmId] = useState("");
    const [loadingFarms, setLoadingFarms] = useState(true);
    const [farmsError, setFarmsError] = useState(null);
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {

        let cancelled = false;

        const load = async () => {

            if (!farmerId) {
                setFarms([]);
                setSelectedFarmId("");
                setLoadingFarms(false);
                return;
            }

            try {
                setLoadingFarms(true);
                setFarmsError(null);
                const data = (await getFarmsByFarmerId(farmerId)) || [];
                if (!cancelled) {
                    setFarms(data);
                    setSelectedFarmId(data.length > 0 ? String(data[0].id) : "");
                }
            } catch (error) {
                if (!cancelled) {
                    setFarms([]);
                    setSelectedFarmId("");
                    setFarmsError({ message: errorMessage(error, "") });
                    onError?.(error);
                }
            } finally {
                if (!cancelled) {
                    setLoadingFarms(false);
                }
            }
        };

        load();

        return () => {
            cancelled = true;
        };

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [farmerId, reloadKey]);

    const reloadFarms = () => setReloadKey((key) => key + 1);

    return { farms, selectedFarmId, setSelectedFarmId, loadingFarms, farmsError, reloadFarms };
}

export const errorMessage = (error, fallback) => {

    const data = error?.response?.data;

    if (typeof data === "string" && data.trim()) {
        return data;
    }

    const fieldErrors = data?.errors ? Object.values(data.errors) : [];

    if (fieldErrors.length > 0) {
        return fieldErrors.join(" ");
    }

    return data?.message || fallback;
};

export const formatDateTime = (value) => {

    if (!value) {
        return "—";
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};
