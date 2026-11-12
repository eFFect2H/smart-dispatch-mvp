import { Outlet } from "react-router-dom";
import { Bell, Search } from "lucide-react";
import Sidebar from "./Sidebar";

export default function AppLayout() {
    return (
        <div className="app-shell">
            <Sidebar />

            <main className="main-area">
                <header className="topbar">
                    <div>
                        <div className="topbar-title">Диспетчерская</div>
                        <div className="topbar-subtitle">
                            Управление заявками и выездными бригадами
                        </div>
                    </div>

                    <div className="topbar-actions">
                        <button className="icon-button" aria-label="Поиск">
                            <Search size={19} />
                        </button>
                        <button className="icon-button" aria-label="Уведомления">
                            <Bell size={19} />
                        </button>
                        <div className="avatar">Д</div>
                    </div>
                </header>

                <div className="page-content">
                    <Outlet />
                </div>
            </main>
        </div>
    );
}