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

const fill = (text, values) =>
    Object.entries(values).reduce((out, [k, v]) => out.replace(`{${k}}`, v), text);

const num = (value, digits = 1) =>
    Number(value).toLocaleString("en-IN", { maximumFractionDigits: digits });

const range = (min, max, digits = 1) =>
    min === max || max == null ? num(min, digits) : `${num(min, digits)} – ${num(max, digits)}`;

const rupees = (min, max) => `₹${range(min, max, 0)}`;

const DUE_KEYS = {
    DUE_NOW: "dueNow",
    COMING: "dueComing",
    PASSED: "duePassed",
    STARTED: "dueStarted",
    ADD_DATE: "dueAddDate",
    BY_STAGE: "dueByStage"
};

// When the step falls due on this farm, from the guide's day count and
// the farm's sowing or transplanting date.
function DueLine({ due, t }) {
    if (!due) {
        return null;
    }
    return (
        <p className={`season-due season-due-${cls(due.status)}`}>
            <strong>{t(DUE_KEYS[due.status] || "dueByStage")}:</strong> {due.sentence}
        </p>
    );
}

const isLink = (value) => typeof value === "string" && value.startsWith("http");

function SourceLink({ url, t }) {
    if (!isLink(url)) {
        return null;
    }
    return (
        <a className="season-req-src" href={url} target="_blank" rel="noreferrer">
            {t("reqSource")}
        </a>
    );
}

/*
 * What one step needs on this farm: when, what to buy and how much,
 * the cost in rupees, workers and water. Every figure links to its
 * source; anything without a source says "Not sourced".
 */
function Requirements({ req, t }) {
    const timing = req.timing;
    const ref = timing?.countFrom ? t(`ref${timing.countFrom}`) : "";
    let when = timing?.text;
    if (timing?.fromDay != null) {
        const days = timing.toDay != null && timing.toDay !== timing.fromDay
            ? fill(t("reqDaysAfter"), { from: timing.fromDay, to: timing.toDay, ref })
            : fill(t("reqDayAfter"), { from: timing.fromDay, ref });
        when = `${days}. ${timing.text}`;
    }

    const labour = req.labour;
    const water = req.water;
    const cost = req.cost;

    return (
        <div className="season-req">
            <p className="season-req-area">
                {req.areaAcres ? fill(t("reqForArea"), { area: num(req.areaAcres, 2) }) : t("reqPerAcre")}
            </p>

            {req.safetyNotes?.map((note, i) => (
                <p key={i} className="season-req-safety">
                    <strong>{t("reqSafety")}:</strong> {note}
                </p>
            ))}

            <dl className="season-req-grid">
                <dt>{t("reqWhen")}</dt>
                <dd>
                    {when} <SourceLink url={timing?.source} t={t} />
                </dd>

                <dt>{t("reqCost")}</dt>
                <dd>
                    {cost.max > 0 ? (
                        <>
                            <strong>{rupees(cost.min, cost.max)}</strong>{" "}
                            ({cost.includesLabour ? t("reqIncludesLabour") : t("reqProductsOnly")})
                        </>
                    ) : (
                        <span className="season-req-missing">{t("reqNotSourced")}</span>
                    )}
                </dd>

                <dt>{t("reqWorkers")}</dt>
                <dd>
                    {labour.sourced ? (
                        labour.parts.map((part, i) => (
                            <span key={i} className="season-req-line">
                                {part.operation}: {range(part.personDaysMin, part.personDaysMax)} {t("reqPersonDays")},{" "}
                                {rupees(part.costMin, part.costMax)} ({part.wageType}){" "}
                                <SourceLink url={part.source} t={t} />
                            </span>
                        ))
                    ) : (
                        <span className="season-req-missing">{t("reqNotSourced")}</span>
                    )}
                </dd>

                <dt>{t("reqWater")}</dt>
                <dd>
                    {!water.sourced ? (
                        <span className="season-req-missing">{t("reqNotSourced")}</span>
                    ) : water.includedIn ? (
                        <>{t("reqCountedIn")} {water.includedIn}</>
                    ) : (
                        <>
                            {range(water.m3Min, water.m3Max)} m³
                            {water.label ? ` (${water.label})` : ""} <SourceLink url={water.source} t={t} />
                        </>
                    )}
                </dd>
            </dl>

            {req.inputs?.length > 0 && (
                <>
                    <h5>
                        {t("reqProducts")}
                        {req.inputs.some((i) => i.choice) ? ` (${t("reqPickOne")})` : ""}
                    </h5>
                    <ul className="season-req-inputs">
                        {req.inputs.map((input) => (
                            <li key={input.product}>
                                <strong>{input.name}</strong>: {range(input.quantityMin, input.quantityMax, 2)} {input.unit},{" "}
                                {rupees(input.costMin, input.costMax)}
                                {input.optional ? ` · ${t("reqOptional")}` : ""}
                                {input.purpose ? ` · ${input.purpose}` : ""}
                                <small>
                                    {" "}₹{num(input.packPrice, 2)} / {num(input.packSize, 2)} {input.packUnit}
                                    {input.shop ? `, ${input.shop}` : ""}, {t("reqPriceOn")} {input.priceDate}
                                    {input.priceEstimate ? ` (${t("reqEstimate")})` : ""}{" "}
                                    <SourceLink url={input.priceSource} t={t} />
                                </small>
                            </li>
                        ))}
                    </ul>
                    <p className="season-req-note">{req.priceNote}</p>
                </>
            )}

            {req.notes?.map((note, i) => <p key={i} className="season-req-note">{note}</p>)}

            {req.notSourced?.length > 0 && (
                <p className="season-req-ask">
                    {t("reqNotSourcedAsk")} {req.notSourced.join("; ")}.
                </p>
            )}
        </div>
    );
}

function StepCard({ step, t }) {
    const [open, setOpen] = useState(false);
    const [showReq, setShowReq] = useState(false);

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
                <DueLine due={step.due} t={t} />
                {step.instruction && <p className="season-step-instruction">{step.instruction}</p>}

                {step.why?.length > 0 && (
                    <ul className="season-step-why">
                        {step.why.map((reason, i) => <li key={i}>{reason}</li>)}
                    </ul>
                )}

                {step.requirements && (
                    <button type="button" className="season-link" onClick={() => setShowReq(!showReq)}>
                        {showReq ? t("reqHideDetails") : t("reqShowDetails")}
                    </button>
                )}
                {showReq && step.requirements && <Requirements req={step.requirements} t={t} />}

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
                            {plan.cropDays?.daysAfterSowing != null
                                ? ` · ${fill(t("dayAfterSowing"), { day: plan.cropDays.daysAfterSowing })}` : ""}
                            {plan.cropDays?.daysAfterTransplanting != null
                                ? ` · ${fill(t("dayAfterTransplanting"), { day: plan.cropDays.daysAfterTransplanting })}` : ""}
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
                                                        {s.due?.from && !s.completed && (
                                                            <small className={`season-stage-due season-due-${cls(s.due.status)}`}>
                                                                {" "}· {t(DUE_KEYS[s.due.status])} {s.due.from}
                                                            </small>
                                                        )}
                                                        {s.requirements?.cost?.max > 0 && (
                                                            <small className="season-stage-cost">
                                                                {" "}· {rupees(s.requirements.cost.min, s.requirements.cost.max)}
                                                            </small>
                                                        )}
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
