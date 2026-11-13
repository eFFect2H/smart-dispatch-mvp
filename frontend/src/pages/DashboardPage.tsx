
import { useEffect, useState } from "react";
import {
    ClipboardList,
    Users,
    Route,
    Clock3,
    ArrowRight,
    RefreshCw,
} from "lucide-react";
import { Link } from "react-router-dom";
import StatCard from "../components/ui/StatCard";
import { getTeams } from "../api/teams";
import { getRequests } from "../api/requests";
import type { Team, TransportRequest } from "../types";

function formatTime(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;

    return `${String(hours).padStart(2, "0")}:${String(mins).padStart(2, "0")}`;
}

function getStatusLabel(status: string): string {
    const labels: Record<string, string> = {
        NEW: "Новая",
        PLANNED: "Запланирована",
        COMPLETED: "Завершена",
    };

    return labels[status] ?? status;
}

export default function DashboardPage() {
    const [teams, setTeams] = useState<Team[]>([]);
    const [requests, setRequests] = useState<TransportRequest[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    async function loadData() {
        try {
            const [teamsData, requestsData] = await Promise.all([
                getTeams(),
                getRequests(),
            ]);

            setTeams(teamsData);
            setRequests(requestsData);
        } catch (err) {
            console.error("Ошибка загрузки данных:", err);
            setError(
                "Не удалось загрузить данные. Проверь, запущен ли backend и доступен ли API."
            );
        } finally {
            setLoading(false);
        }
    }

    function refreshData() {
        setLoading(true);
        setError(null);
        void loadData();
    }

    useEffect(() => {
        void loadData();
    }, []);

    const safeTeams = Array.isArray(teams) ? teams : [];
    const safeRequests = Array.isArray(requests) ? requests : [];

    const activeTeams = safeTeams.filter((team) => team.active).length;

    const plannedRequests = safeRequests.filter(
        (request) => request.status === "PLANNED"
    ).length;

    const unassignedRequests = safeRequests.filter(
        (request) => request.status === "NEW"
    ).length;

    const latestRequests = [...safeRequests]
        .sort((a, b) => b.id - a.id)
        .slice(0, 5);

    return (
        <div className="dashboard">
            <div className="page-heading">
                <div>
                    <div className="eyebrow">ОБЗОР СИСТЕМЫ</div>
                    <h1>Главная панель</h1>
                    <p>Состояние заявок и выездных бригад</p>
                </div>

                <div className="dashboard-actions">
                    <button
                        className="secondary-button"
                        onClick={refreshData}
                        disabled={loading}
                    >
                        <RefreshCw size={16} />
                        Обновить
                    </button>

                    <Link to="/dispatch" className="primary-button">
                        <Route size={17} />
                        Оптимизировать маршруты
                    </Link>
                </div>
            </div>

            {error && (
                <div className="error-banner" role="alert">
                    <span>{error}</span>
                    <button onClick={() => void loadData()}>Повторить</button>
                </div>
            )}

            <div className="stats-grid">
                <StatCard
                    title="Всего заявок"
                    value={loading ? "…" : requests.length}
                    description="Все заявки в системе"
                    icon={ClipboardList}
                />

                <StatCard
                    title="Активные бригады"
                    value={loading ? "…" : activeTeams}
                    description={`Всего бригад: ${teams.length}`}
                    icon={Users}
                />

                <StatCard
                    title="Запланировано"
                    value={loading ? "…" : plannedRequests}
                    description="Заявки со статусом PLANNED"
                    icon={Route}
                />

                <StatCard
                    title="Ожидают назначения"
                    value={loading ? "…" : unassignedRequests}
                    description="Заявки со статусом NEW"
                    icon={Clock3}
                />
            </div>

            <section className="panel welcome-panel">
                <div className="welcome-icon">
                    <Route size={24} />
                </div>

                <div className="welcome-content">
                    <h2>Умная диспетчеризация</h2>
                    <p>
                        В системе {requests.length} заявок и {activeTeams} активных
                        бригад. Запусти оптимизацию, чтобы рассчитать распределение
                        заявок с учётом временных окон.
                    </p>
                </div>

                <Link to="/dispatch" className="text-link">
                    К диспетчеризации <ArrowRight size={17} />
                </Link>
            </section>

            <section className="panel">
                <div className="panel-heading">
                    <div>
                        <h2>Последние заявки</h2>
                        <p>Данные загружаются из Spring Boot API</p>
                    </div>

                    <Link to="/requests" className="secondary-button">
                        Все заявки
                    </Link>
                </div>

                {loading ? (
                    <div className="empty-state">Загрузка данных…</div>
                ) : !error && latestRequests.length === 0 ? (
                    <div className="empty-state">
                        <ClipboardList size={28} />
                        <span>Заявок пока нет</span>
                    </div>
                ) : !error ? (
                    <div className="table-wrapper">
                        <table className="data-table">
                            <thead>
                            <tr>
                                <th>Заявка</th>
                                <th>Адрес</th>
                                <th>Временное окно</th>
                                <th>Бригада</th>
                                <th>Статус</th>
                            </tr>
                            </thead>

                            <tbody>
                            {latestRequests.map((request) => (
                                <tr key={request.id}>
                                    <td>
                                        <div className="request-title">{request.title}</div>
                                        <div className="request-id">
                                            ID: {request.id}
                                        </div>
                                    </td>

                                    <td>{request.address}</td>

                                    <td>
                                        {formatTime(request.windowStartMinute)}–{formatTime(
                                        request.windowEndMinute
                                    )}
                                    </td>

                                    <td>
                                        {request.assignedTeamId == null
                                            ? "Не назначена"
                                            : teams.find(
                                            (team) => team.id === request.assignedTeamId
                                        )?.name ?? `Бригада #${request.assignedTeamId}`}
                                    </td>

                                    <td>
                      <span
                          className={`status-badge status-${request.status.toLowerCase()}`}
                      >
                        {getStatusLabel(request.status)}
                      </span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                ) : null}
            </section>
        </div>
    );
}
