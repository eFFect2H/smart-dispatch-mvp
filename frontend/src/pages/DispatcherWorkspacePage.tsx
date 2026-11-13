import { Fragment, useEffect, useMemo, useState } from "react";
import type { ChangeEvent, CSSProperties } from "react";
import {
    Activity, AlertTriangle, CalendarDays, CheckCircle2, Clock3, Filter,
    Layers3, MapPinned, Play, RefreshCw, Route, Send, Upload, XCircle,
} from "lucide-react";
import { CircleMarker, MapContainer, Polyline, Popup, TileLayer, useMap } from "react-leaflet";
import "leaflet/dist/leaflet.css";

import {
    appendEvent, generatePlan, getEventHistory, getPlan, uploadDay,
    uploadReference, uploadTravelMatrix,
} from "../api/v1Dispatch";
import PlanComparisonPanel from "../components/dispatch/PlanComparisonPanel";
import SelectedOrderDetails from "../components/dispatch/SelectedOrderDetails";
import { comparePlanVersions } from "../utils/planInsights";
import type { PlanComparison } from "../utils/planInsights";
import type {
    AccessNode, DayInput, EventHistory, EventRequest, EventType, Order,
    PlanAlert, PlanResponse, PlanRoute, PlanStop, ReferenceData, ServiceCenter,
    StaffMember, TravelMatrix, UnassignedOrder,
} from "../types/v1";

const ROUTE_COLORS = ["#3563e9", "#0d9488", "#d97706", "#be4b8a", "#6477d7", "#7c5ce7"];
const TIME_START = 8 * 60;
const TIME_END = 22 * 60;
const EVENT_LABELS: Record<EventType, string> = {
    NEW_ORDER: "Новый наряд",
    ORDER_CANCELLED: "Отмена наряда",
    VISIT_DELAYED: "Задержка визита",
    STAFF_UNAVAILABLE: "Сотрудник недоступен",
    ORDER_TRANSFERRED: "Передача другому сотруднику",
};

function localToday(): string {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}
function formatTime(value?: string | null): string {
    if (!value) return "—";
    const match = value.match(/T(\d{2}):(\d{2})/);
    return match ? match[1] + ":" + match[2] : value.slice(0, 5);
}
function minuteOfDay(value?: string | null): number | null {
    if (!value) return null;
    const parts = formatTime(value).split(":").map(Number);
    return Number.isFinite(parts[0]) && Number.isFinite(parts[1]) ? parts[0] * 60 + parts[1] : null;
}
function formatDuration(seconds?: number | null): string {
    if (seconds == null) return "—";
    const total = Math.round(seconds / 60);
    const hours = Math.floor(total / 60);
    const minutes = total % 60;
    return hours ? hours + " ч " + minutes + " мин" : minutes + " мин";
}
function formatDateTime(value?: string | null): string {
    return value ? value.slice(8, 10) + "." + value.slice(5, 7) + " " + formatTime(value) : "—";
}
function offsetDate(value: string): string { return value.slice(0, 10); }

