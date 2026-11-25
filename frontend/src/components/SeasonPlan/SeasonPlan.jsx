import { useCallback, useEffect, useState } from "react";

import { useLanguage } from "../../context/LanguageContext";
import { getSeasonPlan } from "../../services/actionPlanService";

import "./SeasonPlan.css";

/*
 * Whole-season plan for the selected farm. The backend re-decides it
 * from the farm's latest state on every load, so after a disruption
 * (disease, water drop, budget cut) "Do now" shows the best order to
 * follow straight away.
 */

const STATUS_KEYS = {
    ON_TRACK: "seasonStatusOnTrack",
    ACT_NOW: "seasonStatusActNow",
    ADD_DETAILS: "seasonStatusAddDetails",
    NO_GUIDE: "seasonStatusNoGuide"
};

const STAGE_KEYS = {
    DONE: "seasonStageDone",
    NOW: "seasonStageNow",
    UPCOMING: "seasonStageUpcoming"
};

const CHECK_KEYS = {
    FEASIBLE: "seasonCheckOk",
    INFEASIBLE: "seasonCheckFail",
    NEEDS_INPUT: "seasonCheckAdd",
    FIELD_GUIDE: "seasonCheckGuide"
};

const getErrorMessage = (error, fallback) => {
    const data = error?.response?.data;
    if (typeof data === "string" && data.trim()) {
        return data;
    }
    return data?.message || fallback;
};

const cls = (value) => String(value || "").toLowerCase().replace(/_/g, "-");

