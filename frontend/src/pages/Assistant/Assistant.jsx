import { useState } from "react";

import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import Sidebar from "../../components/Sidebar/Sidebar";
import LanguageSelector from "../../components/LanguageSelector/LanguageSelector";
import FarmPicker from "../../components/FarmPicker/FarmPicker";
import { FarmListState } from "../../components/PageState/PageState";

import useFarmerFarms, { errorMessage } from "../../hooks/useFarmerFarms";
import { askFarmAssistant } from "../../services/insightsService";

import "../ActionPlan/ActionPlan.css";
import "../History/History.css";
import "./Assistant.css";

const SUGGESTION_KEYS = [
    "assistantQNext",
    "assistantQRejected",
    "assistantQProgress",
    "assistantQRisks",
    "assistantQChanged",
    "assistantQMoisture"
];

function Assistant() {

    const { farmer } = useAuth();
    const { t } = useLanguage();

    const { farms, selectedFarmId, setSelectedFarmId, loadingFarms, farmsError, reloadFarms } = useFarmerFarms(
        farmer?.userId,
        (error) => toast.error(errorMessage(error, t("farmsLoadFailed")))
    );

    const [question, setQuestion] = useState("");
    const [conversation, setConversation] = useState([]);
    const [asking, setAsking] = useState(false);

    // Answers belong to one farm; start fresh when the farm changes.
    const changeFarm = (farmId) => {
        setConversation([]);
        setSelectedFarmId(farmId);
    };

    const ask = async (text) => {

        const q = text.trim();
        if (!q || !selectedFarmId) {
            return;
        }

        try {
            setAsking(true);
            const answer = await askFarmAssistant(selectedFarmId, q);
            setConversation((current) => [answer, ...current]);
            setQuestion("");
        } catch (error) {
            console.error(error);
            toast.error(errorMessage(error, t("assistantFailed")));
        } finally {
            setAsking(false);
        }
    };

    const handleSubmit = (event) => {
        event.preventDefault();
        ask(question);
    };

    return (
        <div className="action-plan-layout">

            <Sidebar />

            <main className="action-plan-main">

                <header className="action-plan-header">
                    <div>
                        <h1>{t("farmAssistant")}</h1>
                        <p>{t("assistantDescription")}</p>
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
                            id="assistant-farm"
                            farms={farms}
                            value={selectedFarmId}
                            onChange={changeFarm}
                            description={t("assistantSelectFarmDescription")}
                        />

                        <section className="action-plan-card">
                            <form className="assistant-form" onSubmit={handleSubmit}>
                                <input
                                    type="text"
                                    aria-label={t("assistantPlaceholder")}
                                    placeholder={t("assistantPlaceholder")}
                                    value={question}
                                    maxLength={500}
                                    onChange={(e) => setQuestion(e.target.value)}
                                />
                                <button type="submit" className="history-button" disabled={asking || !question.trim()}>
                                    {asking ? t("assistantAsking") : t("assistantAsk")}
                                </button>
                            </form>

                            <p className="assistant-label">{t("assistantSuggestions")}</p>
                            <div className="assistant-suggestions">
                                {SUGGESTION_KEYS.map((key) => (
                                    <button
                                        key={key}
                                        type="button"
                                        className="assistant-chip"
                                        disabled={asking}
                                        onClick={() => ask(t(key))}
                                    >
                                        {t(key)}
                                    </button>
                                ))}
                            </div>

                            <p className="history-ml-note">{t("assistantDataOnly")}</p>
                        </section>

                        {conversation.length === 0 && (
                            <section className="action-plan-card action-plan-empty">{t("assistantEmpty")}</section>
                        )}

                        {conversation.map((entry, index) => (
                            <section
                                key={`${conversation.length - index}`}
                                className={`action-plan-card assistant-answer ${entry.answered ? "" : "assistant-not-covered"}`}
                            >
                                <p className="assistant-question">{entry.question}</p>
                                <span className="history-badge">{t(`intent${entry.intent}`)}</span>
                                {!entry.answered && (
                                    <span className="history-badge assistant-badge-warn">{t("assistantNotCovered")}</span>
                                )}
                                <p className="assistant-text">{entry.answer}</p>
                                {entry.facts.length > 0 && (
                                    <ul className="whatif-list assistant-facts">
                                        {entry.facts.map((fact, i) => <li key={`${i}-${fact}`}>{fact}</li>)}
                                    </ul>
                                )}
                                {entry.sources.length > 0 && (
                                    <p className="assistant-sources">
                                        {t("assistantSources")}: {entry.sources.join(", ")}
                                    </p>
                                )}
                            </section>
                        ))}
                    </>
                )}
            </main>
        </div>
    );
}

export default Assistant;