function readFile(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result ?? ""));
        reader.onerror = () => reject(new Error("Не удалось прочитать файл " + file.name));
        reader.readAsText(file, "UTF-8");
    });
}
async function readJson(file: File): Promise<unknown> {
    try {
        return JSON.parse(await readFile(file)) as unknown;
    } catch (error) {
        const message = error instanceof Error ? error.message : "parse error";
        throw new Error(file.name + ": некорректный JSON (" + message + ")", { cause: error });
    }
}
function unwrapArray<T>(value: unknown, key: string, fileName: string): T[] {
    const candidate = Array.isArray(value) ? value :
        typeof value === "object" && value !== null && key in value ?
            (value as Record<string, unknown>)[key] : null;
    if (!Array.isArray(candidate)) throw new Error(fileName + ": ожидался массив или объект с полем " + key);
    return candidate as T[];
}
function parseCsv(text: string): string[][] {
    const rows: string[][] = [];
    let row: string[] = [];
    let cell = "";
    let quoted = false;
    for (let i = 0; i < text.length; i += 1) {
        const char = text[i];
        if (quoted) {
            if (char === '"' && text[i + 1] === '"') { cell += '"'; i += 1; }
            else if (char === '"') quoted = false;
            else cell += char;
        } else if (char === '"' && cell.length === 0) quoted = true;
        else if (char === ",") { row.push(cell); cell = ""; }
        else if (char === "\n") {
            row.push(cell.replace(/\r$/, ""));
            if (row.some((value) => value.length > 0)) rows.push(row);
            row = []; cell = "";
        } else cell += char;
    }
    if (quoted) throw new Error("work_types.csv: незакрытое поле в кавычках");
    if (cell.length || row.length) {
        row.push(cell.replace(/\r$/, ""));
        if (row.some((value) => value.length > 0)) rows.push(row);
    }
    return rows;
}
function parseWorkTypes(text: string): ReferenceData["work_types"] {
    const rows = parseCsv(text);
    if (rows.length < 2) throw new Error("work_types.csv: нет строк типов работ");
    const headers = rows[0].map((value) => value.trim().replace(/^\uFEFF/, ""));
    const required = ["code", "name", "profiles", "duration_min", "max_start_delay_min",
        "deadline_kind", "deadline_value", "repeat_deadline_value", "pgp_relevant"];
    const missing = required.filter((name) => !headers.includes(name));
    if (missing.length) throw new Error("work_types.csv: отсутствуют столбцы " + missing.join(", "));
    return rows.slice(1).map((row, index) => {
        const get = (name: string) => (row[headers.indexOf(name)] ?? "").trim();
        const integer = (name: string) => {
            const number = Number(get(name));
            if (!Number.isInteger(number)) throw new Error("work_types.csv, строка " + (index + 2) + ": " + name + " должен быть целым числом");
            return number;
        };
        const profiles = get("profiles").split("|").map((value) => value.trim()).filter(Boolean);
        if (!profiles.length) throw new Error("work_types.csv, строка " + (index + 2) + ": profiles пуст");
        const pgp = get("pgp_relevant").toLowerCase();
        if (pgp !== "true" && pgp !== "false") throw new Error("work_types.csv, строка " + (index + 2) + ": pgp_relevant должен быть true/false");
        return {
            code: get("code"), name: get("name"), profiles: profiles as ("INSTALL" | "LTP3")[],
            duration_min: integer("duration_min"),
            max_start_delay_min: get("max_start_delay_min") ? integer("max_start_delay_min") : null,
            deadline_kind: get("deadline_kind") as "SLOT" | "HOURS" | "WORKING_DAYS",
            deadline_value: integer("deadline_value"), repeat_deadline_value: integer("repeat_deadline_value"),
            pgp_relevant: pgp === "true",
        };
    });
}
function getErrorMessage(error: unknown): string {
    if (typeof error === "object" && error !== null && "response" in error) {
        const response = (error as { response?: { data?: unknown; status?: number } }).response;
        const data = response?.data;
        if (typeof data === "string") return data;
        if (typeof data === "object" && data !== null) {
            const body = data as { error?: { code?: string; message?: string }; message?: string };
            if (body.error?.message) return (body.error.code ?? "API_ERROR") + ": " + body.error.message;
            if (body.message) return body.message;
        }
        if (response?.status) return "Ошибка API: HTTP " + response.status;
    }
    return error instanceof Error ? error.message : "Произошла непредвиденная ошибка.";
}
function selectDayOrders(allOrders: Order[], date: string, modelNow: string): Order[] {
    const nowMs = Date.parse(modelNow);
    if (!Number.isFinite(nowMs) || !/[+-]\d{2}:\d{2}$/.test(modelNow)) {
        throw new Error("Укажи model_now в формате ISO-8601 со смещением, например 2026-10-26T08:00:00+05:00");
    }
    return allOrders.filter((order) => offsetDate(order.slot?.start ?? "") === date &&
        Date.parse(order.registered_at) <= nowMs);
}
function locationIndex(reference: ReferenceData | null): Map<string, { lat: number; lon: number; title: string }> {
    const result = new Map<string, { lat: number; lon: number; title: string }>();
    if (!reference) return result;
    reference.service_centers.forEach((center: ServiceCenter) =>
        result.set(center.service_center_id, { lat: center.lat, lon: center.lon, title: center.name }));
    reference.access_nodes.forEach((node: AccessNode) => node.buildings.forEach((building) =>
        result.set(building.building_id, { lat: building.lat, lon: building.lon, title: building.address })));
    return result;
}

