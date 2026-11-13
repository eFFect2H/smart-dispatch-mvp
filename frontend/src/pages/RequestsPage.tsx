import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import {
    Search,
    RefreshCw,
    Plus,
    ClipboardList,
    MapPin,
    Clock3,
} from "lucide-react";
import type {
    TransportRequest,
    TransportRequestCreateRequest,
} from "../types";
import { getRequests, createRequest } from "../api/requests";

type RequestForm = {
    title: string;
    address: string;
    latitude: string;
    longitude: string;
    windowStart: string;
    windowEnd: string;
    serviceDurationMinutes: string;
    demand: string;
};

const initialForm: RequestForm = {
    title: "",
    address: "",
    latitude: "52.5200",
    longitude: "13.4050",
    windowStart: "08:00",
    windowEnd: "18:00",
    serviceDurationMinutes: "30",
    demand: "1",
};

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

function timeToMinutes(time: string): number {
    const [hours, minutes] = time.split(":").map(Number);
    return hours * 60 + minutes;
}

export default function RequestsPage() {
    const [requests, setRequests] = useState<TransportRequest[]>([]);
    const [loading, setLoading] = useState(true);
    const [creating, setCreating] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    const [search, setSearch] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [showForm, setShowForm] = useState(false);
    const [form, setForm] = useState<RequestForm>(initialForm);

    async function loadRequests() {
        try {
            const data = await getRequests();
            setRequests(data);
        } catch (err) {
            setError(getErrorMessage(err));
        } finally {
            setLoading(false);
        }
    }

    async function refreshRequests() {
        setLoading(true);
        setError(null);
        await loadRequests();
    }

    useEffect(() => {
        let isActive = true;

        getRequests()
            .then((data) => {
                if (isActive) setRequests(data);
            })
            .catch((err: unknown) => {
                if (isActive) setError(getErrorMessage(err));
            })
            .finally(() => {
                if (isActive) setLoading(false);
            });

        return () => {
            isActive = false;
        };
    }, []);

    const filteredRequests = requests.filter((request) => {
        const query = search.trim().toLowerCase();

        const matchesSearch =
            !query ||
            request.title.toLowerCase().includes(query) ||
            request.address.toLowerCase().includes(query) ||
            String(request.id).includes(query);

        const matchesStatus =
            statusFilter === "ALL" || request.status === statusFilter;

        return matchesSearch && matchesStatus;
    });

    function updateField(
        field: keyof RequestForm,
        value: string,
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

        const start = timeToMinutes(form.windowStart);
        const end = timeToMinutes(form.windowEnd);
        const latitude = Number(form.latitude);
        const longitude = Number(form.longitude);
        const duration = Number(form.serviceDurationMinutes);
        const demand = Number(form.demand);

        if (end <= start) {
            setError("Конец временного окна должен быть позже его начала.");
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
            setError("Проверь координаты: широта от -90 до 90, долгота от -180 до 180.");
            return;
        }

        if (!Number.isInteger(duration) || duration <= 0) {
            setError("Продолжительность обслуживания должна быть положительным целым числом.");
            return;
        }

        if (!Number.isInteger(demand) || demand <= 0) {
            setError("Потребность должна быть положительным целым числом.");
            return;
        }

        const payload: TransportRequestCreateRequest = {
            title: form.title.trim(),
            address: form.address.trim(),
            latitude,
            longitude,
            windowStartMinute: start,
            windowEndMinute: end,
            serviceDurationMinutes: duration,
            demand,
        };

        setCreating(true);

        try {
            await createRequest(payload);
            setForm(initialForm);
            setShowForm(false);
            setSuccess("Заявка успешно создана.");

            await refreshRequests();
        } catch (err) {
            setError(getErrorMessage(err));
        } finally {
            setCreating(false);
        }
    }

    return (
        <div className="requests-page">
            <div className="page-heading">
                <div>
                    <div className="eyebrow">УПРАВЛЕНИЕ ЗАЯВКАМИ</div>
                    <h1>Заявки</h1>
                    <p>Создание, поиск и просмотр заявок на выездные работы.</p>
                </div>

                <div className="requests-actions">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={() => void refreshRequests()}
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
                        {showForm ? "Скрыть форму" : "Новая заявка"}
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

            {showForm && (
                <section className="panel request-form-panel">
                    <div className="panel-heading">
                        <div>
                            <h2>Новая заявка</h2>
                            <p>Заполни информацию о месте и времени выполнения.</p>
                        </div>
                    </div>

                    <form onSubmit={handleCreate}>
                        <div className="request-form-grid">
                            <label className="form-field form-field-wide">
                                Название заявки
                                <input
                                    className="form-control"
                                    value={form.title}
                                    onChange={(e) => updateField("title", e.target.value)}
                                    placeholder="Например, осмотр объекта"
                                    required
                                    maxLength={200}
                                />
                            </label>

                            <label className="form-field form-field-wide">
                                Адрес
                                <input
                                    className="form-control"
                                    value={form.address}
                                    onChange={(e) => updateField("address", e.target.value)}
                                    placeholder="Например, Potsdamer Platz, Berlin"
                                    required
                                    maxLength={500}
                                />
                            </label>

                            <label className="form-field">
                                Широта
                                <input
                                    className="form-control"
                                    type="number"
                                    step="any"
                                    min="-90"
                                    max="90"
                                    value={form.latitude}
                                    onChange={(e) => updateField("latitude", e.target.value)}
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
                                    value={form.longitude}
                                    onChange={(e) => updateField("longitude", e.target.value)}
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Начало временного окна
                                <input
                                    className="form-control"
                                    type="time"
                                    value={form.windowStart}
                                    onChange={(e) => updateField("windowStart", e.target.value)}
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Конец временного окна
                                <input
                                    className="form-control"
                                    type="time"
                                    value={form.windowEnd}
                                    onChange={(e) => updateField("windowEnd", e.target.value)}
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Обслуживание, минут
                                <input
                                    className="form-control"
                                    type="number"
                                    min="1"
                                    step="1"
                                    value={form.serviceDurationMinutes}
                                    onChange={(e) =>
                                        updateField("serviceDurationMinutes", e.target.value)
                                    }
                                    required
                                />
                            </label>

                            <label className="form-field">
                                Потребность в ресурсах
                                <input
                                    className="form-control"
                                    type="number"
                                    min="1"
                                    step="1"
                                    value={form.demand}
                                    onChange={(e) => updateField("demand", e.target.value)}
                                    required
                                />
                            </label>
                        </div>

                        <div className="request-form-footer">
              <span className="form-hint">
                Временное окно указывается в пределах одного дня.
              </span>

                            <button
                                type="submit"
                                className="primary-button"
                                disabled={creating}
                            >
                                <Plus size={17} />
                                {creating ? "Создание..." : "Создать заявку"}
                            </button>
                        </div>
                    </form>
                </section>
            )}

            <section className="panel">
                <div className="panel-heading requests-list-heading">
                    <div>
                        <h2>Список заявок</h2>
                        <p>
                            {filteredRequests.length} из {requests.length} заявок
                        </p>
                    </div>

                    <div className="request-filters">
                        <label className="search-box">
                            <Search size={17} />
                            <input
                                value={search}
                                onChange={(e) => setSearch(e.target.value)}
                                placeholder="Поиск по заявкам..."
                                aria-label="Поиск по заявкам"
                            />
                        </label>

                        <select
                            className="filter-select"
                            value={statusFilter}
                            onChange={(e) => setStatusFilter(e.target.value)}
                            aria-label="Фильтр по статусу"
                        >
                            <option value="ALL">Все статусы</option>
                            <option value="NEW">Новые</option>
                            <option value="PLANNED">Запланированные</option>
                            <option value="COMPLETED">Завершённые</option>
                        </select>
                    </div>
                </div>

                {loading ? (
                    <div className="empty-state">
                        <RefreshCw size={21} />
                        Загрузка заявок...
                    </div>
                ) : filteredRequests.length === 0 ? (
                    <div className="empty-state">
                        {requests.length === 0 ? (
                            <>
                                <ClipboardList size={28} />
                                <span>Заявок пока нет. Создай первую заявку.</span>
                            </>
                        ) : (
                            <>
                                <Search size={25} />
                                <span>По заданным условиям ничего не найдено.</span>
                            </>
                        )}
                    </div>
                ) : (
                    <div className="table-wrapper">
                        <table className="data-table requests-table">
                            <thead>
                            <tr>
                                <th>ID</th>
                                <th>Заявка</th>
                                <th>Адрес</th>
                                <th>Временное окно</th>
                                <th>Обслуживание</th>
                                <th>Бригада</th>
                                <th>Статус</th>
                            </tr>
                            </thead>

                            <tbody>
                            {filteredRequests.map((request) => (
                                <tr key={request.id}>
                                    <td className="request-id-cell">#{request.id}</td>

                                    <td>
                                        <div className="request-title">{request.title}</div>
                                        <div className="request-id">
                                            Потребность: {request.demand}
                                        </div>
                                    </td>

                                    <td>
                                        <div className="address-cell">
                                            <MapPin size={14} />
                                            <span>{request.address}</span>
                                        </div>
                                    </td>

                                    <td>
                                        <div className="time-cell">
                                            <Clock3 size={14} />
                                            {formatTime(request.windowStartMinute)}
                                            {" – "}
                                            {formatTime(request.windowEndMinute)}
                                        </div>
                                    </td>

                                    <td>{request.serviceDurationMinutes} мин.</td>

                                    <td>
                                        {request.assignedTeamId == null
                                            ? "Не назначена"
                                            : `Бригада #${request.assignedTeamId}`}
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
                )}
            </section>
        </div>
    );
}