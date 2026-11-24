import { useCallback, useEffect, useState } from "react";

import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";

import { getFarmsByFarmerId } from "../../services/farmService";

import {
    clearActionExecution,
    getFarmMonitoring,
    reportActionExecution
} from "../../services/monitoringService";

import "../ActionPlan/ActionPlan.css";
import "./Monitoring.css";

// Columns of the readings table, with their units.
const READING_FIELDS = [
    ["growthStage", ""],
    ["soilMoisture", "%"],
    ["soilPh", ""],
    ["nitrogen", ""],
    ["waterAvailable", ""],
    ["labourAvailable", ""],
    ["budgetAvailable", ""],
    ["cropHealth", ""],
    ["diseaseStatus", ""]
];

const getErrorMessage = (error, fallback) => {

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

const formatDateTime = (value) => {

    if (!value) {
        return "—";
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime())
        ? value
        : date.toLocaleString();
};

// Local calendar date as yyyy-mm-dd, for the date input.
const todayIso = () => {
    const now = new Date();
    const offset = now.getTimezoneOffset() * 60000;
    return new Date(now.getTime() - offset).toISOString().slice(0, 10);
};

const formatDifference = (value) => {

    if (value === null || value === undefined) {
        return "";
    }

    return value > 0 ? `+${value}` : String(value);
};

function Monitoring() {

    const { farmer } = useAuth();
    const { t } = useLanguage();

    const [farms, setFarms] = useState([]);
    const [selectedFarmId, setSelectedFarmId] = useState("");
    const [loadingFarms, setLoadingFarms] = useState(true);

    const [monitoring, setMonitoring] = useState(null);
    const [loading, setLoading] = useState(false);

    // Report form state per item: { date, note }
    const [drafts, setDrafts] = useState({});
    const [savingItemId, setSavingItemId] = useState(null);


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
                    farmerFarms.length > 0 ? String(farmerFarms[0].id) : ""
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


    const loadMonitoring = useCallback(async (farmId) => {

        if (!farmId) {
            setMonitoring(null);
            return;
        }

        try {

            setLoading(true);
            setMonitoring(await getFarmMonitoring(farmId));

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("monitoringLoadFailed")));
            setMonitoring(null);

        } finally {
            setLoading(false);
        }

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);


    useEffect(() => {
        setDrafts({});
        loadMonitoring(selectedFarmId);
    }, [selectedFarmId, loadMonitoring]);


    const draftFor = (itemId) =>
        drafts[itemId] || { date: todayIso(), note: "" };

    const updateDraft = (itemId, change) =>
        setDrafts((current) => ({
            ...current,
            [itemId]: { ...draftFor(itemId), ...change }
        }));


    const handleReport = async (itemId, status) => {

        const draft = draftFor(itemId);

        try {

            setSavingItemId(itemId);

            const updated = await reportActionExecution(itemId, {
                status,
                executedOn: draft.date,
                note: draft.note
            });

            setMonitoring(updated);
            toast.success(
                status === "DONE"
                    ? t("actionMarkedDone")
                    : t("actionMarkedSkipped")
            );

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionReportFailed")));

        } finally {
            setSavingItemId(null);
        }
    };


    const handleUndo = async (itemId) => {

        try {

            setSavingItemId(itemId);
            setMonitoring(await clearActionExecution(itemId));
            toast.success(t("actionReportCleared"));

        } catch (error) {

            console.error(error);
            toast.error(getErrorMessage(error, t("actionReportFailed")));

        } finally {
            setSavingItemId(null);
        }
    };


    const progress = monitoring?.progress;
    const percentDone = progress && progress.total > 0
        ? Math.round(((progress.done + progress.skipped) / progress.total) * 100)
        : 0;

    const plan = monitoring?.plan;
    const minDate = plan?.createdAt ? String(plan.createdAt).slice(0, 10) : undefined;


    return (

        <div className="action-plan-layout">

            <Sidebar />

            <main className="action-plan-main">

                <header className="action-plan-header">

                    <div>
                        <h1>{t("monitoring")}</h1>
                        <p>{t("monitoringDescription")}</p>
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
                                <label htmlFor="monitoring-farm">
                                    {t("selectFarm")}
                                </label>
                                <p>{t("monitoringSelectFarmDescription")}</p>
                            </div>

                            <div className="action-plan-selector-controls">

                                <select
                                    id="monitoring-farm"
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

                            </div>

                        </section>


                        {loading && !monitoring && (
                            <section className="action-plan-card action-plan-empty">
                                {t("loadingMonitoring")}
                            </section>
                        )}


                        {monitoring && (

                            <>

                                <section className="action-plan-card">

                                    <div className="monitoring-plan-head">

                                        <h2>
                                            {plan
                                                ? `${t("actionPlan")} #${plan.id}`
                                                : t("noAcceptedPlan")}
                                        </h2>

                                        {plan && (
                                            <span className="plan-status plan-status-accepted">
                                                {plan.status}
                                            </span>
                                        )}

                                    </div>

                                    {!plan && (
                                        <p className="action-plan-muted">
                                            {t("noAcceptedPlanDescription")}
                                        </p>
                                    )}

                                    {plan && progress && (

                                        <div className="monitoring-progress">

                                            <div
                                                className="monitoring-progress-bar"
                                                role="progressbar"
                                                aria-valuenow={percentDone}
                                                aria-valuemin={0}
                                                aria-valuemax={100}
                                            >
                                                <span style={{ width: `${percentDone}%` }} />
                                            </div>

                                            <dl className="monitoring-counts">
                                                <div>
                                                    <dt>{t("actionsDone")}</dt>
                                                    <dd>{progress.done}</dd>
                                                </div>
                                                <div>
                                                    <dt>{t("actionsSkipped")}</dt>
                                                    <dd>{progress.skipped}</dd>
                                                </div>
                                                <div>
                                                    <dt>{t("actionsPending")}</dt>
                                                    <dd>{progress.pending}</dd>
                                                </div>
                                                <div>
                                                    <dt>{t("acceptedOn")}</dt>
                                                    <dd>{formatDateTime(plan.reviewedAt)}</dd>
                                                </div>
                                            </dl>

                                        </div>

                                    )}


                                    {plan && (

                                        <ol className="plan-items">

                                            {monitoring.items.map((item) => {

                                                const execution = item.execution;
                                                const draft = draftFor(item.itemId);
                                                const saving = savingItemId === item.itemId;

                                                return (

                                                    <li key={item.itemId} className="plan-item">

                                                        <div className="plan-item-order">
                                                            {item.sequenceOrder ?? "—"}
                                                        </div>

                                                        <div className="plan-item-body">

                                                            <div className="plan-item-head">
                                                                <h4>{item.actionName}</h4>
                                                                <span
                                                                    className={`monitoring-status monitoring-${(execution?.status || "pending").toLowerCase()}`}
                                                                >
                                                                    {execution
                                                                        ? t(execution.status === "DONE" ? "statusDone" : "statusSkipped")
                                                                        : t("statusPending")}
                                                                </span>
                                                            </div>

                                                            {item.category && (
                                                                <span className="plan-item-category">
                                                                    {item.category}
                                                                </span>
                                                            )}

                                                            {execution ? (

                                                                <div className="monitoring-report">

                                                                    <p>
                                                                        {t("reportedFor")}{" "}
                                                                        <strong>{execution.executedOn}</strong>
                                                                        {execution.note && <> — {execution.note}</>}
                                                                    </p>

                                                                    {item.change && (

                                                                        <div className="monitoring-change">

                                                                            <p className="action-plan-muted">
                                                                                {item.change.message}
                                                                            </p>

                                                                            {item.change.changes.length > 0 && (

                                                                                <table className="monitoring-table">
                                                                                    <thead>
                                                                                        <tr>
                                                                                            <th>{t("field")}</th>
                                                                                            <th>{t("beforeAction")}</th>
                                                                                            <th>{t("now")}</th>
                                                                                            <th>{t("change")}</th>
                                                                                        </tr>
                                                                                    </thead>
                                                                                    <tbody>
                                                                                        {item.change.changes.map((c) => (
                                                                                            <tr key={c.field}>
                                                                                                <td>{t(c.field)}</td>
                                                                                                <td>{c.before ?? "—"}</td>
                                                                                                <td>{c.after ?? "—"}</td>
                                                                                                <td>{formatDifference(c.difference)}</td>
                                                                                            </tr>
                                                                                        ))}
                                                                                    </tbody>
                                                                                </table>

                                                                            )}

                                                                        </div>

                                                                    )}

                                                                    <button
                                                                        type="button"
                                                                        className="action-plan-secondary monitoring-small"
                                                                        onClick={() => handleUndo(item.itemId)}
                                                                        disabled={saving}
                                                                    >
                                                                        {t("undoReport")}
                                                                    </button>

                                                                </div>

                                                            ) : (

                                                                <div className="monitoring-form">

                                                                    <label>
                                                                        {t("dateDone")}
                                                                        <input
                                                                            type="date"
                                                                            value={draft.date}
                                                                            min={minDate}
                                                                            max={todayIso()}
                                                                            onChange={(event) =>
                                                                                updateDraft(item.itemId, { date: event.target.value })
                                                                            }
                                                                        />
                                                                    </label>

                                                                    <label className="monitoring-note">
                                                                        {t("noteOptional")}
                                                                        <input
                                                                            type="text"
                                                                            maxLength={500}
                                                                            value={draft.note}
                                                                            onChange={(event) =>
                                                                                updateDraft(item.itemId, { note: event.target.value })
                                                                            }
                                                                        />
                                                                    </label>

                                                                    <div className="monitoring-buttons">
                                                                        <button
                                                                            type="button"
                                                                            className="action-plan-primary monitoring-small"
                                                                            onClick={() => handleReport(item.itemId, "DONE")}
                                                                            disabled={saving || !draft.date}
                                                                        >
                                                                            {t("markDone")}
                                                                        </button>
                                                                        <button
                                                                            type="button"
                                                                            className="action-plan-secondary monitoring-small"
                                                                            onClick={() => handleReport(item.itemId, "SKIPPED")}
                                                                            disabled={saving || !draft.date}
                                                                        >
                                                                            {t("markSkipped")}
                                                                        </button>
                                                                    </div>

                                                                </div>

                                                            )}

                                                        </div>

                                                    </li>

                                                );
                                            })}

                                        </ol>

                                    )}

                                </section>


                                <section className="action-plan-card">

                                    <h3 className="plan-section-title">
                                        {t("stateReadings")}
                                    </h3>

                                    <p className="action-plan-muted">
                                        {t("stateReadingsDescription")}
                                    </p>

                                    {monitoring.readings.length === 0 ? (

                                        <p className="action-plan-muted">
                                            {t("noReadings")}
                                        </p>

                                    ) : (

                                        <div className="monitoring-table-wrap">
                                            <table className="monitoring-table">
                                                <thead>
                                                    <tr>
                                                        <th>{t("recordedAt")}</th>
                                                        {READING_FIELDS.map(([field, unit]) => (
                                                            <th key={field}>
                                                                {t(field)}{unit && ` (${unit})`}
                                                            </th>
                                                        ))}
                                                    </tr>
                                                </thead>
                                                <tbody>
                                                    {monitoring.readings.map((reading) => (
                                                        <tr key={reading.id}>
                                                            <td>{formatDateTime(reading.recordedAt)}</td>
                                                            {READING_FIELDS.map(([field]) => (
                                                                <td key={field}>
                                                                    {reading[field] ?? "—"}
                                                                </td>
                                                            ))}
                                                        </tr>
                                                    ))}
                                                </tbody>
                                            </table>
                                        </div>

                                    )}

                                </section>

                            </>

                        )}

                    </>

                )}

            </main>

        </div>
    );
}

export default Monitoring;
