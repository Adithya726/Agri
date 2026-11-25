
import { useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import {
    Plus,
    BookOpen,
    RefreshCw,
    Save,
    X,
} from "lucide-react";

import Sidebar from "../../components/Sidebar/Sidebar";
import PageState from "../../components/PageState/PageState";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";
import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";
import useFarmerFarms from "../../hooks/useFarmerFarms";
import { getSeasonPlan } from "../../services/actionPlanService";
import { DueLine, DUE_KEYS, Requirements } from "../../components/SeasonPlan/SeasonPlan";
import {
    getAllAgriculturalActions,
    addAgriculturalAction,
} from "../../services/agriculturalActionService";

import "./AgriculturalActions.css";

const num = (value, digits = 1) =>
    Number(value).toLocaleString("en-IN", { maximumFractionDigits: digits });

const range = (min, max, digits = 1) =>
    min === max || max == null ? num(min, digits) : `${num(min, digits)} – ${num(max, digits)}`;

/*
 * What each step means for the selected farm, read from the farm's
 * season plan (which the backend re-decides from the latest Farm State):
 * the stage it belongs to (done / now / later), today's decision for
 * current-stage steps, its due date and its sourced needs for the
 * farm's area.
 */
const farmViewOf = (plan) => {
    const view = new Map();
    if (!plan) {
        return view;
    }
    (plan.stages || []).forEach((stage) => {
        (stage.steps || []).forEach((step) => {
            view.set(step.actionId, {
                stage: stage.stage,
                stageStatus: stage.status,
                completed: step.completed,
                due: step.due,
                requirements: step.requirements
            });
        });
    });
    [...(plan.doNow || []), ...(plan.onHold || [])].forEach((step) => {
        const entry = view.get(step.actionId) || {};
        view.set(step.actionId, {
            ...entry,
            decision: step.decision,
            order: step.order,
            onHold: (plan.onHold || []).includes(step),
            due: step.due || entry.due,
            requirements: step.requirements || entry.requirements
        });
    });
    return view;
};

function FarmFit({ view, t }) {
    if (!view) {
        return <span className="farm-fit farm-fit-other">{t("fitOtherCrop")}</span>;
    }
    let label;
    let kind;
    if (view.completed) {
        label = t("fitDone");
        kind = "done";
    } else if (view.decision) {
        label = view.onHold ? `${t("fitOnHold")}: ${view.decision}` : `${t("fitDoNow")} #${view.order}`;
        kind = view.onHold ? "hold" : "now";
    } else if (view.stageStatus === "DONE") {
        label = t("fitEarlierStage");
        kind = "past";
    } else {
        label = `${t("fitLaterStage")} (${view.stage})`;
        kind = "later";
    }
    return (
        <div className="farm-fit-cell">
            <span className={`farm-fit farm-fit-${kind}`}>{label}</span>
            {view.due?.from && !view.completed && (
                <small className="farm-fit-due">
                    {t(DUE_KEYS[view.due.status])}: {view.due.from}
                    {view.due.to && view.due.to !== view.due.from ? ` → ${view.due.to}` : ""}
                </small>
            )}
        </div>
    );
}

function FarmNeeds({ req, t }) {
    const [open, setOpen] = useState(false);
    if (!req) {
        return <small className="action-plan-muted">{t("reqNotSourced")}</small>;
    }
    const cost = req.cost || {};
    const labour = req.labour || {};
    const water = req.water || {};
    return (
        <div className="farm-needs">
            <small>
                {t("reqCost")}: {cost.max > 0 ? `₹${range(cost.min, cost.max, 0)}` : t("reqNotSourced")}
                <br />
                {t("reqWorkers")}: {labour.sourced
                    ? `${range(labour.personDaysMin, labour.personDaysMax)} ${t("reqPersonDays")}`
                    : t("reqNotSourced")}
                <br />
                {t("reqWater")}: {!water.sourced
                    ? t("reqNotSourced")
                    : water.includedIn
                        ? t("reqCountedInShort")
                        : `${range(water.m3Min, water.m3Max)} m³${water.label ? ` (${water.label})` : ""}`}
            </small>
            <button type="button" className="farm-needs-toggle" onClick={() => setOpen((v) => !v)}>
                {open ? t("reqHideDetails") : t("reqShowDetails")}
            </button>
            {open && <Requirements req={req} t={t} />}
        </div>
    );
}

const INITIAL_FORM = {
    actionName: "",
    category: "",
    description: "",
    requiredWater: "",
    requiredLabour: "",
    requiredBudget: "",
    applicableCrop: "",
    applicableGrowthStage: "",
    evidenceStatus: "",
    sourceReference: "",
    active: true,
};

function AgriculturalActions() {
    const { t } = useLanguage();

    const { farmer } = useAuth();
    // Actions are shared reference data; only an ADMIN may add them.
    const isAdmin = farmer?.role === "ADMIN";

    const [actions, setActions] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadFailed, setLoadFailed] = useState(false);
    const [saving, setSaving] = useState(false);
    const [showForm, setShowForm] = useState(false);
    const [formData, setFormData] = useState({ ...INITIAL_FORM });

    // The farm whose Farm State the table is read against.
    const { farms, selectedFarmId, setSelectedFarmId } = useFarmerFarms(farmer?.userId);
    const [seasonPlan, setSeasonPlan] = useState(null);
    const [loadingFarmView, setLoadingFarmView] = useState(false);
    const [onlyThisFarm, setOnlyThisFarm] = useState(true);

    const loadFarmView = async (farmId) => {
        if (!farmId) {
            setSeasonPlan(null);
            return;
        }
        try {
            setLoadingFarmView(true);
            setSeasonPlan(await getSeasonPlan(farmId));
        } catch (error) {
            console.error("Failed to load the farm's plan:", error);
            setSeasonPlan(null);
            toast.error(t("seasonPlanLoadFailed"));
        } finally {
            setLoadingFarmView(false);
        }
    };

    useEffect(() => {
        loadFarmView(selectedFarmId);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selectedFarmId]);

    const farmView = useMemo(() => farmViewOf(seasonPlan), [seasonPlan]);

    const farmCrop = seasonPlan?.crop ? String(seasonPlan.crop).toLowerCase() : null;

    const shownActions = useMemo(() => {
        if (!seasonPlan || !onlyThisFarm || !farmCrop) {
            return actions;
        }
        return actions.filter((a) => String(a.applicableCrop || "").toLowerCase() === farmCrop);
    }, [actions, seasonPlan, onlyThisFarm, farmCrop]);

    const refreshAll = () => {
        loadActions();
        loadFarmView(selectedFarmId);
    };

    useEffect(() => {
        loadActions();
    }, []);

    const loadActions = async () => {
        try {
            setLoading(true);
            setLoadFailed(false);
            const data = await getAllAgriculturalActions();
            setActions(Array.isArray(data) ? data : []);
        } catch (error) {
            console.error("Failed to load agricultural actions:", error);
            setLoadFailed(true);
            toast.error(t("agriculturalActionsLoadFailed"));
        } finally {
            setLoading(false);
        }
    };

    const handleChange = (event) => {
        const { name, value, type, checked } = event.target;

        setFormData((previous) => ({
            ...previous,
            [name]: type === "checkbox" ? checked : value,
        }));
    };

    const handleReset = () => {
        setFormData({ ...INITIAL_FORM });
    };

    const parseOptionalNumber = (value) => {
        if (value === "" || value === null || value === undefined) {
            return null;
        }

        const number = Number(value);

        return Number.isFinite(number) ? number : NaN;
    };

    const handleSubmit = async (event) => {
        event.preventDefault();

        if (!formData.actionName.trim()) {
            toast.error(t("agriculturalActionNameRequired"));
            return;
        }

        if (!formData.applicableCrop.trim()) {
            toast.error(t("agriculturalActionCropRequired"));
            return;
        }

        if (!formData.applicableGrowthStage.trim()) {
            toast.error(t("agriculturalActionGrowthStageRequired"));
            return;
        }

        const requiredWater = parseOptionalNumber(formData.requiredWater);
        const requiredLabour = parseOptionalNumber(formData.requiredLabour);
        const requiredBudget = parseOptionalNumber(formData.requiredBudget);

        const requirements = [
            ["Water", requiredWater],
            ["Labour", requiredLabour],
            ["Budget", requiredBudget],
        ];

        for (const [label, value] of requirements) {
            if (Number.isNaN(value)) {
                toast.error(`${label} requirement must be a valid number.`);
                return;
            }

            if (value !== null && value < 0) {
                toast.error(`${label} requirement cannot be negative.`);
                return;
            }
        }

        // The current backend entity uses Integer for requiredLabour.
        if (
            requiredLabour !== null &&
            !Number.isInteger(requiredLabour)
        ) {
            toast.error("Labour requirement must be a whole number.");
            return;
        }

        const payload = {
            actionName: formData.actionName.trim(),
            category: formData.category.trim(),
            description: formData.description.trim(),
            requiredWater,
            requiredLabour,
            requiredBudget,
            applicableCrop: formData.applicableCrop.trim(),
            applicableGrowthStage: formData.applicableGrowthStage.trim(),
            evidenceStatus: formData.evidenceStatus.trim(),
            sourceReference: formData.sourceReference.trim(),
            active: formData.active,
        };

        try {
            setSaving(true);

            await addAgriculturalAction(payload);

            toast.success(t("agriculturalActionAdded"));
            handleReset();
            setShowForm(false);
            await loadActions();
        } catch (error) {
            console.error("Failed to add agricultural action:", error);

            toast.error(
                error.response?.data?.message ||
                t("agriculturalActionAddFailed")
            );
        } finally {
            setSaving(false);
        }
    };

    const renderOptionalValue = (value, unit) => {
        if (value === null || value === undefined || value === "") {
            return "As written in the step";
        }

        return `${value} ${unit}`;
    };

    return (
        <div className="agricultural-actions-layout">
            <Sidebar />

            <main className="agricultural-actions-main">
                <div className="agricultural-actions-page">
                    <div className="agricultural-actions-container">
                        {/* Page header */}
                        <div className="agricultural-actions-header">
                            <div>
                                <div className="page-title-row">
                                    <BookOpen size={30} />
                                    <h1>{t("agriculturalActionsTitle")}</h1>
                                </div>

                                <p>
                                    {t("agriculturalActionsSubtitle")}
                                </p>
                            </div>

                            <div className="header-actions">
                                <LanguageSelector />

                                <button
                                    type="button"
                                    className="secondary-action-button"
                                    onClick={refreshAll}
                                    disabled={loading || loadingFarmView}
                                >
                                    <RefreshCw
                                        size={17}
                                        className={loading || loadingFarmView ? "spin" : ""}
                                    />
                                    {t("refresh")}
                                </button>

                                {isAdmin && (
                                <button
                                    type="button"
                                    className="primary-action-button"
                                    onClick={() => setShowForm((value) => !value)}
                                    disabled={saving}
                                >
                                    {showForm ? (
                                        <X size={18} />
                                    ) : (
                                        <Plus size={18} />
                                    )}

                                    {showForm
                                        ? t("cancel")
                                        : t("addAgriculturalAction")}
                                </button>
                                )}
                            </div>
                        </div>

                        {/* Knowledge base information */}
                        <div className="knowledge-info-box">
                            <BookOpen size={21} />

                            <div>
                                <strong>
                                    {t("agriculturalActionsKnowledgeBase")}
                                </strong>

                                <p>
                                    {t("agriculturalActionsKnowledgeDescription")}
                                </p>

                                {!isAdmin && (
                                    <p>{t("agriculturalActionsAdminOnly")}</p>
                                )}
                            </div>
                        </div>

                        {/* Add agricultural action */}
                        {isAdmin && showForm && (
                            <div className="action-form-card">
                                <div className="section-heading">
                                    <h2>{t("addAgriculturalAction")}</h2>
                                    <p>
                                        {t("agriculturalActionFormDescription")}
                                    </p>
                                </div>

                                <form onSubmit={handleSubmit}>
                                    {/* Basic information */}
                                    <div className="form-section">
                                        <h3>{t("actionBasicInformation")}</h3>

                                        <div className="form-grid">
                                            <div className="form-group">
                                                <label htmlFor="actionName">
                                                    {t("actionName")}
                                                </label>

                                                <input
                                                    id="actionName"
                                                    type="text"
                                                    name="actionName"
                                                    value={formData.actionName}
                                                    onChange={handleChange}
                                                    placeholder={t("enterActionName")}
                                                    required
                                                />
                                            </div>

                                            <div className="form-group">
                                                <label htmlFor="category">
                                                    {t("actionCategory")}
                                                </label>

                                                <input
                                                    id="category"
                                                    type="text"
                                                    name="category"
                                                    value={formData.category}
                                                    onChange={handleChange}
                                                    placeholder={t("enterActionCategory")}
                                                />
                                            </div>

                                            <div className="form-group full-width">
                                                <label htmlFor="description">
                                                    {t("actionDescription")}
                                                </label>

                                                <textarea
                                                    id="description"
                                                    name="description"
                                                    value={formData.description}
                                                    onChange={handleChange}
                                                    placeholder={t("enterActionDescription")}
                                                    rows={4}
                                                />
                                            </div>
                                        </div>
                                    </div>

                                    {/* Applicability */}
                                    <div className="form-section">
                                        <h3>{t("actionApplicability")}</h3>

                                        <div className="form-grid">
                                            <div className="form-group">
                                                <label htmlFor="applicableCrop">
                                                    {t("applicableCrop")}
                                                </label>

                                                <input
                                                    id="applicableCrop"
                                                    type="text"
                                                    name="applicableCrop"
                                                    value={formData.applicableCrop}
                                                    onChange={handleChange}
                                                    placeholder={t("enterApplicableCrop")}
                                                    required
                                                />
                                            </div>

                                            <div className="form-group">
                                                <label htmlFor="applicableGrowthStage">
                                                    {t("applicableGrowthStage")}
                                                </label>

                                                <input
                                                    id="applicableGrowthStage"
                                                    type="text"
                                                    name="applicableGrowthStage"
                                                    value={formData.applicableGrowthStage}
                                                    onChange={handleChange}
                                                    placeholder={t("enterGrowthStage")}
                                                    required
                                                />
                                            </div>
                                        </div>
                                    </div>

                                    {/* Resource requirements */}
                                    <div className="form-section">
                                        <h3>
                                            {t("actionResourceRequirements")}
                                        </h3>

                                        <p className="form-note">
                                            Enter requirements only when
                                            supported by a verified source or
                                            a documented project assumption.
                                            Leave unavailable values empty.
                                            Empty values show "As written in the
                                            step"; they are not treated as zero.
                                        </p>

                                        <div className="form-grid">
                                            <div className="form-group">
                                                <label htmlFor="requiredWater">
                                                    Water required (L)
                                                </label>

                                                <input
                                                    id="requiredWater"
                                                    type="number"
                                                    name="requiredWater"
                                                    value={formData.requiredWater}
                                                    onChange={handleChange}
                                                    min="0"
                                                    step="any"
                                                    placeholder="Leave blank if the source gives none"
                                                />

                                                <small>
                                                    The water unit and
                                                    measurement period must
                                                    match Farm State.
                                                </small>
                                            </div>

                                            <div className="form-group">
                                                <label htmlFor="requiredLabour">
                                                    Labour required
                                                    (person-days)
                                                </label>

                                                <input
                                                    id="requiredLabour"
                                                    type="number"
                                                    name="requiredLabour"
                                                    value={formData.requiredLabour}
                                                    onChange={handleChange}
                                                    min="0"
                                                    step="1"
                                                    placeholder="Leave blank if the source gives none"
                                                />

                                                <small>
                                                    Current backend supports
                                                    whole person-day values.
                                                </small>
                                            </div>

                                            <div className="form-group">
                                                <label htmlFor="requiredBudget">
                                                    Budget required (₹)
                                                </label>

                                                <input
                                                    id="requiredBudget"
                                                    type="number"
                                                    name="requiredBudget"
                                                    value={formData.requiredBudget}
                                                    onChange={handleChange}
                                                    min="0"
                                                    step="any"
                                                    placeholder="Leave blank if the source gives none"
                                                />

                                                <small>
                                                    Estimated expenditure for
                                                    the defined action scope.
                                                </small>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Evidence */}
                                    <div className="form-section">
                                        <h3>{t("actionEvidence")}</h3>

                                        <div className="form-grid">
                                            <div className="form-group">
                                                <label htmlFor="evidenceStatus">
                                                    {t("evidenceStatus")}
                                                </label>

                                                <input
                                                    id="evidenceStatus"
                                                    type="text"
                                                    name="evidenceStatus"
                                                    value={formData.evidenceStatus}
                                                    onChange={handleChange}
                                                    placeholder={t("selectEvidenceStatus")}
                                                />
                                            </div>

                                            <div className="form-group">
                                                <label htmlFor="sourceReference">
                                                    {t("sourceReference")}
                                                </label>

                                                <input
                                                    id="sourceReference"
                                                    type="text"
                                                    name="sourceReference"
                                                    value={formData.sourceReference}
                                                    onChange={handleChange}
                                                    placeholder={t("enterSourceReference")}
                                                />

                                                <small>
                                                    Use an actual source you
                                                    have checked. Do not invent
                                                    a citation.
                                                </small>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Active status */}
                                    <div className="form-section">
                                        <label className="checkbox-row">
                                            <input
                                                type="checkbox"
                                                name="active"
                                                checked={formData.active}
                                                onChange={handleChange}
                                            />

                                            <span>{t("actionActive")}</span>
                                        </label>
                                    </div>

                                    {/* Form actions */}
                                    <div className="form-actions">
                                        <button
                                            type="button"
                                            className="secondary-action-button"
                                            onClick={() => {
                                                handleReset();
                                                setShowForm(false);
                                            }}
                                            disabled={saving}
                                        >
                                            <X size={17} />
                                            {t("cancel")}
                                        </button>

                                        <button
                                            type="submit"
                                            className="primary-action-button"
                                            disabled={saving}
                                        >
                                            <Save size={17} />
                                            {saving
                                                ? t("saving")
                                                : t("save")}
                                        </button>
                                    </div>
                                </form>
                            </div>
                        )}

                        {/* Existing agricultural actions */}
                        <div className="actions-card">
                            <div className="section-heading">
                                <h2>
                                    {t("agriculturalActionsList")}
                                </h2>

                                <p>
                                    {t("agriculturalActionsListDescription")}
                                </p>
                            </div>

                            {loading ? (
                                <div className="empty-state">
                                    <RefreshCw className="spin" size={25} />
                                    <p>{t("loading")}</p>
                                </div>
                            ) : loadFailed ? (
                                <PageState
                                    variant="error"
                                    title={t("agriculturalActionsLoadFailed")}
                                    message={t("loadErrorHint")}
                                    onRetry={loadActions}
                                />
                            ) : null}

                            {!loading && !loadFailed && farms.length > 0 && (
                                <div className="farm-view-bar">
                                    <label htmlFor="actions-farm">{t("actionsForFarm")}</label>
                                    <select
                                        id="actions-farm"
                                        value={selectedFarmId}
                                        onChange={(event) => setSelectedFarmId(event.target.value)}
                                    >
                                        {farms.map((farm) => (
                                            <option key={farm.id} value={farm.id}>
                                                {farm.farmName}
                                            </option>
                                        ))}
                                    </select>
                                    <label className="farm-view-only">
                                        <input
                                            type="checkbox"
                                            checked={onlyThisFarm}
                                            onChange={(event) => setOnlyThisFarm(event.target.checked)}
                                        />
                                        {t("actionsOnlyThisCrop")}
                                    </label>
                                    {seasonPlan && (
                                        <span className="farm-view-summary">
                                            {seasonPlan.crop || t("cropNotSet")}
                                            {seasonPlan.growthStage ? ` · ${seasonPlan.growthStage}` : ""}
                                            {seasonPlan.cropDays?.daysAfterTransplanting != null
                                                ? ` · ${t("dayAfterTransplanting").replace("{day}", seasonPlan.cropDays.daysAfterTransplanting)}`
                                                : seasonPlan.cropDays?.daysAfterSowing != null
                                                    ? ` · ${t("dayAfterSowing").replace("{day}", seasonPlan.cropDays.daysAfterSowing)}`
                                                    : ""}
                                        </span>
                                    )}
                                    {seasonPlan?.headline && (
                                        <p className="farm-view-headline">{seasonPlan.headline}</p>
                                    )}
                                </div>
                            )}

                            {loading || loadFailed ? null : shownActions.length === 0 ? (
                                <div className="empty-state">
                                    <BookOpen size={35} />
                                    <p>{t("noAgriculturalActions")}</p>
                                </div>
                            ) : (
                                <div className="actions-table-wrapper">
                                    <table className="actions-table">
                                        <thead>
                                            <tr>
                                                <th>{t("actionName")}</th>
                                                <th>{t("actionCategory")}</th>
                                                <th>{t("applicableCrop")}</th>
                                                <th>{t("applicableGrowthStage")}</th>
                                                {seasonPlan && <th>{t("actionsForThisFarm")}</th>}
                                                <th>{seasonPlan ? t("actionsNeedsForFarm") : t("actionsResourceRequirements")}</th>
                                                <th>{t("evidenceStatus")}</th>
                                                <th>{t("status")}</th>
                                            </tr>
                                        </thead>

                                        <tbody>
                                            {shownActions.map((action) => {
                                                const view = farmView.get(action.id);
                                                return (
                                                <tr
                                                    key={action.id}
                                                    className={view?.decision && !view.onHold ? "farm-row-now" : ""}
                                                >
                                                    <td>
                                                        <div className="action-name-cell">
                                                            <strong>
                                                                {action.actionName}
                                                            </strong>

                                                            {action.description && (
                                                                <small>
                                                                    {action.description}
                                                                </small>
                                                            )}
                                                        </div>
                                                    </td>

                                                    <td>
                                                        {action.category || "—"}
                                                    </td>

                                                    <td>
                                                        {action.applicableCrop || "—"}
                                                    </td>

                                                    <td>
                                                        {action.applicableGrowthStage || "—"}
                                                    </td>

                                                    {seasonPlan && (
                                                        <td>
                                                            <FarmFit view={view} t={t} />
                                                            {view?.due && !view.completed && view.stageStatus !== "DONE" && (
                                                                <DueLine due={view.due} t={t} />
                                                            )}
                                                        </td>
                                                    )}

                                                    {seasonPlan ? (
                                                    <td>
                                                        <FarmNeeds req={view?.requirements} t={t} />
                                                    </td>
                                                    ) : (
                                                    <td>
                                                        <small>
                                                            Water:{" "}
                                                            {renderOptionalValue(
                                                                action.requiredWater,
                                                                "L"
                                                            )}
                                                            <br />
                                                            Labour:{" "}
                                                            {renderOptionalValue(
                                                                action.requiredLabour,
                                                                "person-days"
                                                            )}
                                                            <br />
                                                            Budget:{" "}
                                                            {renderOptionalValue(
                                                                action.requiredBudget,
                                                                "₹"
                                                            )}
                                                        </small>
                                                    </td>
                                                    )}

                                                    <td>
                                                        <span className="evidence-badge">
                                                            {action.evidenceStatus || "—"}
                                                        </span>
                                                    </td>

                                                    <td>
                                                        <span
                                                            className={
                                                                action.active
                                                                    ? "status-badge active"
                                                                    : "status-badge inactive"
                                                            }
                                                        >
                                                            {action.active
                                                                ? t("active")
                                                                : t("inactive")}
                                                        </span>
                                                    </td>
                                                </tr>
                                                );
                                            })}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </main>
        </div>
    );
}

export default AgriculturalActions;