function StepCard({ step, t }) {
    const [open, setOpen] = useState(false);

    return (
        <li className="season-step">
            {step.order != null && (
                <div className="season-step-order">{step.order}</div>
            )}
            <div className="season-step-body">
                <div className="season-step-head">
                    <h4>{step.name}</h4>
                    <span className={`season-decision season-decision-${cls(step.decision)}`}>
                        {step.decision}
                    </span>
                </div>
                {step.category && (
                    <span className="season-step-category">{step.category}</span>
                )}
                {step.instruction && <p className="season-step-instruction">{step.instruction}</p>}

                {step.why?.length > 0 && (
                    <ul className="season-step-why">
                        {step.why.map((reason, i) => <li key={i}>{reason}</li>)}
                    </ul>
                )}

                {step.checks?.length > 0 && (
                    <button type="button" className="season-link" onClick={() => setOpen(!open)}>
                        {open ? t("seasonHideChecks") : t("seasonShowChecks")}
                    </button>
                )}

                {open && (
                    <table className="season-checks">
                        <tbody>
                            {step.checks.map((check, i) => (
                                <tr key={i}>
                                    <td>{check.category?.replace(/_/g, " ").toLowerCase()}</td>
                                    <td>
                                        <span className={`season-check season-check-${cls(check.status)}`}>
                                            {t(CHECK_KEYS[check.status] || "seasonCheckAdd")}
                                        </span>
                                    </td>
                                    <td>{check.message}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}

                {step.source && (
                    <p className="season-source">
                        <strong>{t("sourceReference")}:</strong> {step.source}
                    </p>
                )}
            </div>
        </li>
    );
}

function SeasonPlan({ farmId }) {
    const { t } = useLanguage();

    const [plan, setPlan] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    const load = useCallback(async () => {
        if (!farmId) {
            setPlan(null);
            return;
        }
        try {
            setLoading(true);
            setError("");
            setPlan(await getSeasonPlan(farmId));
        } catch (e) {
            console.error(e);
            setPlan(null);
            setError(getErrorMessage(e, t("seasonPlanLoadFailed")));
        } finally {
            setLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [farmId]);

    useEffect(() => {
        load();
    }, [load]);

    return (
        <section className="action-plan-card season-plan">
            <div className="season-plan-top">
                <div>
                    <h2>{t("seasonPlanTitle")}</h2>
                    <p className="action-plan-muted">{t("seasonPlanSubtitle")}</p>
                </div>
                <button type="button" className="action-plan-secondary season-refresh"
                    onClick={load} disabled={loading || !farmId}>
                    {loading ? t("loadingSeasonPlan") : t("refreshSeasonPlan")}
                </button>
            </div>

            {error && <p className="season-error">{error}</p>}

            {plan && (
                <>
                    <div className={`season-banner season-banner-${cls(plan.status)}`}>
                        <strong>{t(STATUS_KEYS[plan.status] || "seasonStatusOnTrack")}</strong>
                        <span>{plan.headline}</span>
                        <small>
                            {plan.crop}{plan.variety ? ` (${plan.variety})` : ""}
                            {plan.growthStage ? ` · ${plan.growthStage}` : ""}
                            {plan.season ? ` · ${plan.season} ${t("season")}` : ""}
                        </small>
                    </div>

                    {plan.alerts?.length > 0 && (
                        <div className="season-block">
                            <h3>{t("seasonNeedsAttention")}</h3>
                            <ul className="season-alerts">
                                {plan.alerts.map((alert, i) => (
                                    <li key={i} className={`season-alert season-alert-${cls(alert.severity)}`}>
                                        <div className="season-alert-head">
                                            <span className="season-alert-severity">{alert.severity}</span>
                                            <strong>{alert.topic}</strong>
                                            <span className="season-alert-reading">{alert.reading}</span>
                                        </div>
                                        {alert.meaning && <p>{alert.meaning}</p>}
                                        <p className="season-alert-fix">{t("seasonHowToFix")}</p>
                                        <ul>
                                            {alert.doThis.map((line, j) => <li key={j}>{line}</li>)}
                                        </ul>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}

                    {plan.changes?.length > 0 && (
                        <div className="season-block">
                            <h3>{t("seasonWhatChanged")}</h3>
                            <ul className="season-changes">
                                {plan.changes.map((c, i) => <li key={i}>{c.sentence}</li>)}
                            </ul>
                        </div>
                    )}

                    <div className="season-block">
                        <h3>{t("seasonDoNow")}</h3>
                        {plan.doNow?.length > 0 ? (
                            <ol className="season-steps">
                                {plan.doNow.map((step) => <StepCard key={step.actionId} step={step} t={t} />)}
                            </ol>
                        ) : (
                            <p className="action-plan-muted">{t("seasonNothingNow")}</p>
                        )}
                        {plan.notes?.map((note, i) => <p key={i} className="season-note">{note}</p>)}
                    </div>

                    {plan.onHold?.length > 0 && (
                        <div className="season-block">
                            <h3>{t("seasonOnHold")}</h3>
                            <ul className="season-steps">
                                {plan.onHold.map((step) => <StepCard key={step.actionId} step={step} t={t} />)}
                            </ul>
                        </div>
                    )}

                    {plan.missingDetails?.length > 0 && (
                        <div className="season-block season-missing">
                            <h3>{t("seasonMissingDetails")}</h3>
                            <p>{plan.missingDetails.join(", ")}</p>
                        </div>
                    )}

                    {plan.stages?.length > 0 && (
                        <div className="season-block">
                            <h3>{t("seasonTimeline")}</h3>
                            <ol className="season-timeline">
                                {plan.stages.map((stage) => (
                                    <li key={stage.stage} className={`season-stage season-stage-${cls(stage.status)}`}>
                                        <div className="season-stage-head">
                                            <strong>{stage.stage}</strong>
                                            <span>{t(STAGE_KEYS[stage.status])}</span>
                                        </div>
                                        {stage.steps.length === 0 ? (
                                            <p className="action-plan-muted">{t("seasonNoStepsInStage")}</p>
                                        ) : (
                                            <ul>
                                                {stage.steps.map((s) => (
                                                    <li key={s.actionId} className={s.completed ? "season-done" : ""}
                                                        title={s.instruction}>
                                                        {s.completed ? "✓ " : ""}{s.name}
                                                    </li>
                                                ))}
                                            </ul>
                                        )}
                                    </li>
                                ))}
                            </ol>
                        </div>
                    )}
                </>
            )}
        </section>
    );
}

export default SeasonPlan;
