import { useEffect, useState } from "react";

import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";
import FarmPicker from "../../components/FarmPicker/FarmPicker";

import useFarmerFarms, { errorMessage } from "../../hooks/useFarmerFarms";
import { getFarmStateByFarmId } from "../../services/farmStateService";
import { runWhatIf } from "../../services/insightsService";

import "../ActionPlan/ActionPlan.css";
import "../History/History.css";
import "./WhatIf.css";

const STAGES = ["Seedling", "Vegetative", "Flowering", "Fruiting", "Maturity", "Harvest"];

// Editable fields, grouped as the farmer thinks of them.
const GROUPS = [
    ["whatIfGroupResources", ["waterAvailable", "labourAvailable", "budgetAvailable"]],
    ["whatIfGroupSoil", ["soilMoisture", "soilPh", "nitrogen", "phosphorus", "potassium"]],
    ["whatIfGroupWeather", ["temperature", "humidity", "rainfall"]]
];

const NUMBER_FIELDS = GROUPS.flatMap(([, fields]) => fields);

const toForm = (state) => {
    const form = { growthStage: state?.growthStage || "" };
    NUMBER_FIELDS.forEach((field) => {
        form[field] = state?.[field] ?? "";
    });
    return form;
};

const toRequest = (form) => {
    const body = {};
    if (form.growthStage) body.growthStage = form.growthStage;
    NUMBER_FIELDS.forEach((field) => {
        if (form[field] !== "" && form[field] !== null) {
            body[field] = field === "labourAvailable"
                ? parseInt(form[field], 10)
                : Number(form[field]);
        }
    });
    return body;
};

const decisionKey = (decision) => decision ? `decision${decision}` : "whatIfNotApplicable";

function ScenarioColumn({ title, scenario, t }) {
    return (
        <div className="whatif-column">
            <h3>{title}</h3>
            <dl className="whatif-summary">
                <div>
                    <dt>{t("whatIfOverallRisk")}</dt>
                    <dd className={`whatif-risk whatif-risk-${(scenario.overallRisk || "unknown").toLowerCase()}`}>
                        {scenario.overallRisk}
                    </dd>
                </div>
                <div>
                    <dt>{t("whatIfRecommended")}</dt>
                    <dd>{scenario.recommended}</dd>
                </div>
                <div>
                    <dt>{t("whatIfDeferred")}</dt>
                    <dd>{scenario.deferred}</dd>
                </div>
                <div>
                    <dt>{t("whatIfNeedsData")}</dt>
                    <dd>{scenario.needsData}</dd>
                </div>
                <div>
                    <dt>{t("whatIfRejected")}</dt>
                    <dd>{scenario.rejected}</dd>
                </div>
                <div>
                    <dt>{t("whatIfCropRecommendation")}</dt>
                    <dd>
                        {scenario.cropRecommendation?.status === "AVAILABLE"
                            ? `${scenario.cropRecommendation.recommendedCrop} (${Math.round((scenario.cropRecommendation.topProbability || 0) * 100)}%)`
                            : t("unavailable")}
                    </dd>
                </div>
            </dl>

            <h4>{t("whatIfRisks")}</h4>
            {scenario.risks.length === 0
                ? <p className="action-plan-muted">{t("whatIfNoRisks")}</p>
                : (
                    <ul className="whatif-list">
                        {scenario.risks.map((risk) => (
                            <li key={`${risk.category}-${risk.parameter}`}>
                                <strong>{risk.severity}</strong> {risk.parameter}
                                {risk.value ? ` (${risk.value})` : ""}: {risk.evidence}
                            </li>
                        ))}
                    </ul>
                )}

            <h4>{t("whatIfActions")} ({scenario.evaluatedActions})</h4>
            {scenario.actions.length === 0
                ? <p className="action-plan-muted">{t("whatIfNoActions")}</p>
                : (
                    <ol className="whatif-list">
                        {scenario.actions.map((action) => (
                            <li key={action.actionId}>
                                <span className={`whatif-decision whatif-${action.decision.toLowerCase()}`}>
                                    {t(decisionKey(action.decision))}
                                </span>{" "}
                                {action.actionName}
                            </li>
                        ))}
                    </ol>
                )}
        </div>
    );
}

