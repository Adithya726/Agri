import { useEffect, useState } from "react";

import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";

import { FarmListState } from "../../components/PageState/PageState";
import useFarmerFarms from "../../hooks/useFarmerFarms";

import {
    getFarmStateByFarmId,
    addFarmState,
    updateFarmState
} from "../../services/farmStateService";

import "./FarmState.css";
import farmFields from "../../assets/farm-fields.jpg";


/*
 * Range rules for the numeric farm state fields.
 * These mirror the backend FarmStateValidator so the farmer
 * sees a problem before the request is sent; the backend
 * still enforces them and its field errors are shown too.
 */
const NUMERIC_RULES = {
    soilMoisture: { min: 0, max: 100 },
    soilPh: { min: 0, max: 14 },
    nitrogen: { min: 0 },
    phosphorus: { min: 0 },
    potassium: { min: 0 },
    temperature: { min: -50, max: 60 },
    humidity: { min: 0, max: 100 },
    rainfall: { min: 0 },
    waterAvailable: { min: 0 },
    labourAvailable: { min: 0, integer: true },
    budgetAvailable: { min: 0 }
};

const GROWTH_STAGE_OPTIONS = [
    "Seedling", "Vegetative", "Flowering", "Fruiting", "Maturity", "Harvest"
];

const CROP_HEALTH_OPTIONS = ["Healthy", "Moderate", "Poor", "Not checked"];

const DISEASE_STATUS_OPTIONS = [
    "No known disease", "Suspected", "Confirmed", "Not checked"
];

/*
 * Fields shown in the "current saved state" summary,
 * with the translation key of their label and their unit.
 */
const SUMMARY_FIELDS = [
    ["crop", "crop", ""],
    ["growthStage", "growthStage", ""],
    ["soilMoisture", "soilMoisture", "%"],
    ["soilPh", "soilPh", ""],
    ["nitrogen", "nitrogen", ""],
    ["phosphorus", "phosphorus", ""],
    ["potassium", "potassium", ""],
    ["temperature", "temperature", "°C"],
    ["humidity", "humidity", "%"],
    ["rainfall", "rainfall", "mm"],
    ["waterAvailable", "waterAvailable", ""],
    ["labourAvailable", "labourAvailable", ""],
    ["budgetAvailable", "budgetAvailable", ""],
    ["cropHealth", "cropHealth", ""],
    ["diseaseStatus", "diseaseStatus", ""]
];

const validateFarmStateForm = (formData, t) => {

    const errors = {};

    Object.entries(NUMERIC_RULES).forEach(([field, rule]) => {

        const raw = formData[field];

        if (raw === "" || raw === null || raw === undefined) {
            return;
        }

        const value = Number(raw);

        if (!Number.isFinite(value)) {
            errors[field] = t("errNotNumber");
            return;
        }

        if (rule.integer && !Number.isInteger(value)) {
            errors[field] = t("errWholeNumber");
            return;
        }

        if (rule.max !== undefined
            && (value < rule.min || value > rule.max)) {

            errors[field] = t("errValueRange")
                .replace("{min}", rule.min)
                .replace("{max}", rule.max);

        } else if (value < rule.min) {

            errors[field] = t("errNonNegative");
        }
    });

    return errors;
};

