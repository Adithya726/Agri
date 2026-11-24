import { useCallback, useEffect, useState } from "react";

import { toast } from "react-hot-toast";

import { useLanguage } from "../../context/LanguageContext";

import { getReplanAssessment, replanFarm } from "../../services/replanService";
import { reviewActionPlan } from "../../services/actionPlanService";

const getErrorMessage = (error, fallback) => {

    const data = error?.response?.data;

    if (typeof data === "string" && data.trim()) {
        return data;
    }

    return data?.message || fallback;
};

const decisionClass = (decision) =>
    `decision-badge decision-${String(decision || "none").toLowerCase()}`;

function Decision({ value }) {

    const { t } = useLanguage();

    return value
        ? <span className={decisionClass(value)}>{value}</span>
        : <span className="action-plan-muted">{t("notApplicable")}</span>;
}

/*
 * Dynamic replanning for the farm's accepted plan: what changed since it
 * was made, what that does to its pending actions, a new plan built from
 * today's state, how the two differ, and accept or reject.
 */
function ReplanPanel({ farmId, planId, onPlanChanged }) {

    const { t } = useLanguage();

    const [assessment, setAssessment] = useState(null);
    const [loading, setLoading] = useState(false);

    const [comparison, setComparison] = useState(null);
    const [working, setWorking] = useState(false);


    const loadAssessment = useCallback(async () => {

        if (!farmId || !planId) {
            setAssessment(null);
            return;
        }

        try {

            setLoading(true);
            setAssessment(await getReplanAssessment(farmId));

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("replanAssessmentFailed")));
            setAssessment(null);

        } finally {
            setLoading(false);
        }

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [farmId, planId]);


    useEffect(() => {
        setComparison(null);
        loadAssessment();
    }, [loadAssessment]);


    const handleReplan = async () => {

        try {

            setWorking(true);
            setComparison(await replanFarm(farmId));
            toast.success(t("newPlanGenerated"));

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("replanFailed")));

        } finally {
            setWorking(false);
        }
    };


    const handleReview = async (decision) => {

        if (!comparison?.newPlan?.id) {
            return;
        }

        try {

            setWorking(true);
            await reviewActionPlan(comparison.newPlan.id, decision);
            toast.success(decision === "ACCEPTED" ? t("newPlanAccepted") : t("newPlanRejected"));
            setComparison(null);

            if (decision === "ACCEPTED") {
                onPlanChanged?.();
            } else {
                loadAssessment();
            }

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionPlanReviewFailed")));

        } finally {
            setWorking(false);
        }
    };


    if (!planId) {
        return null;
    }

    const pendingImpacts = (assessment?.impacts || []).filter((i) => !i.executionStatus);

    return (

        <section className="action-plan-card replan-panel">

            <div className="monitoring-plan-head">

                <h2>{t("replanning")}</h2>

                {assessment && (
                    <span className={`replan-flag ${assessment.replanRecommended ? "replan-yes" : "replan-no"}`}>
                        {assessment.replanRecommended ? t("replanRecommended") : t("planStillStands")}
                    </span>
                )}

            </div>

            <p className="action-plan-muted">{t("replanningDescription")}</p>

            {loading && !assessment && (
                <p className="action-plan-muted">{t("checkingForChanges")}</p>
            )}

            {assessment && (

                <>

                    <ul className="replan-reasons">
                        {assessment.reasons.map((reason) => (
                            <li key={reason}>{reason}</li>
                        ))}
                    </ul>

                    {assessment.changes.length > 0 && (

                        <>
                            <h3 className="plan-section-title">{t("changesSincePlan")}</h3>
                            <div className="monitoring-table-wrap">
                                <table className="monitoring-table">
                                    <thead>
                                        <tr>
                                            <th>{t("field")}</th>
                                            <th>{t("whenPlanned")}</th>
                                            <th>{t("now")}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {assessment.changes.map((c) => (
                                            <tr key={c.field}>
                                                <td>{t(c.field)}</td>
                                                <td>{c.before ?? "—"}</td>
                                                <td>{c.after ?? "—"}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </>

                    )}

                    {pendingImpacts.length > 0 && (

                        <>
                            <h3 className="plan-section-title">{t("pendingActionsReassessed")}</h3>
                            <div className="monitoring-table-wrap">
                                <table className="monitoring-table">
                                    <thead>
                                        <tr>
                                            <th>{t("action")}</th>
                                            <th>{t("whenPlanned")}</th>
                                            <th>{t("now")}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {pendingImpacts.map((i) => (
                                            <tr key={i.itemId} className={i.changed ? "replan-changed" : ""}>
                                                <td className="replan-wrap">{i.actionName}</td>
                                                <td><Decision value={i.previousDecision} /></td>
                                                <td><Decision value={i.currentDecision} /></td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </>

                    )}

                    {!comparison && (
                        <div className="replan-actions">
                            <button
                                type="button"
                                className="action-plan-primary"
                                onClick={handleReplan}
                                disabled={working}
                            >
                                {working ? t("generatingActionPlan") : t("generateNewPlan")}
                            </button>
                        </div>
                    )}

                </>

            )}


            {comparison && (

                <div className="replan-comparison">

                    <h3 className="plan-section-title">
                        {t("planComparison")}: #{comparison.currentPlan.id} → #{comparison.newPlan.id}
                    </h3>

                    <ul className="replan-reasons">
                        {comparison.summary.map((line) => (
                            <li key={line}>{line}</li>
                        ))}
                    </ul>

                    <div className="monitoring-table-wrap">
                        <table className="monitoring-table">
                            <thead>
                                <tr>
                                    <th>{t("action")}</th>
                                    <th>{t("inCurrentPlan")}</th>
                                    <th>{t("inNewPlan")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {comparison.kept.map((a) => (
                                    <tr key={`k${a.actionId}`}>
                                        <td className="replan-wrap">{a.actionName}</td>
                                        <td><Decision value={a.currentDecision} /></td>
                                        <td><Decision value={a.newDecision} /></td>
                                    </tr>
                                ))}
                                {comparison.added.map((a) => (
                                    <tr key={`a${a.actionId}`} className="replan-added">
                                        <td className="replan-wrap">{a.actionName}</td>
                                        <td className="action-plan-muted">{t("notInPlan")}</td>
                                        <td><Decision value={a.newDecision} /></td>
                                    </tr>
                                ))}
                                {comparison.removed.map((a) => (
                                    <tr key={`r${a.actionId}`} className="replan-removed">
                                        <td className="replan-wrap">{a.actionName}</td>
                                        <td>
                                            <Decision value={a.currentDecision} />
                                            {a.executionStatus && (
                                                <span className="action-plan-muted"> ({a.executionStatus})</span>
                                            )}
                                        </td>
                                        <td className="action-plan-muted">{t("dropped")}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {comparison.newPlan.status === "DRAFT" && (
                        <div className="replan-actions">
                            <button
                                type="button"
                                className="action-plan-primary"
                                onClick={() => handleReview("ACCEPTED")}
                                disabled={working}
                            >
                                {t("acceptNewPlan")}
                            </button>
                            <button
                                type="button"
                                className="action-plan-secondary"
                                onClick={() => handleReview("REJECTED")}
                                disabled={working}
                            >
                                {t("rejectNewPlan")}
                            </button>
                        </div>
                    )}

                </div>

            )}

        </section>
    );
}

export default ReplanPanel;