function WhatIf() {

    const { farmer } = useAuth();
    const { t } = useLanguage();

    const { farms, selectedFarmId, setSelectedFarmId, loadingFarms } = useFarmerFarms(
        farmer?.userId,
        (error) => toast.error(errorMessage(error, t("farmsLoadFailed")))
    );

    const [savedState, setSavedState] = useState(null);
    const [form, setForm] = useState(toForm(null));
    const [result, setResult] = useState(null);
    const [loadingState, setLoadingState] = useState(false);
    const [running, setRunning] = useState(false);

    const run = async (farmId, body) => {
        try {
            setRunning(true);
            setResult(await runWhatIf(farmId, body));
        } catch (error) {
            console.error(error);
            toast.error(errorMessage(error, t("whatIfLoadFailed")));
        } finally {
            setRunning(false);
        }
    };

    useEffect(() => {

        setResult(null);
        setSavedState(null);

        if (!selectedFarmId) {
            return;
        }

        const load = async () => {
            try {
                setLoadingState(true);
                const state = await getFarmStateByFarmId(selectedFarmId);
                setSavedState(state);
                setForm(toForm(state));
                // Show the current analysis next to an unchanged copy first.
                await run(selectedFarmId, {});
            } catch (error) {
                console.error(error);
                setSavedState(null);
            } finally {
                setLoadingState(false);
            }
        };

        load();

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selectedFarmId]);

    const handleSubmit = (event) => {
        event.preventDefault();
        run(selectedFarmId, toRequest(form));
    };

    const handleReset = () => {
        setForm(toForm(savedState));
        run(selectedFarmId, {});
    };

    return (
        <div className="action-plan-layout">

            <Sidebar />

            <main className="action-plan-main">

                <header className="action-plan-header">
                    <div>
                        <h1>{t("whatIfAnalysis")}</h1>
                        <p>{t("whatIfDescription")}</p>
                    </div>
                    <LanguageSelector />
                </header>

                {loadingFarms && (
                    <section className="action-plan-card action-plan-empty">{t("loadingYourFarms")}</section>
                )}

                {!loadingFarms && farms.length === 0 && (
                    <section className="action-plan-card action-plan-empty">
                        <h3>{t("noFarms")}</h3>
                        <p>{t("noFarmsDescription")}</p>
                    </section>
                )}

                {!loadingFarms && farms.length > 0 && (
                    <>
                        <FarmPicker
                            id="whatif-farm"
                            farms={farms}
                            value={selectedFarmId}
                            onChange={setSelectedFarmId}
                            description={t("whatIfSelectFarmDescription")}
                        />

                        {loadingState && (
                            <section className="action-plan-card action-plan-empty">{t("loadingFarmState")}</section>
                        )}

                        {!loadingState && !savedState && (
                            <section className="action-plan-card action-plan-empty">{t("whatIfNoState")}</section>
                        )}

                        {savedState && (
                            <form className="action-plan-card whatif-form" onSubmit={handleSubmit}>
                                <h2>{t("whatIfEditTitle")}</h2>
                                <p className="action-plan-muted">
                                    {t("whatIfEditHint")} {t("crop")}: <strong>{savedState.crop || "—"}</strong>
                                </p>

                                <div className="whatif-fields">
                                    <label>
                                        <span>{t("growthStage")}</span>
                                        <select
                                            value={form.growthStage}
                                            onChange={(e) => setForm({ ...form, growthStage: e.target.value })}
                                        >
                                            {STAGES.map((stage) => (
                                                <option key={stage} value={stage}>{t(stage.toLowerCase())}</option>
                                            ))}
                                        </select>
                                    </label>
                                </div>

                                {GROUPS.map(([groupKey, fields]) => (
                                    <fieldset key={groupKey} className="whatif-group">
                                        <legend>{t(groupKey)}</legend>
                                        <div className="whatif-fields">
                                            {fields.map((field) => (
                                                <label key={field}>
                                                    <span>{t(field)}</span>
                                                    <input
                                                        type="number"
                                                        step="any"
                                                        name={field}
                                                        value={form[field]}
                                                        onChange={(e) => setForm({ ...form, [field]: e.target.value })}
                                                    />
                                                </label>
                                            ))}
                                        </div>
                                    </fieldset>
                                ))}

                                <div className="history-filter-buttons">
                                    <button type="submit" className="history-button" disabled={running}>
                                        {running ? t("whatIfRunning") : t("whatIfRun")}
                                    </button>
                                    <button type="button" className="history-button history-button-ghost" onClick={handleReset}>
                                        {t("whatIfReset")}
                                    </button>
                                </div>
                                <p className="history-ml-note">{t("whatIfNotSaved")}</p>
                            </form>
                        )}

                        {result && (
                            <>
                                <section className="action-plan-card">
                                    <h2>{t("whatIfChangedFields")}</h2>
                                    {result.changedFields.length === 0
                                        ? <p className="action-plan-muted">{t("whatIfNoChanges")}</p>
                                        : (
                                            <ul className="whatif-list">
                                                {result.changedFields.map((change) => (
                                                    <li key={change.field}>
                                                        {t(change.field)}: {change.before ?? "—"} → {change.after ?? "—"}
                                                    </li>
                                                ))}
                                            </ul>
                                        )}

                                    <h3>{t("whatIfActionDifferences")}</h3>
                                    {result.actionDifferences.length === 0
                                        ? <p className="action-plan-muted">{t("whatIfNoDifferences")}</p>
                                        : (
                                            <ul className="whatif-list">
                                                {result.actionDifferences.map((d) => (
                                                    <li key={d.actionId}>
                                                        {d.actionName}: {t(decisionKey(d.currentDecision))} → {t(decisionKey(d.whatIfDecision))}
                                                    </li>
                                                ))}
                                            </ul>
                                        )}
                                </section>

                                <section className="action-plan-card whatif-compare">
                                    <ScenarioColumn title={t("whatIfCurrent")} scenario={result.current} t={t} />
                                    <ScenarioColumn title={t("whatIfScenario")} scenario={result.whatIf} t={t} />
                                </section>

                                <section className="action-plan-card">
                                    <h3>{t("whatIfYield")}</h3>
                                    <p>
                                        {result.yieldPrediction?.status === "AVAILABLE"
                                            ? `${result.yieldPrediction.predictedYieldKgPerHa} kg/ha`
                                            : `${t("unavailable")}: ${result.yieldPrediction?.message || ""}`}
                                    </p>
                                    <h3>{t("whatIfNotes")}</h3>
                                    <ul className="whatif-list">
                                        {result.notes.map((note) => <li key={note}>{note}</li>)}
                                    </ul>
                                </section>
                            </>
                        )}
                    </>
                )}
            </main>
        </div>
    );
}

export default WhatIf;
