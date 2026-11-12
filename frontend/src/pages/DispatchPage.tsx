import { Fragment, useEffect, useMemo, useState } from "react";
import {
    CheckCircle2,
    Clock3,
    MapPin,
    RefreshCw,
    Route as RouteIcon,
    Users,
    Zap,
    ClipboardList,
} from "lucide-react";

import {
    CircleMarker,
    MapContainer,
    Polyline,
    Popup,
    TileLayer,
    useMap,
} from "react-leaflet";

import "leaflet/dist/leaflet.css";

import type {
    DispatchPlanResponse,
    DispatchRoutePlan,
} from "../types";
import type { Team, TransportRequest } from "../types";

import { optimizeDispatch } from "../api/dispatch";
import { getRequests } from "../api/requests";
import { getTeams } from "../api/teams";

const ROUTE_COLORS = [
    "#4f46e5",
    "#059669",
    "#d97706",
    "#db2777",
    "#0284c7",
    "#7c3aed",
];

function formatTime(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;

    return `${String(hours).padStart(2, "0")}:${String(mins).padStart(2, "0")}`;
}

function formatDuration(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;

    if (hours === 0) {
        return `${mins} мин.`;
    }

    if (mins === 0) {
        return `${hours} ч`;
    }

    return `${hours} ч ${mins} мин.`;
}

function getErrorMessage(error: unknown): string {
    if (typeof error === "object" && error !== null && "response" in error) {
        const response = (
            error as {
                response?: {
                    data?: {
                        message?: string;
                        detail?: string;
                        error?: string;
                    } | string;
                };
            }
        ).response;

        const data = response?.data;

        if (typeof data === "string") {
            return data;
        }

        if (data?.message) return data.message;
        if (data?.detail) return data.detail;
        if (data?.error) return data.error;
    }

    if (error instanceof Error) {
        return error.message;
    }

    return "Произошла непредвиденная ошибка.";
}

function MapAutoFit({
                        points,
                    }: {
    points: Array<[number, number]>;
}) {
    const map = useMap();

    useEffect(() => {
        if (points.length === 0) return;

        if (points.length === 1) {
            map.setView(points[0], 13);
        } else {
            map.fitBounds(points, {
                padding: [35, 35],
                maxZoom: 14,
            });
        }

        map.invalidateSize();
    }, [map, points]);

    return null;
}

