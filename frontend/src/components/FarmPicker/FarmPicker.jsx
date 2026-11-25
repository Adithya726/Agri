import { useLanguage } from "../../context/LanguageContext";

// Farm selector card shared by the History, What-if and Assistant pages.
function FarmPicker({ id, farms, value, onChange, description }) {

    const { t } = useLanguage();

    return (
        <section className="action-plan-card action-plan-selector">
            <div>
                <label htmlFor={id}>{t("selectFarm")}</label>
                <p>{description}</p>
            </div>
            <div className="action-plan-selector-controls">
                <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
                    {farms.map((farm) => (
                        <option key={farm.id} value={farm.id}>
                            {farm.farmName} — {farm.location}
                        </option>
                    ))}
                </select>
            </div>
        </section>
    );
}

export default FarmPicker;
