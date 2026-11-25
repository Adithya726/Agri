import { useCallback, useEffect, useState } from "react";

import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";
import FarmPicker from "../../components/FarmPicker/FarmPicker";
import { FarmListState } from "../../components/PageState/PageState";

import useFarmerFarms, { errorMessage, formatDateTime } from "../../hooks/useFarmerFarms";
import { getFarmHistory } from "../../services/insightsService";

import "../ActionPlan/ActionPlan.css";
import "./History.css";

const TYPE_FILTERS = [
    ["", "historyAllTypes"],
    ["STATE", "historyTypeState"],
    ["PLAN", "historyTypePlan"],
    ["ACTION", "historyTypeAction"]
];

const EMPTY_FILTERS = { type: "", from: "", to: "" };

function History() {

    const { farmer } = useAuth();
    const { t } = useLanguage();

    const { farms, selectedFarmId, setSelectedFarmId, loadingFarms, farmsError, reloadFarms } = useFarmerFarms(
        farmer?.userId,
        (error) => toast.error(errorMessage(error, t("farmsLoadFailed")))
    );

    const [filters, setFilters] = useState(EMPTY_FILTERS);
    const [history, setHistory] = useState(null);
    const [loading, setLoading] = useState(false);

    const load = useCallback(async (farmId, applied) => {

        if (!farmId) {
            setHistory(null);
            return;
        }

        const params = {};
        if (applied.type) params.type = applied.type;
        if (applied.from) params.from = applied.from;
        if (applied.to) params.to = applied.to;

        try {
            setLoading(true);
            setHistory(await getFarmHistory(farmId, params));
        } catch (error) {
            console.error(error);
            toast.error(errorMessage(error, t("historyLoadFailed")));
        } finally {
            setLoading(false);
        }

        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        setFilters(EMPTY_FILTERS);
        load(selectedFarmId, EMPTY_FILTERS);
    }, [selectedFarmId, load]);

    const applyFilters = (event) => {
        event.preventDefault();
        load(selectedFarmId, filters);
    };

    const clearFilters = () => {
        setFilters(EMPTY_FILTERS);
        load(selectedFarmId, EMPTY_FILTERS);
    };

    return (
        <div className="action-plan-layout">

            <Sidebar />

            <main className="action-plan-main">

                <header className="action-plan-header">
                    <div>
                        <h1>{t("history")}</h1>
                        <p>{t("historyDescription")}</p>
                    </div>
                    <LanguageSelector />
                </header>

                <FarmListState
                    loading={loadingFarms}
                    error={farmsError}
                    farms={farms}
                    onRetry={reloadFarms}
                />

                {!loadingFarms && !farmsError && farms.length > 0 && (
                    <>
                        <FarmPicker
                            id="history-farm"
                            farms={farms}
                            value={selectedFarmId}
                            onChange={setSelectedFarmId}
                            description={t("historySelectFarmDescription")}
                        />

                        <form className="action-plan-card history-filters" onSubmit={applyFilters}>
                            <label>
                                <span>{t("historyFilterType")}</span>
                                <select
                                    value={filters.type}
                                    onChange={(e) => setFilters({ ...filters, type: e.target.value })}
                                >
                                    {TYPE_FILTERS.map(([value, key]) => (
                                        <option key={key} value={value}>{t(key)}</option>
                                    ))}
                                </select>
                            </label>
                            <label>
                                <span>{t("historyFrom")}</span>
                                <input
                                    type="date"
                                    value={filters.from}
                                    onChange={(e) => setFilters({ ...filters, from: e.target.value })}
                                />
                            </label>
                            <label>
                                <span>{t("historyTo")}</span>
                                <input
                                    type="date"
                                    value={filters.to}
                                    onChange={(e) => setFilters({ ...filters, to: e.target.value })}
                                />
                            </label>
                            <div className="history-filter-buttons">
                                <button type="submit" className="history-button" disabled={loading}>
                                    {t("historyApply")}
                                </button>
                                <button type="button" className="history-button history-button-ghost" onClick={clearFilters}>
                                    {t("historyClear")}
                                </button>
                            </div>
                        </form>

                        <p className="history-ml-note">{t("historyMlNote")}</p>

                        {loading && !history && (
                            <section className="action-plan-card action-plan-empty">{t("loadingHistory")}</section>
                        )}

                        {history && (
                            <section className="action-plan-card">
                                <h2 className="history-count">
                                    {history.total} {t("historyEventsCount")}
                                </h2>

                                {history.events.length === 0 && (
                                    <p className="action-plan-muted">{t("historyNoEvents")}</p>
                                )}

                                <ol className="history-timeline">
                                    {history.events.map((event, index) => (
                                        <li
                                            key={`${event.type}-${event.planId ?? ""}-${event.readingId ?? ""}-${event.itemId ?? ""}-${index}`}
                                            className={`history-event history-${event.group.toLowerCase()}`}
                                        >
                                            <div className="history-event-head">
                                                <span className="history-badge">{t(`event${event.type}`)}</span>
                                                <time>
                                                    {formatDateTime(event.at)}
                                                    {event.timeInferred && ` (${t("historyTimeInferred")})`}
                                                </time>
                                            </div>
                                            <h4>{event.title}</h4>
                                            <p>{event.detail}</p>
                                            {event.note && (
                                                <p className="history-note">
                                                    <strong>{t("historyNote")}:</strong> {event.note}
                                                </p>
                                            )}
                                            {event.changes?.length > 0 && (
                                                <ul className="history-changes">
                                                    {event.changes.map((change) => (
                                                        <li key={change.field}>
                                                            {t(change.field)}: {change.before ?? "—"} → {change.after ?? "—"}
                                                        </li>
                                                    ))}
                                                </ul>
                                            )}
                                        </li>
                                    ))}
                                </ol>
                            </section>
                        )}
                    </>
                )}
            </main>
        </div>
    );
}

export default History;