function RouteDetails({
                          route,
                          color,
                      }: {
    route: DispatchRoutePlan;
    color: string;
}) {
    return (
        <article className="dispatch-route-card">
            <div className="dispatch-route-card-header">
                <div className="dispatch-route-title">
          <span
              className="dispatch-route-color"
              style={{ backgroundColor: color }}
          />

                    <div>
                        <h3>{route.teamName}</h3>
                        <p>Бригада #{route.teamId}</p>
                    </div>
                </div>

                <span className="dispatch-route-stop-count">
          {route.stops.length}{" "}
                    {route.stops.length === 1 ? "заявка" : "заявок"}
        </span>
            </div>

            <div className="dispatch-route-metrics">
                <div>
                    <span>Начало смены</span>
                    <strong>{formatTime(route.shiftStartMinute)}</strong>
                </div>

                <div>
                    <span>Завершение маршрута</span>
                    <strong>{formatTime(route.finishMinute)}</strong>
                </div>

                <div>
                    <span>Время в пути</span>
                    <strong>{formatDuration(route.totalTravelMinutes)}</strong>
                </div>
            </div>

            {route.stops.length === 0 ? (
                <div className="dispatch-empty-route">
                    Для этой бригады заявок в плане нет.
                </div>
            ) : (
                <div className="dispatch-stops">
                    {route.stops.map((stop, index) => (
                        <div
                            className="dispatch-stop"
                            key={`${route.teamId}-${stop.requestId}`}
                        >
                            <div
                                className="dispatch-stop-number"
                                style={{
                                    borderColor: color,
                                    color,
                                }}
                            >
                                {index + 1}
                            </div>

                            <div className="dispatch-stop-content">
                                <div className="dispatch-stop-heading">
                                    <strong>{stop.title}</strong>
                                    <span className="dispatch-arrival">
                    {formatTime(stop.arrivalMinute)}
                  </span>
                                </div>

                                <div className="dispatch-stop-address">
                                    <MapPin size={13} />
                                    <span>{stop.address}</span>
                                </div>

                                <div className="dispatch-stop-meta">
                  <span>
                    <Clock3 size={13} />
                    В пути: {formatDuration(stop.travelFromPreviousMinutes)}
                  </span>

                                    <span>
                    Обслуживание: {stop.serviceDurationMinutes} мин.
                  </span>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </article>
    );
}

export default function DispatchPage() {
    const [plan, setPlan] = useState<DispatchPlanResponse | null>(null);
    const [teams, setTeams] = useState<Team[]>([]);
    const [requests, setRequests] = useState<TransportRequest[]>([]);

    const [loadingOverview, setLoadingOverview] = useState(true);
    const [optimizing, setOptimizing] = useState(false);

    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;

        async function loadOverview() {
            setLoadingOverview(true);

            try {
                const [teamsData, requestsData] = await Promise.all([
                    getTeams(),
                    getRequests(),
                ]);

                if (cancelled) return;

                setTeams(teamsData);
                setRequests(requestsData);
            } catch (err) {
                if (!cancelled) {
                    setError(getErrorMessage(err));
                }
            } finally {
                if (!cancelled) {
                    setLoadingOverview(false);
                }
            }
        }

        void loadOverview();

        return () => {
            cancelled = true;
        };
    }, []);

    const activeTeams = teams.filter((team) => team.active);

    const eligibleRequests = requests.filter(
        (request) =>
            request.status === "NEW" || request.status === "PLANNED",
    );

    const mapPoints = useMemo<Array<[number, number]>>(() => {
        const points: Array<[number, number]> = [];

        for (const route of plan?.routes ?? []) {
            if (route.geometry && route.geometry.length > 0) {
                for (const point of route.geometry) {
                    points.push([point.latitude, point.longitude]);
                }
            } else {
                points.push([route.baseLatitude, route.baseLongitude]);

                for (const stop of route.stops) {
                    points.push([stop.latitude, stop.longitude]);
                }
            }
        }

        return points;
    }, [plan]);

    async function handleOptimize() {
        setOptimizing(true);
        setError(null);
        setSuccess(null);

        try {
            const result = await optimizeDispatch();

            setPlan(result);
            setSuccess(
                `Оптимизация завершена. Назначено заявок: ${result.assignedRequests}.`,
            );

            // Backend сохраняет назначения. Повторно загружаем данные,
            // чтобы отобразить актуальные статусы заявок.
            try {
                const [teamsData, requestsData] = await Promise.all([
                    getTeams(),
                    getRequests(),
                ]);

                setTeams(teamsData);
                setRequests(requestsData);
            } catch {
                setError(
                    "План построен, но обновить списки из API не удалось. Нажми «Обновить данные».",
                );
            }
        } catch (err) {
            setError(getErrorMessage(err));
        } finally {
            setOptimizing(false);
        }
    }

    async function handleRefresh() {
        setError(null);
        setSuccess(null);
        setLoadingOverview(true);

        try {
            const [teamsData, requestsData] = await Promise.all([
                getTeams(),
                getRequests(),
            ]);

            setTeams(teamsData);
            setRequests(requestsData);
            setSuccess("Данные успешно обновлены.");
        } catch (err) {
            setError(getErrorMessage(err));
        } finally {
            setLoadingOverview(false);
        }
    }

    return (
        <div className="dispatch-page">
            <div className="page-heading">
                <div>
                    <div className="eyebrow">ПЛАНИРОВАНИЕ МАРШРУТОВ</div>
                    <h1>Диспетчеризация</h1>
                    <p>
                        Автоматическое распределение заявок с учётом вместимости,
                        временных окон и рабочих смен.
                    </p>
                </div>

                <div className="dispatch-actions">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={() => void handleRefresh()}
                        disabled={loadingOverview || optimizing}
                    >
                        <RefreshCw size={16} />
                        Обновить данные
                    </button>

                    <button
                        type="button"
                        className="primary-button"
                        onClick={() => void handleOptimize()}
                        disabled={optimizing || loadingOverview}
                    >
                        {optimizing ? (
                            <RefreshCw size={17} className="dispatch-spin" />
                        ) : (
                            <Zap size={17} />
                        )}

                        {optimizing ? "Выполняется оптимизация..." : "Запустить оптимизацию"}
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

            <div className="dispatch-summary-grid">
                <div className="dispatch-summary-card">
                    <div className="dispatch-summary-icon">
                        <ClipboardList size={21} />
                    </div>

                    <div>
                        <div className="dispatch-summary-label">
                            Заявки для распределения
                        </div>

                        <div className="dispatch-summary-value">
                            {loadingOverview ? "…" : eligibleRequests.length}
                        </div>

                        <div className="dispatch-summary-hint">
                            Статусы NEW и PLANNED
                        </div>
                    </div>
                </div>

                <div className="dispatch-summary-card">
                    <div className="dispatch-summary-icon dispatch-icon-green">
                        <Users size={21} />
                    </div>

                    <div>
                        <div className="dispatch-summary-label">Активные бригады</div>

                        <div className="dispatch-summary-value">
                            {loadingOverview ? "…" : activeTeams.length}
                        </div>

                        <div className="dispatch-summary-hint">
                            Доступны для планирования
                        </div>
                    </div>
                </div>

                <div className="dispatch-summary-card">
                    <div className="dispatch-summary-icon dispatch-icon-blue">
                        <CheckCircle2 size={21} />
                    </div>

                    <div>
                        <div className="dispatch-summary-label">
                            Назначено по плану
                        </div>

                        <div className="dispatch-summary-value">
                            {plan ? plan.assignedRequests : "—"}
                        </div>

                        <div className="dispatch-summary-hint">
                            {plan
                                ? "Результат последней оптимизации"
                                : "Запусти оптимизацию"}
                        </div>
                    </div>
                </div>

                <div className="dispatch-summary-card">
                    <div className="dispatch-summary-icon dispatch-icon-orange">
                        <Clock3 size={21} />
                    </div>

                    <div>
                        <div className="dispatch-summary-label">Суммарное время в пути</div>

                        <div className="dispatch-summary-value dispatch-summary-value-small">
                            {plan ? formatDuration(plan.totalTravelMinutes) : "—"}
                        </div>

                        <div className="dispatch-summary-hint">
                            По рассчитанному плану
                        </div>
                    </div>
                </div>
            </div>

            {!plan && (
                <section className="panel dispatch-intro-panel">
                    <div className="dispatch-intro-icon">
                        <RouteIcon size={27} />
                    </div>

                    <div>
                        <h2>Готово к планированию</h2>
                        <p>
                            Для построения плана будут использованы активные бригады
                            и заявки со статусами NEW и PLANNED. Нажми «Запустить
                            оптимизацию», чтобы получить распределение и порядок
                            посещения точек.
                        </p>

                        {activeTeams.length === 0 && !loadingOverview && (
                            <p className="dispatch-inline-warning">
                                Активных бригад пока нет. Добавь или активируй бригаду
                                перед запуском оптимизации.
                            </p>
                        )}

                        {eligibleRequests.length === 0 && !loadingOverview && (
                            <p className="dispatch-inline-hint">
                                Сейчас нет заявок, ожидающих распределения.
                            </p>
                        )}
                    </div>
                </section>
            )}

            {plan && (
                <>
                    <section className="panel dispatch-result-panel">
                        <div className="dispatch-result-heading">
                            <div>
                                <div className="dispatch-result-icon">
                                    <CheckCircle2 size={20} />
                                </div>
                            </div>

                            <div className="dispatch-result-text">
                                <h2>План оптимизации рассчитан</h2>
                                <p>
                                    Назначено заявок: <strong>{plan.assignedRequests}</strong>.
                                    Количество маршрутов в ответе:{" "}
                                    <strong>{plan.routes.length}</strong>.
                                </p>
                            </div>
                        </div>
                    </section>

                    {plan.routes.length > 0 && (
                        <section className="panel dispatch-map-panel">
                            <div className="panel-heading">
                                <div>
                                    <h2>Карта маршрутов</h2>
                                    <p>
                                        Точки отправления бригад и заявки в порядке посещения.
                                    </p>
                                </div>
                            </div>

                            <div className="dispatch-map-canvas">
                                <MapContainer
                                    center={[52.52, 13.405]}
                                    zoom={12}
                                    scrollWheelZoom
                                    className="dispatch-leaflet-map"
                                >
                                    <TileLayer
                                        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                                        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                                    />

                                    <MapAutoFit points={mapPoints} />

                                    {plan.routes.map((route, routeIndex) => {
                                        const color =
                                            ROUTE_COLORS[routeIndex % ROUTE_COLORS.length];

                                        const fallbackPositions: Array<[number, number]> = [
                                            [route.baseLatitude, route.baseLongitude],
                                            ...route.stops.map(
                                                (stop) =>
                                                    [stop.latitude, stop.longitude] as [number, number],
                                            ),
                                            ...(route.stops.length > 0
                                                ? [[route.baseLatitude, route.baseLongitude] as [number, number]]
                                                : []),
                                        ];

                                        const positions: Array<[number, number]> =
                                            route.geometry && route.geometry.length > 1
                                                ? route.geometry.map(
                                                    (point) =>
                                                        [point.latitude, point.longitude] as [number, number],
                                                )
                                                : fallbackPositions;

                                        return (
                                            <Fragment key={`route-map-${route.teamId}`}>
                        {route.stops.length > 0 && (
                            <Polyline
                                positions={positions}
                                pathOptions={{
                                    color,
                                    weight: 4,
                                    opacity: 0.8,
                                }}
                            />
                        )}

                                                <CircleMarker
                                                    center={[
                                                        route.baseLatitude,
                                                        route.baseLongitude,
                                                    ]}
                                                    radius={9}
                                                    pathOptions={{
                                                        color: "#ffffff",
                                                        weight: 3,
                                                        fillColor: color,
                                                        fillOpacity: 1,
                                                    }}
                                                >
                          <Popup>
                            <strong>{route.teamName}</strong>
                            <br />
                            Точка отправления
                          </Popup>
                        </CircleMarker>

                                                {route.stops.map((stop, stopIndex) => (
                                                    <CircleMarker
                                                        key={`${route.teamId}-${stop.requestId}`}
                                                        center={[stop.latitude, stop.longitude]}
                                                        radius={7}
                                                        pathOptions={{
                                                            color,
                                                            weight: 2,
                                                            fillColor: "#ffffff",
                                                            fillOpacity: 1,
                                                        }}
                                                    >
                                                        <Popup>
                                                            <strong>
                                                                {stopIndex + 1}. {stop.title}
                                                            </strong>
                                                            <br />
                                                            {stop.address}
                                                            <br />
                                                            Прибытие: {formatTime(stop.arrivalMinute)}
                                                            <br />
                                                            Бригада: {route.teamName}
                                                        </Popup>
                                                    </CircleMarker>
                                                ))}
                      </Fragment>
                                        );
                                    })}
                                </MapContainer>
                            </div>

                            <div className="dispatch-map-legend">
                                {plan.routes.map((route, index) => {
                                    const color =
                                        ROUTE_COLORS[index % ROUTE_COLORS.length];

                                    return (
                                        <div className="dispatch-legend-item" key={route.teamId}>
                      <span
                          className="dispatch-legend-dot"
                          style={{ backgroundColor: color }}
                      />
                                            {route.teamName}
                                        </div>
                                    );
                                })}
                            </div>

                            <p className="dispatch-map-note">
                                Линии пока соединяют точки в последовательности маршрута
                                напрямую. Это схема порядка посещения, а не точная геометрия
                                автомобильной дороги. Для отображения дорожных линий понадобится
                                получить геометрию маршрута из routing API.
                            </p>
                        </section>
                    )}

                    <section className="dispatch-routes-section">
                        <div className="dispatch-section-title">
                            <div>
                                <h2>Маршруты бригад</h2>
                                <p>Порядок выполнения назначенных заявок и расчётное время.</p>
                            </div>
                        </div>

                        {plan.routes.length === 0 ? (
                            <div className="panel empty-state">
                                <RouteIcon size={25} />
                                Оптимизатор не вернул маршрутов.
                            </div>
                        ) : (
                            <div className="dispatch-routes-grid">
                                {plan.routes.map((route, index) => (
                                    <RouteDetails
                                        key={route.teamId}
                                        route={route}
                                        color={ROUTE_COLORS[index % ROUTE_COLORS.length]}
                                    />
                                ))}
                            </div>
                        )}
                    </section>
                </>
            )}
        </div>
    );
}
