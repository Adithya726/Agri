import { useCallback, useEffect, useState } from "react";

import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";

import { getFarmsByFarmerId } from "../../services/farmService";

import {
    generateActionPlan,
    getActionPlanById,
    getActionPlansByFarmId,
    reviewActionPlan
} from "../../services/actionPlanService";

import "./ActionPlan.css";

/*
 * The backend returns error details as plain text
 * (or occasionally as an object with a message).
 */
const getErrorMessage = (error, fallback) => {

    const data = error?.response?.data;

    if (typeof data === "string" && data.trim()) {
        return data;
    }

    return data?.message || fallback;
};

const formatDateTime = (value) => {

    if (!value) {
        return "—";
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime())
        ? value
        : date.toLocaleString();
};

const statusClass = (status) =>
    String(status || "unknown").toLowerCase();

function ActionPlan() {

    const { farmer } = useAuth();
    const { t } = useLanguage();

    const [farms, setFarms] = useState([]);
    const [selectedFarmId, setSelectedFarmId] = useState("");
    const [loadingFarms, setLoadingFarms] = useState(true);

    const [plans, setPlans] = useState([]);
    const [loadingPlans, setLoadingPlans] = useState(false);

    const [currentPlan, setCurrentPlan] = useState(null);
    const [loadingPlan, setLoadingPlan] = useState(false);

    const [generating, setGenerating] = useState(false);
    const [reviewing, setReviewing] = useState(false);


    /* Load the logged-in farmer's farms */

    useEffect(() => {

        const loadFarms = async () => {

            if (!farmer?.userId) {
                setFarms([]);
                setSelectedFarmId("");
                setLoadingFarms(false);
                return;
            }

            try {

                setLoadingFarms(true);

                const data = await getFarmsByFarmerId(farmer.userId);
                const farmerFarms = data || [];

                setFarms(farmerFarms);
                setSelectedFarmId(
                    farmerFarms.length > 0
                        ? String(farmerFarms[0].id)
                        : ""
                );

            } catch (error) {

                console.error(error);
                toast.error(getErrorMessage(error, t("farmsLoadFailed")));
                setFarms([]);
                setSelectedFarmId("");

            } finally {
                setLoadingFarms(false);
            }
        };

        loadFarms();

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [farmer?.userId]);


    /* Load previous plans of the selected farm */

    const loadPlans = useCallback(async (farmId) => {

        if (!farmId) {
            setPlans([]);
            return [];
        }

        try {

            setLoadingPlans(true);

            const data = await getActionPlansByFarmId(farmId);
            const farmPlans = Array.isArray(data) ? data : [];

            setPlans(farmPlans);

            return farmPlans;

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionPlansLoadFailed")));
            setPlans([]);

            return [];

        } finally {
            setLoadingPlans(false);
        }

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);


    useEffect(() => {

        setCurrentPlan(null);

        if (!selectedFarmId) {
            setPlans([]);
            return;
        }

        loadPlans(selectedFarmId).then((farmPlans) => {

            if (farmPlans.length > 0) {

                const latest = [...farmPlans].sort(
                    (a, b) =>
                        new Date(b.createdAt) - new Date(a.createdAt)
                )[0];

                setCurrentPlan(latest);
            }
        });

    }, [selectedFarmId, loadPlans]);


    const handleGenerate = async () => {

        if (!selectedFarmId) {
            toast.error(t("selectFarmFirst"));
            return;
        }

        try {

            setGenerating(true);

            const plan = await generateActionPlan(selectedFarmId);

            setCurrentPlan(plan);
            toast.success(t("actionPlanGenerated"));

            await loadPlans(selectedFarmId);

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionPlanGenerateFailed")));

        } finally {
            setGenerating(false);
        }
    };


    const handleOpenPlan = async (planId) => {

        try {

            setLoadingPlan(true);

            const plan = await getActionPlanById(planId);

            setCurrentPlan(plan);

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionPlanLoadFailed")));

        } finally {
            setLoadingPlan(false);
        }
    };


    const handleReview = async (decision) => {

        if (!currentPlan?.id) {
            return;
        }

        try {

            setReviewing(true);

            const plan = await reviewActionPlan(currentPlan.id, decision);

            setCurrentPlan(plan);

            toast.success(
                decision === "ACCEPTED"
                    ? t("actionPlanAcceptedToast")
                    : t("actionPlanRejectedToast")
            );

            await loadPlans(selectedFarmId);

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionPlanReviewFailed")));

        } finally {
            setReviewing(false);
        }
    };


    const sortedItems = [...(currentPlan?.items || [])].sort(
        (a, b) => (a.sequenceOrder ?? 0) - (b.sequenceOrder ?? 0)
    );

    const sortedPlans = [...plans].sort(
        (a, b) => new Date(b.createdAt) - new Date(a.createdAt)
    );

    const isDraft = currentPlan?.status === "DRAFT";


    return (

        <div className="action-plan-layout">

            <Sidebar />

            <main className="action-plan-main">

                <header className="action-plan-header">

                    <div>
                        <h1>{t("actionPlan")}</h1>
                        <p>{t("actionPlanDescription")}</p>
                    </div>

                    <LanguageSelector />

                </header>


                {loadingFarms && (
                    <section className="action-plan-card action-plan-empty">
                        {t("loadingYourFarms")}
                    </section>
                )}


                {!loadingFarms && farms.length === 0 && (
                    <section className="action-plan-card action-plan-empty">
                        <h3>{t("noFarms")}</h3>
                        <p>{t("noFarmsDescription")}</p>
                    </section>
                )}


                {!loadingFarms && farms.length > 0 && (

                    <>

                        <section className="action-plan-card action-plan-selector">

                            <div>
                                <label htmlFor="action-plan-farm">
                                    {t("selectFarm")}
                                </label>
                                <p>{t("actionPlanSelectFarmDescription")}</p>
                            </div>

                            <div className="action-plan-selector-controls">

                                <select
                                    id="action-plan-farm"
                                    value={selectedFarmId}
                                    onChange={(event) =>
                                        setSelectedFarmId(event.target.value)
                                    }
                                >
                                    {farms.map((farm) => (
                                        <option key={farm.id} value={farm.id}>
                                            {farm.farmName} — {farm.location}
                                        </option>
                                    ))}
                                </select>

                                <button
                                    type="button"
                                    className="action-plan-primary"
                                    onClick={handleGenerate}
                                    disabled={generating || !selectedFarmId}
                                >
                                    {generating
                                        ? t("generatingActionPlan")
                                        : t("generateActionPlan")}
                                </button>

                            </div>

                        </section>


                        <div className="action-plan-grid">

                            {/* Current plan */}

                            <section className="action-plan-card">

                                {loadingPlan && (
                                    <p className="action-plan-muted">
                                        {t("loadingActionPlan")}
                                    </p>
                                )}

                                {!loadingPlan && !currentPlan && (
                                    <div className="action-plan-empty">
                                        <h3>{t("noActionPlanYet")}</h3>
                                        <p>{t("noActionPlanYetDescription")}</p>
                                    </div>
                                )}

                                {!loadingPlan && currentPlan && (

                                    <>

                                        <div className="plan-summary">

                                            <div className="plan-summary-title">
                                                <h2>
                                                    {t("actionPlan")} #{currentPlan.id}
                                                </h2>
                                                <span
                                                    className={`plan-status plan-status-${statusClass(currentPlan.status)}`}
                                                >
                                                    {currentPlan.status}
                                                </span>
                                            </div>

                                            <dl className="plan-meta">
                                                <div>
                                                    <dt>{t("farmName")}</dt>
                                                    <dd>{currentPlan.farmName || "—"}</dd>
                                                </div>
                                                <div>
                                                    <dt>{t("crop")}</dt>
                                                    <dd>{currentPlan.crop || "—"}</dd>
                                                </div>
                                                <div>
                                                    <dt>{t("growthStage")}</dt>
                                                    <dd>{currentPlan.growthStage || "—"}</dd>
                                                </div>
                                                <div>
                                                    <dt>{t("createdOn")}</dt>
                                                    <dd>{formatDateTime(currentPlan.createdAt)}</dd>
                                                </div>
                                                {currentPlan.reviewedAt && (
                                                    <div>
                                                        <dt>{t("reviewedOn")}</dt>
                                                        <dd>{formatDateTime(currentPlan.reviewedAt)}</dd>
                                                    </div>
                                                )}
                                            </dl>

                                        </div>


                                        {currentPlan.status === "ACCEPTED" && (
                                            <p className="plan-note">
                                                {t("actionPlanAcceptedNote")}
                                            </p>
                                        )}


                                        <h3 className="plan-section-title">
                                            {t("recommendedActions")}
                                        </h3>

                                        {sortedItems.length === 0 ? (

                                            <p className="action-plan-muted">
                                                {t("noActionsInPlan")}
                                            </p>

                                        ) : (

                                            <ol className="plan-items">

                                                {sortedItems.map((item) => (

                                                    <li key={item.id} className="plan-item">

                                                        <div className="plan-item-order">
                                                            {item.sequenceOrder ?? "—"}
                                                        </div>

                                                        <div className="plan-item-body">

                                                            <div className="plan-item-head">
                                                                <h4>{item.actionName || "—"}</h4>
                                                                <span
                                                                    className={`validation-badge validation-${statusClass(item.validationStatus)}`}
                                                                >
                                                                    {item.validationStatus || "UNKNOWN"}
                                                                </span>
                                                            </div>

                                                            {item.category && (
                                                                <span className="plan-item-category">
                                                                    {item.category}
                                                                </span>
                                                            )}

                                                            {item.description && (
                                                                <p>{item.description}</p>
                                                            )}

                                                            {item.validationMessage && (
                                                                <p className="plan-item-message">
                                                                    {item.validationMessage}
                                                                </p>
                                                            )}

                                                        </div>

                                                    </li>

                                                ))}

                                            </ol>

                                        )}


                                        {isDraft && (

                                            <div className="plan-actions">

                                                <button
                                                    type="button"
                                                    className="action-plan-primary"
                                                    onClick={() => handleReview("ACCEPTED")}
                                                    disabled={reviewing}
                                                >
                                                    {t("acceptPlan")}
                                                </button>

                                                <button
                                                    type="button"
                                                    className="action-plan-secondary"
                                                    onClick={() => handleReview("REJECTED")}
                                                    disabled={reviewing}
                                                >
                                                    {t("rejectPlan")}
                                                </button>

                                            </div>

                                        )}

                                    </>

                                )}

                            </section>


                            {/* Previous plans */}

                            <aside className="action-plan-card">

                                <h3 className="plan-section-title">
                                    {t("previousActionPlans")}
                                </h3>

                                {loadingPlans && (
                                    <p className="action-plan-muted">
                                        {t("loadingActionPlans")}
                                    </p>
                                )}

                                {!loadingPlans && sortedPlans.length === 0 && (
                                    <p className="action-plan-muted">
                                        {t("noPreviousActionPlans")}
                                    </p>
                                )}

                                {!loadingPlans && sortedPlans.length > 0 && (

                                    <ul className="plan-history">

                                        {sortedPlans.map((plan) => (

                                            <li key={plan.id}>

                                                <button
                                                    type="button"
                                                    className={
                                                        plan.id === currentPlan?.id
                                                            ? "plan-history-item active"
                                                            : "plan-history-item"
                                                    }
                                                    onClick={() => handleOpenPlan(plan.id)}
                                                >
                                                    <span>
                                                        #{plan.id} · {formatDateTime(plan.createdAt)}
                                                    </span>
                                                    <span
                                                        className={`plan-status plan-status-${statusClass(plan.status)}`}
                                                    >
                                                        {plan.status}
                                                    </span>
                                                </button>

                                            </li>

                                        ))}

                                    </ul>

                                )}

                            </aside>

                        </div>

                    </>

                )}

            </main>

        </div>
    );
}

export default ActionPlan;
