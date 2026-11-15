import { Outlet } from "react-router-dom";
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

                </header>

                <div className="page-content">
                    <Outlet />
                </div>
            </main>
        </div>
    );
}