function FarmState() {

    const { farmer } = useAuth();
    const { t } = useLanguage();

    const {
        farms,
        selectedFarmId,
        setSelectedFarmId,
        loadingFarms,
        farmsError,
        reloadFarms
    } = useFarmerFarms(
        farmer?.userId,
        (error) => toast.error(error.response?.data?.message || t("farmsLoadFailed"))
    );

    const [farmState, setFarmState] = useState(null);

    const [loadingState, setLoadingState] = useState(false);
    const [saving, setSaving] = useState(false);

    const [errors, setErrors] = useState({});

    const [formData, setFormData] = useState({
        crop: "",
        growthStage: "",

        soilMoisture: "",
        soilPh: "",

        nitrogen: "",
        phosphorus: "",
        potassium: "",

        temperature: "",
        humidity: "",
        rainfall: "",

        waterAvailable: "",
        labourAvailable: "",
        budgetAvailable: "",

        cropHealth: "",
        diseaseStatus: ""
    });


    /*
     * Reset the form when a selected farm
     * does not have a Farm State yet.
     */
    const resetFormForSelectedFarm = () => {

        const selectedFarm = farms.find(
            (farm) =>
                String(farm.id) === String(selectedFarmId)
        );

        setFormData({

            crop: selectedFarm?.crop || "",
            growthStage: selectedFarm?.growthStage || "",

            soilMoisture: "",
            soilPh: "",

            nitrogen: "",
            phosphorus: "",
            potassium: "",

            temperature: "",
            humidity: "",
            rainfall: "",

            waterAvailable: "",
            labourAvailable: "",
            budgetAvailable: "",

            cropHealth: "",
            diseaseStatus: ""

        });
    };


    /*
     * Load Farm State whenever selected farm changes.
     */
    useEffect(() => {

        const loadFarmState = async () => {

            if (!selectedFarmId) {

                setFarmState(null);
                return;
            }

            /*
             * Clear the previous farm's state immediately.
             * This prevents an old farmState.id from being
             * used while the new farm is loading.
             */
            setFarmState(null);

            try {

                setLoadingState(true);

                const data = await getFarmStateByFarmId(
                    selectedFarmId
                );

                /*
                 * If backend returns null,
                 * this farm does not have a state yet.
                 */
                if (!data) {

                    setFarmState(null);
                    resetFormForSelectedFarm();

                    return;
                }

                setFarmState(data);

                setFormData({

                    crop: data.crop || "",
                    growthStage: data.growthStage || "",

                    soilMoisture: data.soilMoisture ?? "",
                    soilPh: data.soilPh ?? "",

                    nitrogen: data.nitrogen ?? "",
                    phosphorus: data.phosphorus ?? "",
                    potassium: data.potassium ?? "",

                    temperature: data.temperature ?? "",
                    humidity: data.humidity ?? "",
                    rainfall: data.rainfall ?? "",

                    waterAvailable: data.waterAvailable ?? "",
                    labourAvailable: data.labourAvailable ?? "",
                    budgetAvailable: data.budgetAvailable ?? "",

                    cropHealth: data.cropHealth || "",
                    diseaseStatus: data.diseaseStatus || ""

                });

            } catch (error) {

                console.error(error);

                if (error.response?.status === 404) {

                    setFarmState(null);
                    resetFormForSelectedFarm();

                } else {

                    toast.error(
                        error.response?.data?.message ||
                        t("farmStateLoadFailed")
                    );

                }

            } finally {

                setLoadingState(false);

            }
        };

        loadFarmState();

    }, [selectedFarmId, farms, t]);


    /*
     * Handle farm selection.
     */
    const handleFarmChange = (event) => {

        const farmId = event.target.value;

        /*
         * Change selected farm.
         */
        setSelectedFarmId(farmId);

        setErrors({});

        /*
         * Clear previous farm state immediately.
         * This prevents accidental update of the
         * previous farm's Farm State.
         */
        setFarmState(null);

        /*
         * Clear the form while the new farm state
         * is being loaded.
         */
        const selectedFarm = farms.find(
            (farm) =>
                String(farm.id) === String(farmId)
        );

        setFormData({

            crop: selectedFarm?.crop || "",
            growthStage: selectedFarm?.growthStage || "",

            soilMoisture: "",
            soilPh: "",

            nitrogen: "",
            phosphorus: "",
            potassium: "",

            temperature: "",
            humidity: "",
            rainfall: "",

            waterAvailable: "",
            labourAvailable: "",
            budgetAvailable: "",

            cropHealth: "",
            diseaseStatus: ""

        });
    };


    /*
     * Handle form field changes.
     */
    const handleChange = (event) => {

        const { name, value } = event.target;

        setFormData((previous) => ({

            ...previous,
            [name]: value

        }));

        if (errors[name]) {

            setErrors((previous) => {

                const next = { ...previous };
                delete next[name];

                return next;
            });
        }

    };


    /*
     * Save Farm State.
     */
    const handleSave = async (event) => {

        event.preventDefault();

        if (!selectedFarmId) {

            toast.error(t("pleaseSelectFarm"));

            return;
        }

        const clientErrors = validateFarmStateForm(formData, t);

        if (Object.keys(clientErrors).length > 0) {

            setErrors(clientErrors);

            toast.error(t("fixFormErrors"));

            return;
        }

        setErrors({});

        try {

            setSaving(true);

            const farmStateData = {

                farm: {
                    id: Number(selectedFarmId)
                },

                crop:
                    formData.crop.trim() || null,

                growthStage:
                    formData.growthStage.trim() || null,

                soilMoisture:
                    formData.soilMoisture === ""
                        ? null
                        : Number(formData.soilMoisture),

                soilPh:
                    formData.soilPh === ""
                        ? null
                        : Number(formData.soilPh),

                /*
                 * Soil nutrients required by the
                 * Crop Recommendation ML model.
                 */
                nitrogen:
                    formData.nitrogen === ""
                        ? null
                        : Number(formData.nitrogen),

                phosphorus:
                    formData.phosphorus === ""
                        ? null
                        : Number(formData.phosphorus),

                potassium:
                    formData.potassium === ""
                        ? null
                        : Number(formData.potassium),

                temperature:
                    formData.temperature === ""
                        ? null
                        : Number(formData.temperature),

                humidity:
                    formData.humidity === ""
                        ? null
                        : Number(formData.humidity),

                rainfall:
                    formData.rainfall === ""
                        ? null
                        : Number(formData.rainfall),

                waterAvailable:
                    formData.waterAvailable === ""
                        ? null
                        : Number(formData.waterAvailable),

                labourAvailable:
                    formData.labourAvailable === ""
                        ? null
                        : Number(formData.labourAvailable),

                budgetAvailable:
                    formData.budgetAvailable === ""
                        ? null
                        : Number(formData.budgetAvailable),

                cropHealth:
                    formData.cropHealth.trim() || null,

                diseaseStatus:
                    formData.diseaseStatus.trim() || null

            };

            let response;

            /*
             * If a Farm State already exists for this
             * selected farm, update it.
             */
            if (farmState?.id) {

                response = await updateFarmState(
                    farmState.id,
                    farmStateData
                );

                toast.success(
                    t("farmStateUpdated")
                );

            } else {

                /*
                 * Otherwise create a new Farm State.
                 */
                response = await addFarmState(
                    farmStateData
                );

                toast.success(
                    t("farmStateCreated")
                );
            }

            /*
             * Store the newly created/updated state.
             */
            setFarmState(response);

        } catch (error) {

            console.error(error);

            /*
             * The backend returns per-field messages for
             * out-of-range values; show them next to the inputs.
             */
            if (error.response?.data?.errors) {

                setErrors(error.response.data.errors);
            }

            toast.error(
                error.response?.data?.message ||
                t("farmStateSaveFailed")
            );

        } finally {

            setSaving(false);

        }
    };


    const selectedFarm = farms.find(
        (farm) =>
            String(farm.id) === String(selectedFarmId)
    );


    return (

        <div className="farm-state-layout">

            <Sidebar />

            <main className="farm-state-main">

                {/* Header */}

                <header className="farm-state-header">

                    <div>

                        <h1>
                            {t("farmState")}
                        </h1>

                        <p>
                            {t("farmStateSubtitle")}
                        </p>

                    </div>

                    <LanguageSelector />

                </header>

                <section className="farm-state-hero">
    <img
        src={farmFields}
        alt="Green agricultural fields"
        className="farm-state-hero-image"
    />

    <div className="farm-state-hero-overlay" />

    <div className="farm-state-hero-content">
        <span>AGRISENSE · FIELD MONITORING</span>
        <h2>{t("farmState")}</h2>
        <p>{t("farmStateSubtitle")}</p>
    </div>
</section>


                <FarmListState
                    loading={loadingFarms}
                    error={farmsError}
                    farms={farms}
                    onRetry={reloadFarms}
                />


                {!loadingFarms && !farmsError && farms.length > 0 && (

                    <>

                        {/* Farm selector */}

                        <section className="farm-selector-card">

                            <div>

                                <label htmlFor="farm-select">

                                    {t("selectFarm")}

                                </label>

                                <p>

                                    {t("selectFarmDescription")}

                                </p>

                            </div>

                            <select
                                id="farm-select"
                                value={selectedFarmId}
                                onChange={handleFarmChange}
                            >

                                {farms.map((farm) => (

                                    <option
                                        key={farm.id}
                                        value={farm.id}
                                    >

                                        {farm.farmName} — {farm.location}

                                    </option>

                                ))}

                            </select>

                        </section>


                        {/* Selected Farm Summary */}

                        {selectedFarm && (

                            <section className="selected-farm-card">

                                <div className="selected-farm-icon">
                                    🌾
                                </div>

                                <div>

                                    <h2>
                                        {selectedFarm.farmName}
                                    </h2>

                                    <p>
                                        {selectedFarm.location}
                                    </p>

                                </div>


                                <div className="selected-farm-info">

                                    <span>
                                        {t("crop")}
                                    </span>

                                    <strong>
                                        {selectedFarm.crop || "—"}
                                    </strong>

                                </div>


                                <div className="selected-farm-info">

                                    <span>
                                        {t("area")}
                                    </span>

                                    <strong>
                                        {selectedFarm.area} acres
                                    </strong>

                                </div>


                                <div className="selected-farm-info">

                                    <span>
                                        {t("growthStage")}
                                    </span>

                                    <strong>
                                        {selectedFarm.growthStage || "—"}
                                    </strong>

                                </div>

                            </section>

                        )}


                        {/* Current saved Farm State */}

                        {!loadingState && (

                            <section
                                className="current-state-card"
                                data-testid="current-farm-state"
                            >

                                <h2>
                                    {t("currentFarmState")}
                                </h2>

                                {farmState ? (

                                    <div className="current-state-grid">

                                        {SUMMARY_FIELDS.map(([field, labelKey, unit]) => (

                                            <div
                                                key={field}
                                                className="current-state-item"
                                                data-field={field}
                                            >

                                                <span>
                                                    {t(labelKey)}
                                                </span>

                                                <strong>
                                                    {farmState[field] === null
                                                        || farmState[field] === undefined
                                                        || farmState[field] === ""
                                                        ? t("notRecorded")
                                                        : `${farmState[field]}${unit ? ` ${unit}` : ""}`}
                                                </strong>

                                            </div>

                                        ))}

                                    </div>

                                ) : (

                                    <p>
                                        {t("noFarmStateYet")}
                                    </p>

                                )}

                            </section>

                        )}


                        {/* Farm State Loading */}

                        {loadingState ? (

                            <section className="farm-state-message">

                                {t("loadingFarmState")}

                            </section>

                        ) : (

                            <form
                                className="farm-state-form"
                                onSubmit={handleSave}
                                noValidate
                            >

                                {/* Crop Information */}

                                <section className="state-section">

                                    <div className="state-section-header">

                                        <div className="state-section-icon">
                                            🌱
                                        </div>

                                        <div>

                                            <h2>
                                                {t("cropInformation")}
                                            </h2>

                                            <p>
                                                {t(
                                                    "cropInformationDescription"
                                                )}
                                            </p>

                                        </div>

                                    </div>


                                    <div className="state-grid">

                                        <div className="state-form-group">

                                            <label>
                                                {t("crop")}
                                            </label>

                                            <input
                                                type="text"
                                                name="crop"
                                                aria-invalid={Boolean(errors.crop)}
                                                value={formData.crop}
                                                onChange={handleChange}
                                                placeholder={t("cropExample")}
                                            />

                                            {errors.crop && (
                                                <small className="state-field-error">
                                                    {errors.crop}
                                                </small>
                                            )}

                                        </div>


                                        <div className="state-form-group">

                                            <label>
                                                {t("growthStage")}
                                            </label>

                                            <select
                                                name="growthStage"
                                                value={formData.growthStage}
                                                onChange={handleChange}
                                            >

                                                <option value="">
                                                    {t("selectStage")}
                                                </option>

                                                <option value="Seedling">
                                                    {t("seedling")}
                                                </option>

                                                <option value="Vegetative">
                                                    {t("vegetative")}
                                                </option>

                                                <option value="Flowering">
                                                    {t("flowering")}
                                                </option>

                                                <option value="Fruiting">
                                                    {t("fruiting")}
                                                </option>

                                                <option value="Maturity">
                                                    {t("maturity")}
                                                </option>

                                                <option value="Harvest">
                                                    {t("harvest")}
                                                </option>

                                            {formData.growthStage
                                                    && !GROWTH_STAGE_OPTIONS.includes(formData.growthStage) && (
                                                    <option value={formData.growthStage}>
                                                        {formData.growthStage}
                                                    </option>
                                                )}

                                            </select>

                                            {errors.growthStage && (
                                                <small className="state-field-error">
                                                    {errors.growthStage}
                                                </small>
                                            )}

                                        </div>

                                    </div>

                                </section>


                                {/* Soil Conditions */}

                                <section className="state-section">

                                    <div className="state-section-header">

                                        <div className="state-section-icon">
                                            🪨
                                        </div>

                                        <div>

                                            <h2>
                                                {t("soilConditions")}
                                            </h2>

                                            <p>
                                                {t(
                                                    "soilConditionsDescription"
                                                )}
                                            </p>

                                        </div>

                                    </div>


                                    <div className="state-grid state-grid-three">

                                        {/* Soil Moisture */}

                                        <div className="state-form-group">

                                            <label>
                                                {t("soilMoisture")}
                                            </label>

                                            <input
                                                type="number"
                                                name="soilMoisture"
                                                aria-invalid={Boolean(errors.soilMoisture)}
                                                value={
                                                    formData.soilMoisture
                                                }
                                                onChange={handleChange}
                                                step="0.01"
                                            />

                                            <small>
                                                {t("measuredMoisture")}
                                            </small>

                                            {errors.soilMoisture && (
                                                <small className="state-field-error">
                                                    {errors.soilMoisture}
                                                </small>
                                            )}

                                        </div>


                                        {/* Soil pH */}

                                        <div className="state-form-group">

                                            <label>
                                                {t("soilPh")}
                                            </label>

                                            <input
                                                type="number"
                                                name="soilPh"
                                                aria-invalid={Boolean(errors.soilPh)}
                                                value={formData.soilPh}
                                                onChange={handleChange}
                                                min="0"
                                                max="14"
                                                step="0.01"
                                            />

                                            <small>
                                                {t("phScale")}
                                            </small>

                                            {errors.soilPh && (
                                                <small className="state-field-error">
                                                    {errors.soilPh}
                                                </small>
                                            )}

                                        </div>


                                        {/* Nitrogen */}

                                        <div className="state-form-group">

                                            <label>
                                                {t("nitrogen")}
                                            </label>

                                            <input
                                                type="number"
                                                name="nitrogen"
                                                aria-invalid={Boolean(errors.nitrogen)}
                                                value={formData.nitrogen}
                                                onChange={handleChange}
                                                min="0"
                                                step="0.01"
                                                placeholder={t(
                                                    "nitrogenUnit"
                                                )}
                                            />

                                            {errors.nitrogen && (
                                                <small className="state-field-error">
                                                    {errors.nitrogen}
                                                </small>
                                            )}

                                        </div>


                                        {/* Phosphorus */}

                                        <div className="state-form-group">

                                            <label>
                                                {t("phosphorus")}
                                            </label>

                                            <input
                                                type="number"
                                                name="phosphorus"
                                                aria-invalid={Boolean(errors.phosphorus)}
                                                value={formData.phosphorus}
                                                onChange={handleChange}
                                                min="0"
                                                step="0.01"
                                                placeholder={t(
                                                    "phosphorusUnit"
                                                )}
                                            />

                                            {errors.phosphorus && (
                                                <small className="state-field-error">
                                                    {errors.phosphorus}
                                                </small>
                                            )}

                                        </div>


                                        {/* Potassium */}

                                        <div className="state-form-group">

                                            <label>
                                                {t("potassium")}
                                            </label>

                                            <input
                                                type="number"
                                                name="potassium"
                                                aria-invalid={Boolean(errors.potassium)}
                                                value={formData.potassium}
                                                onChange={handleChange}
                                                min="0"
                                                step="0.01"
                                                placeholder={t(
                                                    "potassiumUnit"
                                                )}
                                            />

                                            {errors.potassium && (
                                                <small className="state-field-error">
                                                    {errors.potassium}
                                                </small>
                                            )}

                                        </div>

                                    </div>

                                </section>


                                {/* Weather Conditions */}

                                <section className="state-section">

                                    <div className="state-section-header">

                                        <div className="state-section-icon">
                                            ☁️
                                        </div>

                                        <div>

                                            <h2>
                                                {t("weatherConditions")}
                                            </h2>

                                            <p>
                                                {t(
                                                    "weatherConditionsDescription"
                                                )}
                                            </p>

                                        </div>

                                    </div>


                                    <div className="state-grid state-grid-three">

                                        <div className="state-form-group">

                                            <label>
                                                {t("temperature")}
                                            </label>

                                            <input
                                                type="number"
                                                name="temperature"
                                                aria-invalid={Boolean(errors.temperature)}
                                                value={
                                                    formData.temperature
                                                }
                                                onChange={handleChange}
                                                placeholder={t(
                                                    "temperatureUnit"
                                                )}
                                                step="0.01"
                                            />

                                            {errors.temperature && (
                                                <small className="state-field-error">
                                                    {errors.temperature}
                                                </small>
                                            )}

                                        </div>


                                        <div className="state-form-group">

                                            <label>
                                                {t("humidity")}
                                            </label>

                                            <input
                                                type="number"
                                                name="humidity"
                                                aria-invalid={Boolean(errors.humidity)}
                                                value={
                                                    formData.humidity
                                                }
                                                onChange={handleChange}
                                                placeholder={t(
                                                    "humidityUnit"
                                                )}
                                                min="0"
                                                max="100"
                                                step="0.01"
                                            />

                                            {errors.humidity && (
                                                <small className="state-field-error">
                                                    {errors.humidity}
                                                </small>
                                            )}

                                        </div>


                                        <div className="state-form-group">

                                            <label>
                                                {t("rainfall")}
                                            </label>

                                            <input
                                                type="number"
                                                name="rainfall"
                                                aria-invalid={Boolean(errors.rainfall)}
                                                value={
                                                    formData.rainfall
                                                }
                                                onChange={handleChange}
                                                placeholder={t(
                                                    "rainfallUnit"
                                                )}
                                                min="0"
                                                step="0.01"
                                            />

                                            {errors.rainfall && (
                                                <small className="state-field-error">
                                                    {errors.rainfall}
                                                </small>
                                            )}

                                        </div>

                                    </div>

                                </section>


                                {/* Available Resources */}

                                <section className="state-section">

                                    <div className="state-section-header">

                                        <div className="state-section-icon">
                                            💧
                                        </div>

                                        <div>

                                            <h2>
                                                {t("availableResources")}
                                            </h2>

                                            <p>
                                                {t(
                                                    "availableResourcesDescription"
                                                )}
                                            </p>

                                        </div>

                                    </div>


                                    <div className="state-grid state-grid-three">

                                        <div className="state-form-group">

                                            <label>
                                                {t("waterAvailable")}
                                            </label>

                                            <input
                                                type="number"
                                                name="waterAvailable"
                                                aria-invalid={Boolean(errors.waterAvailable)}
                                                value={
                                                    formData.waterAvailable
                                                }
                                                onChange={handleChange}
                                                placeholder={t(
                                                    "waterQuantity"
                                                )}
                                                min="0"
                                                step="0.01"
                                            />

                                            {errors.waterAvailable && (
                                                <small className="state-field-error">
                                                    {errors.waterAvailable}
                                                </small>
                                            )}

                                        </div>


                                        <div className="state-form-group">

                                            <label>
                                                {t("labourAvailable")}
                                            </label>

                                            <input
                                                type="number"
                                                name="labourAvailable"
                                                aria-invalid={Boolean(errors.labourAvailable)}
                                                value={
                                                    formData.labourAvailable
                                                }
                                                onChange={handleChange}
                                                placeholder={t(
                                                    "workersCount"
                                                )}
                                                min="0"
                                                step="1"
                                            />

                                            {errors.labourAvailable && (
                                                <small className="state-field-error">
                                                    {errors.labourAvailable}
                                                </small>
                                            )}

                                        </div>


                                        <div className="state-form-group">

                                            <label>
                                                {t("budgetAvailable")}
                                            </label>

                                            <input
                                                type="number"
                                                name="budgetAvailable"
                                                aria-invalid={Boolean(errors.budgetAvailable)}
                                                value={
                                                    formData.budgetAvailable
                                                }
                                                onChange={handleChange}
                                                placeholder={t(
                                                    "availableBudget"
                                                )}
                                                min="0"
                                                step="0.01"
                                            />

                                            {errors.budgetAvailable && (
                                                <small className="state-field-error">
                                                    {errors.budgetAvailable}
                                                </small>
                                            )}

                                        </div>

                                    </div>

                                </section>


                                {/* Crop Health */}

                                <section className="state-section">

                                    <div className="state-section-header">

                                        <div className="state-section-icon">
                                            ❤️
                                        </div>

                                        <div>

                                            <h2>
                                                {t("cropHealth")}
                                            </h2>

                                            <p>
                                                {t(
                                                    "cropHealthDescription"
                                                )}
                                            </p>

                                        </div>

                                    </div>


                                    <div className="state-grid">

                                        <div className="state-form-group">

                                            <label>
                                                {t("cropHealth")}
                                            </label>

                                            <select
                                                name="cropHealth"
                                                value={
                                                    formData.cropHealth
                                                }
                                                onChange={handleChange}
                                            >

                                                <option value="">
                                                    {t("selectCondition")}
                                                </option>

                                                <option value="Healthy">
                                                    {t("healthy")}
                                                </option>

                                                <option value="Moderate">
                                                    {t("moderate")}
                                                </option>

                                                <option value="Poor">
                                                    {t("poor")}
                                                </option>

                                                <option value="Not checked">
                                                    {t("unknown")}
                                                </option>

                                            {formData.cropHealth
                                                    && !CROP_HEALTH_OPTIONS.includes(formData.cropHealth) && (
                                                    <option value={formData.cropHealth}>
                                                        {formData.cropHealth}
                                                    </option>
                                                )}

                                            </select>

                                            {errors.cropHealth && (
                                                <small className="state-field-error">
                                                    {errors.cropHealth}
                                                </small>
                                            )}

                                        </div>


                                        <div className="state-form-group">

                                            <label>
                                                {t("diseaseStatus")}
                                            </label>

                                            <select
                                                name="diseaseStatus"
                                                value={
                                                    formData.diseaseStatus
                                                }
                                                onChange={handleChange}
                                            >

                                                <option value="">
                                                    {t("selectStatus")}
                                                </option>

                                                <option value="No known disease">
                                                    {t("noKnownDisease")}
                                                </option>

                                                <option value="Suspected">
                                                    {t("suspected")}
                                                </option>

                                                <option value="Confirmed">
                                                    {t("confirmed")}
                                                </option>

                                                <option value="Not checked">
                                                    {t("unknown")}
                                                </option>

                                            {formData.diseaseStatus
                                                    && !DISEASE_STATUS_OPTIONS.includes(formData.diseaseStatus) && (
                                                    <option value={formData.diseaseStatus}>
                                                        {formData.diseaseStatus}
                                                    </option>
                                                )}

                                            </select>

                                            {errors.diseaseStatus && (
                                                <small className="state-field-error">
                                                    {errors.diseaseStatus}
                                                </small>
                                            )}

                                        </div>

                                    </div>

                                </section>


                                {/* Save */}

                                <div className="farm-state-actions">

                                    <button
                                        type="submit"
                                        className="save-state-button"
                                        disabled={
                                            saving || loadingState
                                        }
                                    >

                                        {saving
                                            ? t("saving")
                                            : farmState
                                                ? t("updateFarmState")
                                                : t("createFarmState")}

                                    </button>

                                </div>

                            </form>

                        )}

                    </>

                )}

            </main>

        </div>

    );
}

export default FarmState;