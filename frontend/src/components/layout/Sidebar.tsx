import {
    LayoutDashboard,
    ClipboardList,
    Users,
    Route,
    Truck,
} from "lucide-react";
import { NavLink } from "react-router-dom";

const links = [
    { to: "/", label: "Обзор", icon: LayoutDashboard },
    { to: "/requests", label: "Заявки", icon: ClipboardList },
    { to: "/teams", label: "Бригады", icon: Users },
    { to: "/dispatch", label: "Рабочее место", icon: Route },
];

export default function Sidebar() {
    return (
        <aside className="sidebar">
            <div className="brand">
                <div className="brand-icon">
                    <Truck size={23} />
                </div>
                <div>
                    <div className="brand-name">Smart Dispatch</div>
                    <div className="brand-caption">Управление выездами</div>
                </div>
            </div>

            <div className="nav-caption">РАБОЧЕЕ ПРОСТРАНСТВО</div>

            <nav className="navigation">
                {links.map(({ to, label, icon: Icon }) => (
                    <NavLink
                        key={to}
                        to={to}
                        end={to === "/"}
                        className={({ isActive }) =>
                            `nav-link${isActive ? " active" : ""}`
                        }
                    >
                        <Icon size={19} />
                        <span>{label}</span>
                    </NavLink>
                ))}
            </nav>

            <div className="sidebar-footer">
                <span className="status-dot" />
                <span>Диспетчерская система</span>
            </div>
        </aside>

    );
}