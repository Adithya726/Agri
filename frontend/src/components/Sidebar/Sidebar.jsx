import { useEffect, useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { toast } from "react-hot-toast";

import { useAuth } from "../../context/AuthContext";
import { useLanguage } from "../../context/LanguageContext";

import "./Sidebar.css";

function Sidebar() {
    const navigate = useNavigate();

    const { logout } = useAuth();
    const { t } = useLanguage();

    // Small screens show the sidebar as a drawer opened from the top bar.
    const [open, setOpen] = useState(false);

    const closeMenu = () => setOpen(false);

    useEffect(() => {
        if (!open) {
            return undefined;
        }

        const onKeyDown = (event) => {
            if (event.key === "Escape") {
                setOpen(false);
            }
        };

        document.addEventListener("keydown", onKeyDown);
        document.body.classList.add("sidebar-drawer-open");

        return () => {
            document.removeEventListener("keydown", onKeyDown);
            document.body.classList.remove("sidebar-drawer-open");
        };
    }, [open]);

    const handleLogout = () => {
        closeMenu();

        logout();

        toast.success(t("logout"));

        navigate("/login");
    };

    return (
        <>
        {/* Top bar with the menu button, shown on small screens only */}
        <div className="sidebar-topbar">
            <button
                type="button"
                className="sidebar-toggle"
                aria-label={open ? t("closeMenu") : t("openMenu")}
                aria-expanded={open}
                aria-controls="app-sidebar"
                onClick={() => setOpen((value) => !value)}
            >
                <span aria-hidden="true">{open ? "✕" : "☰"}</span>
            </button>

            <span className="sidebar-topbar-brand">
                <span aria-hidden="true">🌱</span> {t("appName")}
            </span>
        </div>

        {open && (
            <div
                className="sidebar-overlay"
                aria-hidden="true"
                onClick={closeMenu}
            />
        )}

        <aside
            id="app-sidebar"
            className={`sidebar ${open ? "is-open" : ""}`}
        >

            {/* Brand */}
            <div className="sidebar-brand">

                <div className="sidebar-logo">
                    🌱
                </div>

                <div className="sidebar-brand-text">
                    <h1>{t("appName")}</h1>

                    <span>
                        {t("appSubtitle")}
                    </span>
                </div>

            </div>


            {/* Navigation */}
            <nav className="sidebar-navigation">

                {/* Dashboard */}
                <NavLink
                    onClick={closeMenu}
                    to="/dashboard"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🏠</span>
                    <span>{t("dashboard")}</span>
                </NavLink>


                {/* Farmer Profile */}
                <NavLink
                    onClick={closeMenu}
                    to="/farmer-profile"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">👤</span>
                    <span>{t("farmerProfile")}</span>
                </NavLink>


                {/* My Farms */}
                <NavLink
                    onClick={closeMenu}
                    to="/farms"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🌾</span>
                    <span>{t("myFarms")}</span>
                </NavLink>


                {/* Farm State */}
                <NavLink
                    onClick={closeMenu}
                    to="/farm-state"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🌱</span>
                    <span>{t("farmState")}</span>
                </NavLink>


                {/* Predictions */}
                <NavLink
                    onClick={closeMenu}
                    to="/predictions"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🤖</span>
                    <span>{t("predictions")}</span>
                </NavLink>


                {/* Context Interpretation */}
                <NavLink
                    onClick={closeMenu}
                    to="/context-interpretation"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🧠</span>
                    <span>{t("contextInterpretation")}</span>
                </NavLink>


                {/* Agricultural Action Knowledge */}
                <NavLink
                    onClick={closeMenu}
                    to="/agricultural-actions"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">📚</span>
                    <span>{t("agriculturalActions")}</span>
                </NavLink>


                {/* Constraint / Feasibility Validation */}
                <NavLink
                    onClick={closeMenu}
                    to="/constraint-validation"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">⚖️</span>
                    <span>{t("constraintValidation")}</span>
                </NavLink>


                {/* Decision Engine */}
                <NavLink
                    onClick={closeMenu}
                    to="/decision-engine"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🎯</span>
                    <span>{t("decisionEngine")}</span>
                </NavLink>


                {/* Action Plan */}
                <NavLink
                    onClick={closeMenu}
                    to="/action-plan"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">📋</span>
                    <span>{t("actionPlan")}</span>
                </NavLink>


                {/* Monitoring */}
                <NavLink
                    onClick={closeMenu}
                    to="/monitoring"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">📊</span>
                    <span>{t("monitoring")}</span>
                </NavLink>


                {/* History */}
                <NavLink
                    onClick={closeMenu}
                    to="/history"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">📜</span>
                    <span>{t("history")}</span>
                </NavLink>


                {/* What-If Analysis */}
                <NavLink
                    onClick={closeMenu}
                    to="/what-if"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">🔄</span>
                    <span>{t("whatIfAnalysis")}</span>
                </NavLink>


                {/* Farm Assistant */}
                <NavLink
                    onClick={closeMenu}
                    to="/assistant"
                    className={({ isActive }) =>
                        `sidebar-link ${
                            isActive ? "active" : ""
                        }`
                    }
                >
                    <span className="sidebar-icon">💬</span>
                    <span>{t("farmAssistant")}</span>
                </NavLink>

            </nav>


            {/* Bottom Actions */}
            <div className="sidebar-bottom">


                {/* Logout */}
                <button
                    type="button"
                    className="sidebar-logout"
                    onClick={handleLogout}
                >
                    <span className="sidebar-icon">🚪</span>
                    <span>{t("logout")}</span>
                </button>

            </div>

        </aside>
        </>
    );
}

export default Sidebar;