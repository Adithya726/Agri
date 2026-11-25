import { Link } from "react-router-dom";

import { useLanguage } from "../../context/LanguageContext";

import "./PageState.css";

/*
 * Shared loading / empty / error card used by the pages.
 * variant: "loading" | "empty" | "error"
 */
export function PageState({ variant = "empty", title, message, actionLabel, actionTo, onRetry }) {

    const { t } = useLanguage();

    const isError = variant === "error";
    const isLoading = variant === "loading";

    return (
        <section
            className={`page-state page-state--${variant}`}
            role={isError ? "alert" : "status"}
            aria-live="polite"
            aria-busy={isLoading || undefined}
        >
            {isLoading && <span className="page-state-spinner" aria-hidden="true" />}

            {title && <h3>{title}</h3>}
            {message && <p>{message}</p>}

            {(onRetry || actionTo) && (
                <div className="page-state-actions">
                    {isError && onRetry && (
                        <button type="button" className="page-state-button" onClick={onRetry}>
                            {t("retry")}
                        </button>
                    )}
                    {actionTo && actionLabel && (
                        <Link className="page-state-button" to={actionTo}>
                            {actionLabel}
                        </Link>
                    )}
                </div>
            )}
        </section>
    );
}

/*
 * The state of the signed-in farmer's farm list: loading, failed, or empty.
 * Renders nothing once at least one farm is loaded, so a page can show its
 * content right after it.
 */
export function FarmListState({ loading, error, farms, onRetry }) {

    const { t } = useLanguage();

    if (loading) {
        return <PageState variant="loading" message={t("loadingYourFarms")} />;
    }

    if (error) {
        return (
            <PageState
                variant="error"
                title={t("farmsLoadFailed")}
                message={error.message || t("loadErrorHint")}
                onRetry={onRetry}
            />
        );
    }

    if (!farms || farms.length === 0) {
        return (
            <PageState
                variant="empty"
                title={t("noFarms")}
                message={t("noFarmsDescription")}
                actionLabel={t("addFarm")}
                actionTo="/farms"
            />
        );
    }

    return null;
}

export default PageState;