function MapAutoFit({ points }: { points: [number, number][] }) {
    const map = useMap();
    useEffect(() => {
        if (points.length === 1) map.setView(points[0], 13);
        else if (points.length > 1) map.fitBounds(points, { padding: [30, 30], maxZoom: 14 });
    }, [map, points]);
    return null;
}
function StaffLabel({ staffId, staff }: { staffId: string; staff?: StaffMember }) {
    return <span>{staff?.display_name || staffId}</span>;
}
function GanttRow(props: {
    route: PlanRoute; staff?: StaffMember; selectedOrderId: string | null;
    onSelectOrder: (id: string | null) => void; color: string;
}) {
    const staffStart = minuteOfDay(props.staff?.shift.start) ?? TIME_START;
    const staffEnd = minuteOfDay(props.staff?.shift.end) ?? TIME_END;
    const shiftStyle = {
        left: (((staffStart - TIME_START) / (TIME_END - TIME_START)) * 100) + "%",
        width: (((staffEnd - staffStart) / (TIME_END - TIME_START)) * 100) + "%",
    } as CSSProperties;
    return (
        <div className="v1-gantt-row">
            <div className="v1-gantt-person">
                <span className="v1-gantt-avatar">{(props.staff?.display_name || props.route.staff_id).slice(0, 1)}</span>
                <span><strong><StaffLabel staffId={props.route.staff_id} staff={props.staff} /></strong><small>{props.route.staff_id}</small></span>
            </div>
            <div className="v1-gantt-track">
                <div className="v1-gantt-shift" style={shiftStyle} />
                {props.route.stops.map((stop: PlanStop) => {
                    const start = minuteOfDay(stop.start) ?? minuteOfDay(stop.arrival);
                    const end = minuteOfDay(stop.end) ?? start;
                    if (start === null || end === null) return null;
                    const clippedStart = Math.max(TIME_START, start);
                    const clippedEnd = Math.min(TIME_END, Math.max(end, start + 3));
                    const style = {
                        left: (((clippedStart - TIME_START) / (TIME_END - TIME_START)) * 100) + "%",
                        width: Math.max(0.5, ((clippedEnd - clippedStart) / (TIME_END - TIME_START)) * 100) + "%",
                        backgroundColor: stop.type === "VISIT" ? props.color : stop.type === "BREAK" ? "#94a3b8" : "#cbd5e1",
                    } as CSSProperties;
                    const selected = Boolean(stop.order_id && props.selectedOrderId === stop.order_id);
                    return <button key={props.route.staff_id + "-" + stop.seq} type="button"
                        className={"v1-gantt-stop" + (selected ? " selected" : "")} style={style}
                        title={stop.type + (stop.order_id ? " · " + stop.order_id : "") + " · " + formatTime(stop.start) + "–" + formatTime(stop.end)}
                        onClick={() => props.onSelectOrder(stop.order_id ?? null)} />;
                })}
            </div>
        </div>
    );
}

