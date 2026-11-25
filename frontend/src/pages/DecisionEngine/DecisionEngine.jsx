import { useCallback, useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import toast from "react-hot-toast";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import { FarmListState } from "../../components/PageState/PageState";
import useFarmerFarms from "../../hooks/useFarmerFarms";
import { getFarmDecision } from "../../services/decisionEngineService";

import "./DecisionEngine.css";

const getErrorMessage = (error, fallback) => {
    const data = error?.response?.data;

    if (typeof data === "string" && data.trim()) {
        return data;
    }

    return data?.message || fallback;
};

const lower = (value) => String(value || "needs_data").toLowerCase();

const formatNumber = (value) =>
    value === null || value === undefined ? "—" : Number(value).toLocaleString();

function DecisionEngine() {
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
        (err) => toast.error(getErrorMessage(err, t("farmsLoadFailed")))
    );

    const [decision, setDecision] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    const loadDecision = useCallback(async (farmId) => {
        if (!farmId) {
            setDecision(null);
            return;
        }

        try {
            setLoading(true);
            setError("");
            setDecision(await getFarmDecision(farmId));
        } catch (err) {
            console.error(err);
            const message = getErrorMessage(err, t("decisionLoadFailed"));
            setError(message);
            setDecision(null);
        } finally {
            setLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        loadDecision(selectedFarmId);
    }, [selectedFarmId, loadDecision]);

    const crop = decision?.ml?.cropRecommendation;
    const yieldSignal = decision?.ml?.yieldPrediction;
    const resources = decision?.resources;

    return (
        <div className="app-layout">
            <Sidebar />

            <main className="main-content decision-page">
                <div className="page-header">
                    <div>
                        <h1>{t("decisionEngine")}</h1>
                        <p>{t("decisionEngineDescription")}</p>
                    </div>

                    <LanguageSelector />
                </div>

                <FarmListState
                    loading={loadingFarms}
                    error={farmsError}
                    farms={farms}
                    onRetry={reloadFarms}
                />

                {!loadingFarms && !farmsError && farms.length > 0 && (
                <section className="decision-card">
                    <div className="decision-farm-selection">
                        <label htmlFor="decision-farm">{t("decisionSelectFarm")}</label>

                        <select
                            id="decision-farm"
                            value={selectedFarmId}
                            onChange={(event) => setSelectedFarmId(event.target.value)}
                        >
                            {farms.map((farm) => (
                                <option key={farm.id} value={farm.id}>
                                    {farm.farmName}
                                </option>
                            ))}
                        </select>

                        <button
                            type="button"
                            className="decision-refresh"
                            onClick={() => loadDecision(selectedFarmId)}
                            disabled={!selectedFarmId || loading}
                        >
                            <RefreshCw size={16} className={loading ? "decision-spin" : ""} />
                            {t("refresh")}
                        </button>
                    </div>

                </section>
                )}

                {loading && (
                    <p className="decision-loading" data-testid="decision-loading">
                        {t("decisionLoading")}
                    </p>
                )}

                {error && !loading && (
                    <p className="decision-error" role="alert">{error}</p>
                )}

                {decision && !loading && (
                    <div className="decision-results" data-testid="decision-results">
                        <section className="decision-card decision-summary">
                            <div>
                                <h2>{decision.farmName}</h2>
                                <p>
                                    {decision.crop} · {decision.growthStage}
                                </p>
                                <p className="decision-message">{decision.message}</p>
                            </div>

                            <div className={`decision-risk decision-risk-${lower(decision.overallRisk)}`}>
                                <span>{t("decisionOverallRisk")}</span>
                                <strong data-testid="decision-overall-risk">{decision.overallRisk}</strong>
                            </div>
                        </section>

                        <div className="decision-grid">
                            <section className="decision-card">
                                <h3>{t("decisionContextAttention")}</h3>

                                {decision.context?.attentionItems?.length ? (
                                    <ul className="decision-list">
                                        {decision.context.attentionItems.map((item) => (
                                            <li key={`${item.category}-${item.parameter}`}>
                                                <span className={`decision-pill decision-pill-${lower(item.severity)}`}>
                                                    {item.severity}
                                                </span>
                                                <strong>{item.parameter}</strong>
                                                {item.value !== null && <> ({item.value})</>}: {item.interpretation}
                                            </li>
                                        ))}
                                    </ul>
                                ) : (
                                    <p className="decision-muted">{t("decisionContextAllClear")}</p>
                                )}
                            </section>

                            <section className="decision-card" data-testid="decision-ml">
                                <h3>{t("decisionMlSignals")}</h3>

                                <div className="decision-signal">
                                    <span>{t("decisionCropModel")}</span>
                                    {crop?.status === "AVAILABLE" ? (
                                        <strong>
                                            {crop.recommendedCrop}
                                            {crop.topProbability !== null &&
                                                ` (${Math.round(crop.topProbability * 100)}%)`}
                                            {" · "}
                                            {crop.matchesCurrentCrop
                                                ? t("decisionMatchesCrop")
                                                : t("decisionDiffersCrop")}
                                        </strong>
                                    ) : (
                                        <em>{t("decisionUnavailable")}: {crop?.message}</em>
                                    )}
                                </div>

                                <div className="decision-signal">
                                    <span>{t("decisionYieldModel")}</span>
                                    {yieldSignal?.status === "AVAILABLE" ? (
                                        <strong>{formatNumber(yieldSignal.predictedYieldKgPerHa)} kg/ha</strong>
                                    ) : (
                                        <em>{t("decisionUnavailable")}: {yieldSignal?.message}</em>
                                    )}
                                </div>
                            </section>

                            <section className="decision-card">
                                <h3>{t("decisionResources")}</h3>

                                <div className="decision-signal">
                                    <span>{t("decisionWater")}</span>
                                    <strong>
                                        {formatNumber(resources?.waterAllocated)} / {formatNumber(resources?.waterAvailable)} {t("decisionAllocated")}
                                    </strong>
                                </div>
                                <div className="decision-signal">
                                    <span>{t("decisionBudget")}</span>
                                    <strong>
                                        {formatNumber(resources?.budgetAllocated)} / {formatNumber(resources?.budgetAvailable)} {t("decisionAllocated")}
                                    </strong>
                                </div>
                                <div className="decision-signal">
                                    <span>{t("decisionLabour")}</span>
                                    <strong>
                                        {formatNumber(resources?.peakLabourAllocated)} / {formatNumber(resources?.labourAvailable)} {t("decisionPeakLabour")}
                                    </strong>
                                </div>
                            </section>
                        </div>

                        <section className="decision-card">
                            <h3>{t("decisionRankedActions")}</h3>

                            {decision.actions?.length ? (
                                <ol className="decision-actions" data-testid="decision-actions">
                                    {decision.actions.map((action) => (
                                        <li
                                            key={action.actionId}
                                            className={`decision-action decision-action-${lower(action.decision)}`}
                                        >
                                            <div className="decision-action-head">
                                                <span className="decision-rank">
                                                    {t("decisionRank")} {action.rank}
                                                </span>
                                                <strong>{action.actionName}</strong>
                                                <span className="decision-muted">{action.category}</span>
                                                <span className={`decision-pill decision-pill-${lower(action.decision)}`}>
                                                    {t(`decision_${action.decision}`)}
                                                </span>
                                                {action.sequence && (
                                                    <span className="decision-step">
                                                        {t("decisionSequence")} {action.sequence}
                                                    </span>
                                                )}
                                            </div>

                                            <div className="decision-action-body">
                                                <h4>{t("decisionReasons")}</h4>
                                                <ul>
                                                    {action.reasons.map((reason) => (
                                                        <li key={reason}>{reason}</li>
                                                    ))}
                                                </ul>

                                                {action.dependencies?.length > 0 && (
                                                    <>
                                                        <h4>{t("decisionDependencies")}</h4>
                                                        <ul>
                                                            {action.dependencies.map((dependency) => (
                                                                <li key={`${dependency.type}-${dependency.description}`}>
                                                                    <span className="decision-pill">{dependency.type}</span>{" "}
                                                                    {dependency.description}
                                                                </li>
                                                            ))}
                                                        </ul>
                                                    </>
                                                )}

                                                {action.sourceReference && (
                                                    <p className="decision-muted">
                                                        {t("decisionReference")}: {action.sourceReference}
                                                    </p>
                                                )}
                                            </div>
                                        </li>
                                    ))}
                                </ol>
                            ) : (
                                <p className="decision-muted">{t("decisionNoActions")}</p>
                            )}
                        </section>

                        {decision.notes?.length > 0 && (
                            <section className="decision-card">
                                <h3>{t("decisionNotes")}</h3>
                                <ul className="decision-list">
                                    {decision.notes.map((note) => (
                                        <li key={note}>{note}</li>
                                    ))}
                                </ul>
                            </section>
                        )}
                    </div>
                )}
            </main>
        </div>
    );
}

export default DecisionEngine;
