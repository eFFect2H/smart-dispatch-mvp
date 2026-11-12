import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import {
    Users,
    Plus,
    Search,
    RefreshCw,
    MapPin,
    Clock3,
    CheckCircle2,
    XCircle,
} from "lucide-react";

import type { Team } from "../types";
import {
    createTeam,
    getTeams,
    type TeamCreateRequest,
} from "../api/teams";

type TeamForm = {
    name: string;
    capacity: string;
    startLatitude: string;
    startLongitude: string;
    shiftStart: string;
    shiftEnd: string;
    active: boolean;
};

const initialForm: TeamForm = {
    name: "",
    capacity: "3",
    startLatitude: "52.5200",
    startLongitude: "13.4050",
    shiftStart: "08:00",
    shiftEnd: "18:00",
    active: true,
};

function timeToMinutes(time: string): number {
    const [hours, minutes] = time.split(":").map(Number);
    return hours * 60 + minutes;
}

function formatTime(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;

    return `${String(hours).padStart(2, "0")}:${String(mins).padStart(2, "0")}`;
}

function getErrorMessage(error: unknown): string {
    if (typeof error === "object" && error !== null && "response" in error) {
        const response = (
            error as { response?: { data?: unknown } }
        ).response;

        const data = response?.data;

        if (typeof data === "string") {
            return data;
        }

        if (
            typeof data === "object" &&
            data !== null &&
            "message" in data &&
            typeof data.message === "string"
        ) {
            return data.message;
        }
    }

    if (error instanceof Error) {
        return error.message;
    }

    return "Произошла непредвиденная ошибка.";
}