export default function DispatcherWorkspacePage() {
    const today = localToday();
    const [date, setDate] = useState(today);
    const [modelNow, setModelNow] = useState(today + "T08:00:00+05:00");
    const [serviceCentersFile, setServiceCentersFile] = useState<File | null>(null);
    const [staffFile, setStaffFile] = useState<File | null>(null);
    const [accessNodesFile, setAccessNodesFile] = useState<File | null>(null);
    const [workTypesFile, setWorkTypesFile] = useState<File | null>(null);
    const [matrixFile, setMatrixFile] = useState<File | null>(null);
    const [ordersFile, setOrdersFile] = useState<File | null>(null);
    const [reference, setReference] = useState<ReferenceData | null>(null);
    const [allOrders, setAllOrders] = useState<Order[]>([]);
    const [plan, setPlan] = useState<PlanResponse | null>(null);
    const [planComparison, setPlanComparison] = useState<PlanComparison | null>(null);
    const [eventHistory, setEventHistory] = useState<EventHistory | null>(null);
    const [absentStaffInput, setAbsentStaffInput] = useState("");
    const [eventType, setEventType] = useState<EventType>("VISIT_DELAYED");
    const [eventOrderId, setEventOrderId] = useState("");
    const [eventStaffId, setEventStaffId] = useState("");
    const [targetStaffId, setTargetStaffId] = useState("");
    const [delayMin, setDelayMin] = useState("15");
    const [eventNow, setEventNow] = useState(today + "T09:00:00+05:00");
    const [newOrderJson, setNewOrderJson] = useState("");
    const [staffFilter, setStaffFilter] = useState("ALL");
    const [activePanel, setActivePanel] = useState<"unassigned" | "risk" | "pgp" | "events">("unassigned");
    const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
    const [uploading, setUploading] = useState(false);
    const [building, setBuilding] = useState(false);
    const [sending, setSending] = useState(false);
    const [refreshing, setRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);

    const locationById = useMemo(() => locationIndex(reference), [reference]);
    const filteredRoutes = useMemo(() => (plan?.routes ?? []).filter((route) =>
        staffFilter === "ALL" || route.staff_id === staffFilter), [plan, staffFilter]);
    const mapRoutes = useMemo(() => filteredRoutes.map((route) => ({
        route,
        points: route.stops.map((stop) => {
            const location = locationById.get(stop.location_id);
            return location ? { ...location, stop } : null;
        }).filter((item): item is { lat: number; lon: number; title: string; stop: PlanStop } => item !== null),
    })), [filteredRoutes, locationById]);
    const mapPoints = useMemo<[number, number][]>(() =>
        mapRoutes.flatMap(({ points }) => points.map((point) => [point.lat, point.lon] as [number, number])), [mapRoutes]);
    const activeAlerts = (plan?.alerts ?? []).filter((alert: PlanAlert) =>
        alert.status === "ACTIVE" && alert.type === "LATE_RISK");
    const staffOptions = reference?.staff ?? (plan?.routes ?? []).map((route) => ({
        staff_id: route.staff_id,
        display_name: route.staff_id,
    }));

    function fileHandler(setter: (file: File | null) => void) {
        return (event: ChangeEvent<HTMLInputElement>) => setter(event.target.files?.[0] ?? null);
    }

    async function handleUploadReference() {
        if (!serviceCentersFile || !staffFile || !accessNodesFile || !workTypesFile || !matrixFile) {
            setError("Выбери service_centers.json, staff.json, access_nodes.json, work_types.csv и travel_matrix.json.");
            return;
        }
        setUploading(true); setError(null); setNotice(null);
        try {
            const values = await Promise.all([
                readJson(serviceCentersFile), readJson(staffFile), readJson(accessNodesFile),
                readFile(workTypesFile), readJson(matrixFile),
            ]);
            const nextReference: ReferenceData = {
                service_centers: unwrapArray<ServiceCenter>(values[0], "service_centers", serviceCentersFile.name),
                staff: unwrapArray<StaffMember>(values[1], "staff", staffFile.name),
                access_nodes: unwrapArray<AccessNode>(values[2], "access_nodes", accessNodesFile.name),
                work_types: parseWorkTypes(values[3] as string),
            };
            const matrixRaw = values[4];
            const nextMatrix = typeof matrixRaw === "object" && matrixRaw !== null && "travel_matrix" in matrixRaw
                ? (matrixRaw as { travel_matrix: TravelMatrix }).travel_matrix : matrixRaw as TravelMatrix;
            await uploadReference(nextReference);
            // The backend resets loaded day snapshots as soon as reference data is replaced.
            setReference(nextReference);
            setPlan(null); setEventHistory(null); setPlanComparison(null); setSelectedOrderId(null); setAllOrders([]);
            await uploadTravelMatrix(nextMatrix);
            setNotice("Справочники и матрица загружены: " + nextReference.staff.length + " сотрудников, " +
                nextReference.service_centers.length + " СЦ, " + nextReference.access_nodes.length +
                " узлов. Состояние дней сброшено — загрузи день заново.");
        } catch (cause) { setError(getErrorMessage(cause)); }
        finally { setUploading(false); }
    }

    async function handleBuildPlan() {
        if (!ordersFile) { setError("Выбери orders_train.json или файл нарядов одного дня."); return; }
        if (!reference) { setError("Сначала загрузи справочники и матрицу."); return; }
        setBuilding(true); setError(null); setNotice(null);
        try {
            const raw = await readJson(ordersFile);
            const orders = unwrapArray<Order>(raw, "orders", ordersFile.name);
            const selectedOrders = selectDayOrders(orders, date, modelNow);
            const dayInput: DayInput = {
                date, now: modelNow, orders: selectedOrders,
                absent_staff_ids: absentStaffInput.split(",").map((value) => value.trim()).filter(Boolean),
            };
            const previousPlan = plan?.date === date ? plan : null;
            await uploadDay(date, dayInput);
            await generatePlan(date);
            const [latestPlan, history] = await Promise.all([getPlan(date), getEventHistory(date)]);
            setAllOrders(orders); setPlan(latestPlan); setEventHistory(history); setEventNow(modelNow);
            setPlanComparison(comparePlanVersions(previousPlan, latestPlan, "Перестроение исходного дня"));
            setSelectedOrderId(null);
            setNotice("План v" + latestPlan.plan_version + " построен. В день загружено " + selectedOrders.length + " нарядов.");
            setActivePanel("unassigned");
        } catch (cause) { setError(getErrorMessage(cause)); }
        finally { setBuilding(false); }
    }

    async function handleRefresh() {
        setRefreshing(true); setError(null);
        try {
            const previousPlan = plan?.date === date ? plan : null;
            const [latestPlan, history] = await Promise.all([getPlan(date), getEventHistory(date)]);
            const comparison = comparePlanVersions(previousPlan, latestPlan, "Обновление из API");
            if (comparison) setPlanComparison(comparison);
            setPlan(latestPlan); setEventHistory(history);
            setNotice("Получен план v" + latestPlan.plan_version + " на " + date + ".");
        } catch (cause) { setError(getErrorMessage(cause)); }
        finally { setRefreshing(false); }
    }

    async function handleSendEvent() {
        if (!plan) { setError("Сначала построй план дня."); return; }
        if (!eventNow.startsWith(date + "T") || !/[+-]\d{2}:\d{2}$/.test(eventNow)) {
            setError("Время события должно относиться к " + date + " и содержать UTC offset."); return;
        }
        const needsOrder = ["NEW_ORDER", "ORDER_CANCELLED", "VISIT_DELAYED", "ORDER_TRANSFERRED"].includes(eventType);
        if (needsOrder && !eventOrderId.trim()) { setError("Для выбранного события требуется order_id."); return; }
        if (eventType === "STAFF_UNAVAILABLE" && !eventStaffId.trim()) { setError("Для STAFF_UNAVAILABLE требуется staff_id."); return; }
        if (eventType === "ORDER_TRANSFERRED" && !targetStaffId.trim()) { setError("Для ORDER_TRANSFERRED требуется target_staff_id."); return; }
        let payload: Record<string, unknown> = {};
        if (eventType === "VISIT_DELAYED") {
            const delay = Number(delayMin);
            if (!Number.isInteger(delay) || delay <= 0) { setError("delay_min должен быть положительным целым числом."); return; }
            payload = { delay_min: delay };
        } else if (eventType === "NEW_ORDER") {
            try {
                const parsed = JSON.parse(newOrderJson) as Record<string, unknown>;
                const order = "order" in parsed ? parsed.order : parsed;
                if (!order || typeof order !== "object") throw new Error("Не найден объект order");
                payload = { order };
            } catch (cause) {
                setError("Проверь JSON нового наряда: " + (cause instanceof Error ? cause.message : "ошибка формата")); return;
            }
        }
        const event: EventRequest = {
            event_id: "EVT-" + crypto.randomUUID(),
            event_type: eventType,
            occurred_at: eventNow,
            ...(needsOrder ? { order_id: eventOrderId.trim() } : {}),
            ...(eventType === "STAFF_UNAVAILABLE" ? { staff_id: eventStaffId.trim() } : {}),
            ...(eventType === "ORDER_TRANSFERRED" ? { target_staff_id: targetStaffId.trim() } : {}),
            payload,
        };
        const previousPlan = plan;
        setSending(true); setError(null); setNotice(null);
        try {
            await appendEvent(date, event);
            // The current backend applies event replay during POST /plan, not during GET /plan.
            await generatePlan(date);
            const [latestPlan, history] = await Promise.all([getPlan(date), getEventHistory(date)]);
            setPlan(latestPlan); setEventHistory(history); setModelNow(eventNow);
            setPlanComparison(comparePlanVersions(previousPlan, latestPlan, EVENT_LABELS[eventType]));
            if (eventType === "NEW_ORDER" && typeof payload.order === "object" && payload.order !== null &&
                "order_id" in payload.order && typeof payload.order.order_id === "string") {
                const newOrder = payload.order as Order;
                setAllOrders(current => current.some(item => item.order_id === newOrder.order_id)
                    ? current : [...current, newOrder]);
                setSelectedOrderId(newOrder.order_id);
            }
            setNotice("Событие принято. План пересчитан до версии v" + latestPlan.plan_version + ".");
        } catch (cause) { setError(getErrorMessage(cause)); }
        finally { setSending(false); }
    }

    const totalOrders = plan?.summary?.orders_total ?? allOrders.filter((order) => offsetDate(order.slot.start) === date).length;

    return (
        <div className="v1-workspace">
            <div className="v1-page-heading">
                <div><div className="eyebrow">STAGE E2 · API V1</div><h1>Рабочее место диспетчера</h1>
                    <p>Маршруты, анализ изменений плана, объяснение назначений и контроль рисков.</p></div>
                <div className="v1-heading-actions">
                    <label className="v1-date-control"><CalendarDays size={16} />
                        <input type="date" value={date} onChange={(event) => {
                            const value = event.target.value; setDate(value);
                            setModelNow(value + modelNow.slice(10)); setEventNow(value + eventNow.slice(10));
                            setPlan(null); setEventHistory(null); setPlanComparison(null); setSelectedOrderId(null);
                        }} /></label>
                    <button className="secondary-button" type="button" onClick={() => void handleRefresh()} disabled={refreshing}>
                        <RefreshCw size={15} className={refreshing ? "v1-spin" : ""} /> Обновить
                    </button>
                </div>
            </div>

            {error && <div className="error-banner" role="alert"><span>{error}</span><button type="button" onClick={() => setError(null)}>Закрыть</button></div>}
            {notice && <div className="v1-notice" role="status"><CheckCircle2 size={16} /><span>{notice}</span><button type="button" onClick={() => setNotice(null)}><XCircle size={15} /></button></div>}

            <details className="v1-setup-panel" open={!plan}>
                <summary><span><Upload size={17} /> Загрузка исходных данных</span><small>JSON / CSV · API организатора v1</small></summary>
                <div className="v1-setup-grid">
                    <label className="v1-file-field">Сервисные центры<input type="file" accept=".json,application/json" onChange={fileHandler(setServiceCentersFile)} /><small>{serviceCentersFile?.name ?? "service_centers.json"}</small></label>
                    <label className="v1-file-field">Сотрудники<input type="file" accept=".json,application/json" onChange={fileHandler(setStaffFile)} /><small>{staffFile?.name ?? "staff.json"}</small></label>
                    <label className="v1-file-field">Узлы доступа<input type="file" accept=".json,application/json" onChange={fileHandler(setAccessNodesFile)} /><small>{accessNodesFile?.name ?? "access_nodes.json"}</small></label>
                    <label className="v1-file-field">Типы работ<input type="file" accept=".csv,text/csv" onChange={fileHandler(setWorkTypesFile)} /><small>{workTypesFile?.name ?? "work_types.csv"}</small></label>
                    <label className="v1-file-field">Матрица маршрутов<input type="file" accept=".json,application/json" onChange={fileHandler(setMatrixFile)} /><small>{matrixFile?.name ?? "travel_matrix.json"}</small></label>
                    <button className="primary-button v1-upload-button" type="button" onClick={() => void handleUploadReference()} disabled={uploading}>
                        {uploading ? <RefreshCw size={16} className="v1-spin" /> : <Upload size={16} />}
                        {uploading ? "Загружаем..." : "Загрузить справочники и матрицу"}
                    </button>
                </div>
                <div className="v1-day-upload">
                    <label className="v1-file-field v1-orders-file">Наряды дня / orders_train.json<input type="file" accept=".json,application/json" onChange={fileHandler(setOrdersFile)} /><small>{ordersFile?.name ?? "Выбрать JSON"}</small></label>
                    <label className="v1-text-field">Модельное время now<input value={modelNow} onChange={(event) => setModelNow(event.target.value)} placeholder="2026-10-26T08:00:00+05:00" /></label>
                    <label className="v1-text-field">Недоступные сотрудники<input value={absentStaffInput} onChange={(event) => setAbsentStaffInput(event.target.value)} placeholder="ST-004, ST-011" /></label>
                    <button className="primary-button" type="button" onClick={() => void handleBuildPlan()} disabled={building || !reference}>
                        {building ? <RefreshCw size={16} className="v1-spin" /> : <Play size={16} />}
                        {building ? "Строим план..." : "Загрузить день и построить план"}
                    </button>
                </div>
                <p className="v1-setup-note">Исходные файлы остаются в браузере. После обновления страницы справочники для карты нужно загрузить повторно; сохранённый план можно получить через «Обновить».</p>
            </details>

            {plan ? <Fragment>
                <div className="v1-kpi-grid">
                    <div className="v1-kpi"><span>Наряды в дне</span><strong>{totalOrders}</strong><small>Входной набор</small></div>
                    <div className="v1-kpi"><span>Визиты в плане</span><strong>{plan.summary?.visits_planned ?? plan.routes.reduce((sum, route) => sum + route.stops.filter((stop) => stop.type === "VISIT").length, 0)}</strong><small>План v{plan.plan_version}</small></div>
                    <div className="v1-kpi"><span>Нераспределённые</span><strong className={plan.unassigned.length ? "danger" : ""}>{plan.unassigned.length}</strong><small>Требуют решения</small></div>
                    <div className="v1-kpi"><span>Риски опоздания</span><strong className={activeAlerts.length ? "warning" : ""}>{activeAlerts.length}</strong><small>Активные LATE_RISK</small></div>
                    <div className="v1-kpi"><span>В пути</span><strong>{formatDuration(plan.summary?.travel_sec_total ?? 0)}</strong><small>Сумма по маршрутам</small></div>
                </div>

                <div className="v1-toolbar">
                    <div className="v1-plan-meta"><span className="v1-live-dot" /> План v{plan.plan_version} · на {formatDateTime(plan.as_of)} · расчёт {plan.computation_ms} мс</div>
                    <label className="v1-filter"><Filter size={14} /><select value={staffFilter} onChange={(event) => setStaffFilter(event.target.value)}>
                        <option value="ALL">Все сотрудники</option>
                        {staffOptions.map((staff) =>
                            <option key={staff.staff_id} value={staff.staff_id}>{staff.display_name || staff.staff_id}</option>)}
                    </select></label>
                </div>

                {planComparison && <PlanComparisonPanel
                    comparison={planComparison}
                    onClose={() => setPlanComparison(null)}
                />}

                <div className="v1-main-grid">
                    <section className="v1-card v1-map-card">
                        <div className="v1-card-heading"><div><h2><MapPinned size={17} /> Карта маршрутов</h2><p>Последовательность остановок сотрудников</p></div><span className="v1-card-count">{mapRoutes.length} маршрутов</span></div>
                        {mapPoints.length ? <MapContainer center={[56.8389, 60.6057]} zoom={11} scrollWheelZoom className="v1-map">
                            <TileLayer attribution="&copy; OpenStreetMap contributors" url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
                            <MapAutoFit points={mapPoints} />
                            {mapRoutes.map(({ route, points }, index) => {
                                const color = ROUTE_COLORS[index % ROUTE_COLORS.length];
                                const positions = points.map((point) => [point.lat, point.lon] as [number, number]);
                                return <Fragment key={route.staff_id}>
                                    {positions.length > 1 && <Polyline positions={positions} pathOptions={{ color, weight: 4, opacity: 0.75 }} />}
                                    {points.map(({ stop, lat, lon, title }) => <CircleMarker key={route.staff_id + "-" + stop.seq + "-" + stop.location_id}
                                        center={[lat, lon]} radius={stop.order_id && selectedOrderId === stop.order_id ? 9 : 6}
                                        eventHandlers={{ click: () => setSelectedOrderId(stop.order_id ?? null) }}
                                        pathOptions={{ color: stop.order_id && selectedOrderId === stop.order_id ? "#111827" : color, fillColor: color, fillOpacity: 0.9, weight: stop.order_id && selectedOrderId === stop.order_id ? 3 : 1 }}>
                                        <Popup><strong>{stop.type}</strong><br />{stop.order_id || stop.location_id}<br />{title}<br />{formatTime(stop.start)}–{formatTime(stop.end)}<br />{route.staff_id}</Popup>
                                    </CircleMarker>)}
                                </Fragment>;
                            })}
                        </MapContainer> : <div className="v1-map-empty"><MapPinned size={28} /><strong>Нет координат для отображения</strong><span>Загрузи справочники, чтобы связать location_id с адресами.</span></div>}
                        <div className="v1-map-legend">{mapRoutes.map(({ route }, index) => <span key={route.staff_id}><i style={{ background: ROUTE_COLORS[index % ROUTE_COLORS.length] }} />{route.staff_id}</span>)}</div>
                        <p className="v1-map-footnote">Линии показывают порядок посещения. Геометрия дорожного маршрута в V1 PlanResponse пока не передаётся.</p>
                    </section>

                    <section className="v1-card v1-gantt-card">
                        <div className="v1-card-heading"><div><h2><Activity size={17} /> Загрузка смен</h2><p>Временная шкала остановок</p></div><span className="v1-card-count">{filteredRoutes.length} сотрудников</span></div>
                        <div className="v1-gantt-scale"><div className="v1-gantt-label-spacer" /><div className="v1-gantt-ticks">
                            {[8, 10, 12, 14, 16, 18, 20, 22].map((hour) => <span key={hour} style={{ left: (((hour * 60 - TIME_START) / (TIME_END - TIME_START)) * 100) + "%" }}>{String(hour).padStart(2, "0")}:00</span>)}
                        </div></div>
                        <div className="v1-gantt-list">
                            {filteredRoutes.map((route, index) => <GanttRow key={route.staff_id} route={route}
                                staff={reference?.staff.find((member) => member.staff_id === route.staff_id)}
                                selectedOrderId={selectedOrderId} onSelectOrder={setSelectedOrderId}
                                color={ROUTE_COLORS[index % ROUTE_COLORS.length]} />)}
                            {!filteredRoutes.length && <div className="v1-empty-inline">Нет маршрутов для выбранного фильтра.</div>}
                        </div>
                        <div className="v1-gantt-legend"><span><i className="visit" /> Визит</span><span><i className="break" /> Перерыв</span><span><i className="depot" /> СЦ / RESTOCK</span></div>
                    </section>
                </div>

                {selectedOrderId && <SelectedOrderDetails
                    orderId={selectedOrderId}
                    order={allOrders.find(order => order.order_id === selectedOrderId) ?? null}
                    plan={plan}
                    reference={reference}
                    onClose={() => setSelectedOrderId(null)}
                />}

                <section className="v1-card v1-lists-card">
                    <div className="v1-card-heading"><div><h2><Layers3 size={17} /> Контроль дня</h2><p>Состояния, требующие внимания диспетчера</p></div></div>
                    <div className="v1-tabs">
                        <button type="button" className={activePanel === "unassigned" ? "active" : ""} onClick={() => setActivePanel("unassigned")}>Нераспределённые <b>{plan.unassigned.length}</b></button>
                        <button type="button" className={activePanel === "risk" ? "active" : ""} onClick={() => setActivePanel("risk")}>Риск опоздания <b>{activeAlerts.length}</b></button>
                        <button type="button" className={activePanel === "pgp" ? "active" : ""} onClick={() => setActivePanel("pgp")}>ПГП <b>{plan.pgp_groups.length}</b></button>
                        <button type="button" className={activePanel === "events" ? "active" : ""} onClick={() => setActivePanel("events")}>События <b>{eventHistory?.events.length ?? 0}</b></button>
                    </div>

                    {activePanel === "unassigned" && <div className="v1-issue-list">
                        {plan.unassigned.map((item: UnassignedOrder) => <button key={item.order_id} type="button"
                            className={"v1-issue-row" + (selectedOrderId === item.order_id ? " selected" : "")}
                            onClick={() => setSelectedOrderId(item.order_id)}>
                            <span className="v1-issue-icon"><AlertTriangle size={17} /></span>
                            <span className="v1-issue-main"><strong>{item.order_id}</strong><small>{item.reason_text}</small></span>
                            <span className="v1-issue-code">{item.reason_code}</span>
                        </button>)}
                        {!plan.unassigned.length && <div className="v1-empty-inline"><CheckCircle2 size={17} /> Все наряды распределены.</div>}
                    </div>}

                    {activePanel === "risk" && <div className="v1-issue-list">
                        {activeAlerts.map((alert: PlanAlert) => <button key={alert.alert_id} type="button"
                            className={"v1-issue-row" + (selectedOrderId === alert.order_id ? " selected" : "")}
                            onClick={() => setSelectedOrderId(alert.order_id ?? null)}>
                            <span className="v1-issue-icon risk"><Clock3 size={17} /></span>
                            <span className="v1-issue-main"><strong>{alert.order_id || alert.alert_id}</strong><small>{alert.message}</small></span>
                            <span className="v1-risk-value">+{alert.lateness_min ?? 0} мин</span>
                        </button>)}
                        {!activeAlerts.length && <div className="v1-empty-inline"><CheckCircle2 size={17} /> Активных LATE_RISK нет.</div>}
                    </div>}

                    {activePanel === "pgp" && <div className="v1-issue-list">
                        {plan.pgp_groups.map((group) => <div key={group.group_id} className="v1-pgp-row">
                            <div className="v1-pgp-header"><strong>{group.group_id}</strong><span>{group.access_node_id}</span></div>
                            <p>{group.comment || group.action}</p>
                            <div className="v1-pgp-orders">{group.order_ids.map((id) => <button key={id} type="button"
                                className={selectedOrderId === id ? "selected" : ""} onClick={() => setSelectedOrderId(id)}>{id}</button>)}</div>
                            <small>Обнаружено: {formatDateTime(group.detected_at)}</small>
                        </div>)}
                        {!plan.pgp_groups.length && <div className="v1-empty-inline">Групповых проблем не обнаружено.</div>}
                    </div>}

                    {activePanel === "events" && <div className="v1-issue-list">
                        {(eventHistory?.events ?? []).slice().reverse().map((event) => <div className="v1-event-history-row" key={event.event_id}>
                            <span className="v1-event-dot" /><div><strong>{EVENT_LABELS[event.event_type] || event.event_type}</strong>
                                <small>{event.order_id || event.staff_id || event.event_id} · {formatDateTime(event.occurred_at)}</small></div>
                            <code>{event.event_sequence}</code>
                        </div>)}
                        {!eventHistory?.events.length && <div className="v1-empty-inline">События ещё не поступали.</div>}
                    </div>}
                </section>

                <section className="v1-card v1-event-card">
                    <div className="v1-card-heading"><div><h2><Send size={17} /> Зарегистрировать событие</h2>
                        <p>После приёма событие воспроизводится при пересчёте плана.</p></div><span className="v1-card-count">POST /events → POST /plan</span></div>
                    <div className="v1-event-form">
                        <label className="v1-text-field">Тип события<select value={eventType} onChange={(event) => setEventType(event.target.value as EventType)}>
                            {Object.entries(EVENT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                        </select></label>
                        <label className="v1-text-field">Время события<input value={eventNow} onChange={(event) => setEventNow(event.target.value)} /></label>
                        {["NEW_ORDER", "ORDER_CANCELLED", "VISIT_DELAYED", "ORDER_TRANSFERRED"].includes(eventType) &&
                            <label className="v1-text-field">ID наряда<input value={eventOrderId} onChange={(event) => setEventOrderId(event.target.value)} placeholder="ORD-20261026-0001" /></label>}
                        {eventType === "STAFF_UNAVAILABLE" && <label className="v1-text-field">ID сотрудника<input value={eventStaffId} onChange={(event) => setEventStaffId(event.target.value)} placeholder="ST-001" /></label>}
                        {eventType === "ORDER_TRANSFERRED" && <label className="v1-text-field">Передать сотруднику<input value={targetStaffId} onChange={(event) => setTargetStaffId(event.target.value)} placeholder="ST-002" /></label>}
                        {eventType === "VISIT_DELAYED" && <label className="v1-text-field">Задержка, мин<input type="number" min="1" value={delayMin} onChange={(event) => setDelayMin(event.target.value)} /></label>}
                        {eventType === "NEW_ORDER" && <label className="v1-text-field v1-new-order-json">JSON нового наряда<textarea rows={5} value={newOrderJson} onChange={(event) => setNewOrderJson(event.target.value)} placeholder='{"order_id":"ORD-...","work_type_code":"REPAIR", ...}' /></label>}
                        <button type="button" className="primary-button v1-event-submit" onClick={() => void handleSendEvent()} disabled={sending}>
                            {sending ? <RefreshCw size={15} className="v1-spin" /> : <Send size={15} />}
                            {sending ? "Пересчитываем..." : "Отправить и перепланировать"}
                        </button>
                    </div>
                    <p className="v1-setup-note"><AlertTriangle size={14} /> Event API только записывает событие. UI отдельно вызывает генерацию плана, чтобы применить replay C1–C4 в текущей реализации backend.</p>
                </section>
            </Fragment> : <div className="v1-empty-workspace"><Route size={32} /><h2>План дня ещё не загружен</h2>
                <p>Загрузи справочники, матрицу и наряды, затем построй план. Здесь появятся карта, график смен и контрольные списки.</p></div>}
        </div>
    );
}