export default function TeamsPage() {
    const [teams, setTeams] = useState<Team[]>([]);
    const [loading, setLoading] = useState(true);
    const [creating, setCreating] = useState(false);

    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    const [search, setSearch] = useState("");
    const [activityFilter, setActivityFilter] = useState("ALL");
    const [showForm, setShowForm] = useState(false);
    const [form, setForm] = useState<TeamForm>(initialForm);

    async function loadTeams() {
        setLoading(true);
        setError(null);

        try {
            const data = await getTeams();
            setTeams(data);
        } catch (err) {
            setError(getErrorMessage(err));
        } finally {
            setLoading(false);
        }
    }

    useEffect(() => {
        void loadTeams();
    }, []);

    const filteredTeams = teams.filter((team) => {
        const query = search.trim().toLowerCase();

        const matchesSearch =
            !query ||
            team.name.toLowerCase().includes(query) ||
            String(team.id).includes(query);

        const matchesActivity =
            activityFilter === "ALL" ||
            (activityFilter === "ACTIVE" && team.active) ||
            (activityFilter === "INACTIVE" && !team.active);

        return matchesSearch && matchesActivity;
    });

    const activeCount = teams.filter((team) => team.active).length;
    const inactiveCount = teams.length - activeCount;

    function updateField<K extends keyof TeamForm>(
        field: K,
        value: TeamForm[K],
    ) {
        setForm((current) => ({
            ...current,
            [field]: value,
        }));
    }

    async function handleCreate(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setError(null);
        setSuccess(null);

        const name = form.name.trim();
        const capacity = Number(form.capacity);
        const latitude = Number(form.startLatitude);
        const longitude = Number(form.startLongitude);

        const shiftStartMinute = timeToMinutes(form.shiftStart);
        const shiftEndMinute = timeToMinutes(form.shiftEnd);

        if (!name) {
            setError("Укажи название бригады.");
            return;
        }

        if (!Number.isInteger(capacity) || capacity <= 0) {
            setError("Вместимость должна быть положительным целым числом.");
            return;
        }

        if (
            !Number.isFinite(latitude) ||
            latitude < -90 ||
            latitude > 90 ||
            !Number.isFinite(longitude) ||
            longitude < -180 ||
            longitude > 180
        ) {
            setError(
                "Проверь координаты: широта от -90 до 90, долгота от -180 до 180.",
            );
            return;
        }

        if (shiftEndMinute <= shiftStartMinute) {
            setError("Конец смены должен быть позже её начала.");
            return;
        }

        const payload: TeamCreateRequest = {
            name,
            capacity,
            startLatitude: latitude,
            startLongitude: longitude,
            shiftStartMinute,
            shiftEndMinute,
            active: form.active,
        };

        setCreating(true);

        try {
            await createTeam(payload);

            // Повторно запрашиваем данные с backend, чтобы показать
            // фактически сохранённую запись.
            const updatedTeams = await getTeams();
            setTeams(updatedTeams);

            setForm(initialForm);
            setShowForm(false);
            setSuccess("Бригада успешно создана.");
        } catch (err) {
            setError(getErrorMessage(err));
        } finally {
            setCreating(false);
        }
    }

    return (
        <div className="teams-page">
            <div className="page-heading">
                <div>
                    <div className="eyebrow">УПРАВЛЕНИЕ ПЕРСОНАЛОМ</div>
                    <h1>Выездные бригады</h1>
                    <p>Состав, вместимость, рабочие смены и точки отправления.</p>
                </div>

                <div className="teams-actions">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={() => void loadTeams()}
                        disabled={loading}
                    >
                        <RefreshCw size={16} />
                        Обновить
                    </button>

                    <button
                        type="button"
                        className="primary-button"
                        onClick={() => {
                            setShowForm((current) => !current);
                            setError(null);
                            setSuccess(null);
                        }}
                    >
                        <Plus size={17} />
                        {showForm ? "Скрыть форму" : "Добавить бригаду"}
                    </button>
                </div>
            </div>

            {error && (
                <div className="error-banner" role="alert">
                    <span>{error}</span>
                    <button type="button" onClick={() => setError(null)}>
                        Закрыть
                    </button>
                </div>
            )}

            {success && (
                <div className="success-banner" role="status">
                    <span>{success}</span>
                    <button type="button" onClick={() => setSuccess(null)}>
                        Закрыть
                    </button>
                </div>
            )}

            <div className="team-summary-grid">
                <div className="team-summary-card">
                    <div className="team-summary-icon">
                        <Users size={20} />
                    </div>
                    <div>
                        <div className="team-summary-label">Всего бригад</div>
                        <div className="team-summary-value">{teams.length}</div>
                    </div>
                </div>

                <div className="team-summary-card">
                    <div className="team-summary-icon team-summary-icon-active">
                        <CheckCircle2 size={20} />
                    </div>
                    <div>
                        <div className="team-summary-label">Активные</div>
                        <div className="team-summary-value">{activeCount}</div>
                    </div>
                </div>

                <div className="team-summary-card">
                    <div className="team-summary-icon team-summary-icon-inactive">
                        <XCircle size={20} />
                    </div>
                    <div>
                        <div className="team-summary-label">Неактивные</div>
                        <div className="team-summary-value">{inactiveCount}</div>
                    </div>
                </div>
            </div>

            {showForm && (
                <section className="panel team-form-panel">
                    <div className="panel-heading">
                        <div>
                            <h2>Новая бригада</h2>
                            <p>Укажи параметры бригады и время её рабочей смены.</p>
                        </div>
                    </div>

                    <form onSubmit={handleCreate}>
                        <div className="team-form-grid">
                            <label className="form-field team-form-field-wide">
                                Название бригады
                                <input
                                    className="form-control"
                                    value={form.name}
                                    onChange={(event) => updateField("name", event.target.value)}
                                    placeholder="Например, Южная бригада"
                                    maxLength={150}
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Вместимость
                                <input
                                    className="form-control"
                                    type="number"
                                    min="1"
                                    step="1"
                                    value={form.capacity}
                                    onChange={(event) =>
                                        updateField("capacity", event.target.value)
                                    }
                                    required
                                />
                                <span className="field-hint">
                  Максимальная нагрузка бригады в единицах модели.
                </span>
                            </label>

                            <label className="form-field">
                                Статус
                                <span className="team-checkbox-field">
                  <input
                      type="checkbox"
                      checked={form.active}
                      onChange={(event) =>
                          updateField("active", event.target.checked)
                      }
                  />
                  Бригада активна
                </span>
                            </label>

                            <div className="team-form-field-wide team-form-section-title">
                                Координаты точки отправления
                            </div>

                            <label className="form-field">
                                Широта
                                <input
                                    className="form-control"
                                    type="number"
                                    step="any"
                                    min="-90"
                                    max="90"
                                    value={form.startLatitude}
                                    onChange={(event) =>
                                        updateField("startLatitude", event.target.value)
                                    }
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Долгота
                                <input
                                    className="form-control"
                                    type="number"
                                    step="any"
                                    min="-180"
                                    max="180"
                                    value={form.startLongitude}
                                    onChange={(event) =>
                                        updateField("startLongitude", event.target.value)
                                    }
                                    required
                                />
                            </label>

                            <div className="team-form-field-wide team-form-section-title">
                                Рабочая смена
                            </div>

                            <label className="form-field">
                                Начало смены
                                <input
                                    className="form-control"
                                    type="time"
                                    value={form.shiftStart}
                                    onChange={(event) =>
                                        updateField("shiftStart", event.target.value)
                                    }
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Конец смены
                                <input
                                    className="form-control"
                                    type="time"
                                    value={form.shiftEnd}
                                    onChange={(event) =>
                                        updateField("shiftEnd", event.target.value)
                                    }
                                    required
                                />
                            </label>
                        </div>

                        <div className="team-form-footer">
              <span className="form-hint">
                Координаты задают базовую точку, откуда начинается маршрут.
              </span>

                            <button
                                type="submit"
                                className="primary-button"
                                disabled={creating}
                            >
                                <Plus size={17} />
                                {creating ? "Создание..." : "Создать бригаду"}
                            </button>
                        </div>
                    </form>
                </section>
            )}

            <section className="panel">
                <div className="panel-heading teams-list-heading">
                    <div>
                        <h2>Список бригад</h2>
                        <p>
                            Показано {filteredTeams.length} из {teams.length}
                        </p>
                    </div>

                    <div className="team-filters">
                        <label className="search-box">
                            <Search size={17} />
                            <input
                                value={search}
                                onChange={(event) => setSearch(event.target.value)}
                                placeholder="Поиск по названию или ID"
                                aria-label="Поиск бригад"
                            />
                        </label>

                        <select
                            className="filter-select"
                            value={activityFilter}
                            onChange={(event) => setActivityFilter(event.target.value)}
                            aria-label="Фильтр по активности"
                        >
                            <option value="ALL">Все бригады</option>
                            <option value="ACTIVE">Активные</option>
                            <option value="INACTIVE">Неактивные</option>
                        </select>
                    </div>
                </div>

                {loading ? (
                    <div className="empty-state">
                        <RefreshCw size={21} />
                        Загрузка бригад...
                    </div>
                ) : filteredTeams.length === 0 ? (
                    <div className="empty-state">
                        <Users size={27} />
                        <span>
              {teams.length === 0
                  ? "Бригад пока нет. Создай первую бригаду."
                  : "По заданным условиям бригады не найдены."}
            </span>
                    </div>
                ) : (
                    <div className="table-wrapper">
                        <table className="data-table teams-table">
                            <thead>
                            <tr>
                                <th>Бригада</th>
                                <th>Вместимость</th>
                                <th>Точка отправления</th>
                                <th>Рабочая смена</th>
                                <th>Статус</th>
                            </tr>
                            </thead>

                            <tbody>
                            {filteredTeams.map((team) => (
                                <tr key={team.id}>
                                    <td>
                                        <div className="team-name-cell">{team.name}</div>
                                        <div className="request-id">ID: {team.id}</div>
                                    </td>

                                    <td>
                      <span className="capacity-badge">
                        <Users size={14} />
                          {team.capacity}
                      </span>
                                    </td>

                                    <td>
                                        <div className="address-cell">
                                            <MapPin size={14} />
                                            <span>
                          {team.startLatitude.toFixed(4)},{" "}
                                                {team.startLongitude.toFixed(4)}
                        </span>
                                        </div>
                                    </td>

                                    <td>
                                        <div className="time-cell">
                                            <Clock3 size={14} />
                                            {formatTime(team.shiftStartMinute)}
                                            {" – "}
                                            {formatTime(team.shiftEndMinute)}
                                        </div>
                                    </td>

                                    <td>
                      <span
                          className={`status-badge ${
                              team.active ? "team-status-active" : "team-status-inactive"
                          }`}
                      >
                        {team.active ? "Активна" : "Неактивна"}
                      </span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </div>
    );
}