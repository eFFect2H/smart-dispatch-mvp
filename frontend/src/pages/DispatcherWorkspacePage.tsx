import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent, CSSProperties } from "react";
import {
    Activity, AlertTriangle, CalendarDays, CheckCircle2, Clock3, Filter, Layers3,
    ListVideo, MapPinned, Play, RefreshCw, Route, Send, Square, Upload, UserX, XCircle,
} from "lucide-react";
import { CircleMarker, MapContainer, Polyline, Popup, TileLayer, useMap } from "react-leaflet";
import "leaflet/dist/leaflet.css";

import {
    describeApiError, generatePlan, getHealth, getPlan, sendEvent, uploadDay,
    uploadReference, uploadTravelMatrix,
} from "../api/v1Dispatch";
import PlanComparisonPanel from "../components/dispatch/PlanComparisonPanel";
import SelectedOrderDetails from "../components/dispatch/SelectedOrderDetails";
import { comparePlanVersions } from "../utils/planInsights";
import type { PlanComparison } from "../utils/planInsights";
import {
    addMinutes, findVisit, isIsoWithOffset, newEventId, offsetMinutes,
    triggerFireTime, triggerToEvent, validateScenario,
} from "../utils/events";
import { parseWorkTypes } from "../utils/workTypesCsv";
import type {
    AccessNode, CancelReason, DayInput, DispatchEvent, EventLogEntry, EventType, Order,
    PlanAlert, PlanResponse, PlanRoute, PlanStop, ReferenceData, ScenarioTrigger,
    ServiceCenter, StaffMember, TransferTarget, TravelMatrix, UnassignedOrder, UnavailableReason,
} from "../types/v1";

const ROUTE_COLORS = ["#3563e9", "#0d9488", "#d97706", "#be4b8a", "#6477d7", "#7c5ce7"];
const TIME_START = 8 * 60;
const TIME_END = 22 * 60;
const EVENT_LIMIT_MS = 10_000;
const REFERENCE_STORAGE_KEY = "smart-dispatch.reference.v1";

const EVENT_LABELS: Record<EventType, string> = {
    NEW_ORDER: "Новый наряд",
    ORDER_CANCELLED: "Отмена наряда",
    VISIT_DELAYED: "Задержка визита",
    ORDER_TRANSFERRED: "Передача в профильное подразделение",
    STAFF_UNAVAILABLE: "Сотрудник недоступен",
    TIME_TICK: "Ход времени",
};
const REASON_LABELS: Record<string, string> = {
    NO_QUALIFIED_STAFF: "Нет сотрудника с нужным допуском",
    NO_SHIFT_TIME: "Не хватает времени смены",
    SLOT_UNREACHABLE: "Не успевают в слот",
    NO_EQUIPMENT: "Нет нужного АО",
    STAFF_UNAVAILABLE: "Исполнитель выбыл",
    OTHER: "Другая причина",
};
const ACTION_LABELS: Record<string, string> = {
    RESCHEDULE: "Перенести визит",
    REASSIGN: "Передать другому сотруднику",
    ESCALATE: "Решение диспетчера",
    TRANSFER_TO_STATION: "Передать в станционное подразделение",
    NONE: "—",
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
function offsetSuffix(iso: string): string {
    const total = offsetMinutes(iso);
    const sign = total < 0 ? "-" : "+";
    const abs = Math.abs(total);
    return sign + String(Math.floor(abs / 60)).padStart(2, "0") + ":" + String(abs % 60).padStart(2, "0");
}

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
function selectDayOrders(allOrders: Order[], date: string, modelNow: string): Order[] {
    if (!isIsoWithOffset(modelNow)) {
        throw new Error("Укажи now в формате ISO-8601 со смещением, например 2026-10-26T08:30:00+05:00");
    }
    const nowMs = Date.parse(modelNow);
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
function loadStoredReference(): ReferenceData | null {
    try {
        const raw = localStorage.getItem(REFERENCE_STORAGE_KEY);
        return raw ? JSON.parse(raw) as ReferenceData : null;
    } catch {
        return null;
    }
}
function storeReference(reference: ReferenceData) {
    try {
        localStorage.setItem(REFERENCE_STORAGE_KEY, JSON.stringify(reference));
    } catch {
        // Хранилище недоступно или переполнено — карта просто потребует повторной загрузки справочников.
    }
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
function trackPercent(minute: number): string {
    return (((Math.min(TIME_END, Math.max(TIME_START, minute)) - TIME_START) / (TIME_END - TIME_START)) * 100) + "%";
}

function GanttRow(props: {
    route: PlanRoute; staff?: StaffMember; selectedOrderId: string | null;
    onSelectOrder: (id: string | null) => void; color: string;
    onMarkUnavailable: (staffId: string) => void; busy: boolean; nowMinute: number | null;
}) {
    const [confirmUnavailable, setConfirmUnavailable] = useState(false);
    const staffStart = minuteOfDay(props.staff?.shift.start) ?? TIME_START;
    const staffEnd = minuteOfDay(props.staff?.shift.end) ?? TIME_END;
    const shiftStyle = {
        left: trackPercent(staffStart),
        width: (((staffEnd - staffStart) / (TIME_END - TIME_START)) * 100) + "%",
    } as CSSProperties;
    const unavailableFrom = minuteOfDay(props.route.unavailable_from);
    const unavailableTo = minuteOfDay(props.route.unavailable_until) ?? staffEnd;
    const unavailable = props.route.status === "UNAVAILABLE" && unavailableFrom !== null;
    return (
        <div className="v1-gantt-row">
            <div className="v1-gantt-person">
                <span className="v1-gantt-avatar">{(props.staff?.display_name || props.route.staff_id).slice(0, 1)}</span>
                <span><strong><StaffLabel staffId={props.route.staff_id} staff={props.staff} /></strong><small>{props.route.staff_id}</small></span>
                {confirmUnavailable ? (
                    <span className="v1-staff-confirm">
                        <button type="button" className="v1-staff-confirm-yes" disabled={props.busy}
                            onClick={() => { setConfirmUnavailable(false); props.onMarkUnavailable(props.route.staff_id); }}>Да</button>
                        <button type="button" className="v1-staff-confirm-no"
                            onClick={() => setConfirmUnavailable(false)}>Нет</button>
                    </span>
                ) : (
                    <button type="button" className="v1-staff-unavailable-button"
                        title={props.route.status === "UNAVAILABLE" ? "Сотрудник уже отмечен как недоступный" : "Отметить сотрудника недоступным до конца смены"}
                        aria-label={"Отметить " + props.route.staff_id + " недоступным"}
                        disabled={props.busy || props.route.status === "UNAVAILABLE"}
                        onClick={() => setConfirmUnavailable(true)}>
                        <UserX size={13} />
                    </button>
                )}
            </div>
            <div className="v1-gantt-track">
                <div className="v1-gantt-shift" style={shiftStyle} />
                {unavailable && unavailableFrom !== null && <div className="v1-gantt-unavailable"
                    title={"Недоступен с " + formatTime(props.route.unavailable_from) +
                        (props.route.unavailable_until ? " до " + formatTime(props.route.unavailable_until) : " до конца смены")}
                    style={{
                        left: trackPercent(unavailableFrom),
                        width: (((Math.max(unavailableTo, unavailableFrom) - unavailableFrom) / (TIME_END - TIME_START)) * 100) + "%",
                    }} />}
                {props.route.stops.map((stop: PlanStop) => {
                    const start = minuteOfDay(stop.start) ?? minuteOfDay(stop.arrival);
                    const end = minuteOfDay(stop.end) ?? start;
                    if (start === null || end === null) return null;
                    const clippedStart = Math.max(TIME_START, start);
                    const clippedEnd = Math.min(TIME_END, Math.max(end, start + 3));
                    const style = {
                        left: trackPercent(clippedStart),
                        width: Math.max(0.5, ((clippedEnd - clippedStart) / (TIME_END - TIME_START)) * 100) + "%",
                        backgroundColor: stop.type === "VISIT" ? props.color : stop.type === "BREAK" ? "#94a3b8" : "#cbd5e1",
                    } as CSSProperties;
                    const selected = Boolean(stop.order_id && props.selectedOrderId === stop.order_id);
                    return <button key={props.route.staff_id + "-" + stop.seq} type="button"
                        className={"v1-gantt-stop" + (selected ? " selected" : "")} style={style}
                        title={stop.type + (stop.order_id ? " · " + stop.order_id : "") + " · " + formatTime(stop.start) + "–" + formatTime(stop.end)}
                        onClick={() => props.onSelectOrder(stop.order_id ?? null)} />;
                })}
                {props.nowMinute !== null && <div className="v1-gantt-now" style={{ left: trackPercent(props.nowMinute) }} />}
            </div>
        </div>
    );
}

export default function DispatcherWorkspacePage() {
    const today = localToday();
    const [date, setDate] = useState(today);
    const [modelNow, setModelNow] = useState(today + "T08:30:00+05:00");
    const [serviceCentersFile, setServiceCentersFile] = useState<File | null>(null);
    const [staffFile, setStaffFile] = useState<File | null>(null);
    const [accessNodesFile, setAccessNodesFile] = useState<File | null>(null);
    const [workTypesFile, setWorkTypesFile] = useState<File | null>(null);
    const [matrixFile, setMatrixFile] = useState<File | null>(null);
    const [ordersFile, setOrdersFile] = useState<File | null>(null);
    const [scenarioFile, setScenarioFile] = useState<File | null>(null);
    const [reference, setReference] = useState<ReferenceData | null>(loadStoredReference);
    const [allOrders, setAllOrders] = useState<Order[]>([]);
    const [orderDates, setOrderDates] = useState<string[]>([]);
    const [plan, setPlan] = useState<PlanResponse | null>(null);
    const [planComparison, setPlanComparison] = useState<PlanComparison | null>(null);
    const [eventLog, setEventLog] = useState<EventLogEntry[]>([]);
    const [absentStaffInput, setAbsentStaffInput] = useState("");
    const [eventType, setEventType] = useState<EventType>("VISIT_DELAYED");
    const [eventOrderId, setEventOrderId] = useState("");
    const [eventStaffId, setEventStaffId] = useState("");
    const [delayMin, setDelayMin] = useState("15");
    const [cancelReason, setCancelReason] = useState<CancelReason>("NOT_CONFIRMED");
    const [transferTarget, setTransferTarget] = useState<TransferTarget>("STATION_EQUIPMENT");
    const [unavailableReason, setUnavailableReason] = useState<UnavailableReason>("VEHICLE_BREAKDOWN");
    const [unavailableUntil, setUnavailableUntil] = useState("");
    const [eventNow, setEventNow] = useState(today + "T09:00:00+05:00");
    const [newOrderJson, setNewOrderJson] = useState("");
    const [staffFilter, setStaffFilter] = useState("ALL");
    const [activePanel, setActivePanel] = useState<"unassigned" | "risk" | "pgp" | "events">("unassigned");
    const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
    const [uploading, setUploading] = useState(false);
    const [building, setBuilding] = useState(false);
    const [sending, setSending] = useState(false);
    const [refreshing, setRefreshing] = useState(false);
    const [scenarioProgress, setScenarioProgress] = useState<string | null>(null);
    const [health, setHealth] = useState<{ state: "checking" | "ok" | "down"; label?: string }>({ state: "checking" });
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const scenarioAbort = useRef(false);

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
    const lateRiskAlerts = (plan?.alerts ?? []).filter((alert: PlanAlert) => alert.type === "LATE_RISK");
    const activeAlerts = lateRiskAlerts.filter((alert) => alert.status === "ACTIVE");
    const resolvedAlerts = lateRiskAlerts.filter((alert) => alert.status !== "ACTIVE");
    const staffOptions = reference?.staff ?? (plan?.routes ?? []).map((route) => ({
        staff_id: route.staff_id,
        display_name: route.staff_id,
    }));
    const planOrderIds = useMemo(() => (plan?.routes ?? []).flatMap((route) =>
        route.stops.filter((stop) => stop.type === "VISIT" && stop.order_id).map((stop) => stop.order_id as string)), [plan]);
    const busy = sending || building || uploading;

    // Состояние сервиса: GET /api/v1/health.
    useEffect(() => {
        let alive = true;
        const check = () => getHealth()
            .then((info) => alive && setHealth({ state: "ok", label: [info.team, info.version].filter(Boolean).join(" · ") }))
            .catch(() => alive && setHealth({ state: "down" }));
        void check();
        const timer = window.setInterval(() => void check(), 15_000);
        return () => { alive = false; window.clearInterval(timer); };
    }, []);

    // Если сервис уже построил план на выбранную дату, показываем его (например, после перезагрузки страницы).
    useEffect(() => {
        let alive = true;
        getPlan(date)
            .then((latest) => {
                if (!alive) return;
                setPlan(latest);
                setEventNow(latest.as_of);
                setModelNow(latest.as_of);
            })
            .catch(() => { /* 404 — план ещё не построен */ });
        return () => { alive = false; };
    }, [date]);

    function fileHandler(setter: (file: File | null) => void) {
        return (event: ChangeEvent<HTMLInputElement>) => setter(event.target.files?.[0] ?? null);
    }
    function resetDayState() {
        setPlan(null); setEventLog([]); setPlanComparison(null); setSelectedOrderId(null);
    }
    function changeDate(value: string) {
        if (!value) return;
        setDate(value);
        setModelNow(value + modelNow.slice(10));
        setEventNow(value + eventNow.slice(10));
        resetDayState();
    }
    function applyPlan(next: PlanResponse) {
        setPlan(next);
        setEventNow(next.as_of);
    }

    async function handleOrdersFile(event: ChangeEvent<HTMLInputElement>) {
        const file = event.target.files?.[0] ?? null;
        setOrdersFile(file);
        if (!file) return;
        try {
            const orders = unwrapArray<Order>(await readJson(file), "orders", file.name);
            const dates = [...new Set(orders.map((order) => offsetDate(order.slot?.start ?? "")).filter(Boolean))].sort();
            setAllOrders(orders);
            setOrderDates(dates);
            setError(null);
            if (dates.length && !dates.includes(date)) changeDate(dates[0]);
            setNotice("В файле " + orders.length + " нарядов за " + dates.length + " дн.: " + dates.join(", ") + ".");
        } catch (cause) {
            setOrderDates([]);
            setError(describeApiError(cause));
        }
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
            // Загрузка справочников удаляет все дни, планы и события на сервере.
            setReference(nextReference);
            storeReference(nextReference);
            resetDayState();
            await uploadTravelMatrix(nextMatrix);
            setNotice("Справочники и матрица загружены: " + nextReference.staff.length + " сотрудников, " +
                nextReference.service_centers.length + " СЦ, " + nextReference.access_nodes.length +
                " узлов. Состояние дней сброшено — загрузи день заново.");
        } catch (cause) { setError(describeApiError(cause)); }
        finally { setUploading(false); }
    }

    async function handleBuildPlan() {
        if (!allOrders.length) { setError("Выбери orders_train.json или файл нарядов одного дня."); return; }
        if (!reference) { setError("Сначала загрузи справочники и матрицу."); return; }
        setBuilding(true); setError(null); setNotice(null);
        try {
            const selectedOrders = selectDayOrders(allOrders, date, modelNow);
            if (!selectedOrders.length) {
                throw new Error("В файле нет нарядов со слотом на " + date + " и registered_at не позже " + modelNow + ".");
            }
            const dayInput: DayInput = {
                date, now: modelNow, orders: selectedOrders,
                absent_staff_ids: absentStaffInput.split(",").map((value) => value.trim()).filter(Boolean),
            };
            const previousPlan = plan?.date === date ? plan : null;
            await uploadDay(date, dayInput);
            resetDayState();
            const built = await generatePlan(date);
            applyPlan(built);
            setPlanComparison(comparePlanVersions(previousPlan, built, "Перестроение исходного дня"));
            setNotice("План v" + built.plan_version + " построен за " + (built.computation_ms ?? 0) +
                " мс. В день загружено " + selectedOrders.length + " нарядов.");
            setActivePanel("unassigned");
        } catch (cause) { setError(describeApiError(cause)); }
        finally { setBuilding(false); }
    }

    async function handleRefresh() {
        setRefreshing(true); setError(null);
        try {
            const previousPlan = plan?.date === date ? plan : null;
            const latest = await getPlan(date);
            const comparison = comparePlanVersions(previousPlan, latest, "Обновление из API");
            if (comparison) setPlanComparison(comparison);
            applyPlan(latest);
            setModelNow(latest.as_of);
            setNotice("Получен план v" + latest.plan_version + " на " + date + ".");
        } catch (cause) { setError(describeApiError(cause)); }
        finally { setRefreshing(false); }
    }

    /** Одно событие: POST /events → GET /plan. Журнал и план обновляются сразу. */
    async function runEvent(event: DispatchEvent, hints: { delayMin?: number } = {}) {
        const started = performance.now();
        try {
            const outcome = await sendEvent(date, event, hints);
            const elapsed = Math.round(performance.now() - started);
            setEventLog((log) => [...log, { event, result: outcome.result, elapsed_ms: elapsed }]);
            applyPlan(outcome.plan);
            if (event.type === "NEW_ORDER") {
                const added = event.order;
                setAllOrders((current) => current.some((item) => item.order_id === added.order_id)
                    ? current : [...current, added]);
            }
            return { ...outcome, elapsed };
        } catch (cause) {
            const message = describeApiError(cause);
            setEventLog((log) => [...log, { event, result: null, elapsed_ms: Math.round(performance.now() - started), error: message }]);
            throw new Error(message, { cause });
        }
    }

    function validateEventTimestamp(): boolean {
        if (!plan) { setError("Сначала построй план дня."); return false; }
        if (!isIsoWithOffset(eventNow) || !eventNow.startsWith(date + "T")) {
            setError("Время события должно относиться к " + date + " и содержать смещение, например +05:00.");
            return false;
        }
        if (Date.parse(eventNow) < Date.parse(plan.as_of)) {
            setError("Время события не может быть раньше текущего модельного времени " + formatTime(plan.as_of) + ".");
            return false;
        }
        return true;
    }

    async function submitDispatchEvent(event: DispatchEvent, actionLabel: string, hints: { delayMin?: number } = {}): Promise<boolean> {
        const previousPlan = plan;
        setSending(true); setError(null); setNotice(null);
        try {
            const outcome = await runEvent(event, hints);
            setPlanComparison(comparePlanVersions(previousPlan, outcome.plan, actionLabel));
            const parts = [outcome.result.duplicate
                ? "Повтор события — состояние не изменилось."
                : "Событие принято, план v" + outcome.plan.plan_version + "."];
            if (outcome.result.new_alerts?.length) parts.push("Новых предупреждений: " + outcome.result.new_alerts.length + ".");
            parts.push("Ответ за " + outcome.elapsed + " мс.");
            if (outcome.usedLegacyReplan) parts.push("Сервис не перепланировал сам — выполнен POST /plan.");
            if (outcome.elapsed > EVENT_LIMIT_MS) parts.push("⚠ Дольше лимита 10 с — по правилам оценки событие не обработано в срок.");
            setNotice(parts.join(" "));
            return true;
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : String(cause));
            return false;
        } finally {
            setSending(false);
        }
    }

    function startedVisit(orderId: string) {
        if (!plan) return null;
        const visit = findVisit(plan, orderId);
        if (!visit) { setError("Наряд " + orderId + " не стоит ни в одном маршруте."); return null; }
        if (Date.parse(visit.stop.start) > Date.parse(eventNow)) {
            setError("Визит по " + orderId + " начнётся в " + formatTime(visit.stop.start) +
                ", а время события " + formatTime(eventNow) + ". Задержка и передача принимаются только для начатого визита — сдвинь время.");
            return null;
        }
        return visit;
    }

    async function handleTransferOrder(orderId: string, target: TransferTarget) {
        if (!validateEventTimestamp()) return;
        const visit = startedVisit(orderId);
        if (!visit) return;
        await submitDispatchEvent({
            event_id: newEventId(date, eventLog.length), type: "ORDER_TRANSFERRED", timestamp: eventNow,
            staff_id: visit.route.staff_id, order_id: orderId, actual_end: eventNow, transferred_to: target,
        }, "Передача в профильное подразделение " + orderId);
    }

    async function handleDelayOrder(orderId: string, delay: number) {
        if (!validateEventTimestamp()) return;
        if (!Number.isInteger(delay) || delay <= 0) { setError("Задержка должна быть положительным целым числом минут."); return; }
        const visit = startedVisit(orderId);
        if (!visit) return;
        await submitDispatchEvent({
            event_id: newEventId(date, eventLog.length), type: "VISIT_DELAYED", timestamp: eventNow,
            staff_id: visit.route.staff_id, order_id: orderId, expected_end: addMinutes(visit.stop.end, delay),
        }, "Задержка визита " + orderId, { delayMin: delay });
    }

    async function handleCancelOrder(orderId: string, reason: CancelReason) {
        if (!validateEventTimestamp()) return;
        await submitDispatchEvent({
            event_id: newEventId(date, eventLog.length), type: "ORDER_CANCELLED", timestamp: eventNow,
            order_id: orderId, reason,
        }, "Отмена наряда " + orderId);
    }

    async function handleStaffUnavailable(staffId: string) {
        if (!validateEventTimestamp()) return;
        await submitDispatchEvent({
            event_id: newEventId(date, eventLog.length), type: "STAFF_UNAVAILABLE", timestamp: eventNow,
            staff_id: staffId, until: null, reason: "OTHER",
        }, "Сотрудник недоступен: " + staffId);
    }

    async function handleTimeTick() {
        if (!validateEventTimestamp()) return;
        await submitDispatchEvent({
            event_id: newEventId(date, eventLog.length), type: "TIME_TICK", timestamp: eventNow,
        }, "Ход времени до " + formatTime(eventNow));
    }

    async function handleSendEvent() {
        if (!plan) { setError("Сначала построй план дня."); return; }
        if (!validateEventTimestamp()) return;
        const id = newEventId(date, eventLog.length);
        const orderId = eventOrderId.trim();
        const staffId = eventStaffId.trim();
        switch (eventType) {
            case "NEW_ORDER": {
                let order: Order;
                try {
                    const parsed = JSON.parse(newOrderJson) as Record<string, unknown>;
                    const candidate = "order" in parsed ? parsed.order : parsed;
                    if (!candidate || typeof candidate !== "object" ||
                        typeof (candidate as Order).order_id !== "string") throw new Error("не найден объект order с order_id");
                    order = candidate as Order;
                } catch (cause) {
                    setError("Проверь JSON нового наряда: " + (cause instanceof Error ? cause.message : "ошибка формата"));
                    return;
                }
                await submitDispatchEvent({ event_id: id, type: "NEW_ORDER", timestamp: eventNow, order }, "Новый наряд " + order.order_id);
                setSelectedOrderId(order.order_id);
                return;
            }
            case "ORDER_CANCELLED":
                if (!orderId) { setError("Укажи наряд."); return; }
                await submitDispatchEvent({ event_id: id, type: "ORDER_CANCELLED", timestamp: eventNow, order_id: orderId, reason: cancelReason }, "Отмена наряда " + orderId);
                return;
            case "VISIT_DELAYED": {
                const delay = Number(delayMin);
                if (!orderId) { setError("Укажи наряд."); return; }
                if (!Number.isInteger(delay) || delay <= 0) { setError("Задержка должна быть положительным целым числом минут."); return; }
                const visit = startedVisit(orderId);
                if (!visit) return;
                await submitDispatchEvent({
                    event_id: id, type: "VISIT_DELAYED", timestamp: eventNow, staff_id: visit.route.staff_id,
                    order_id: orderId, expected_end: addMinutes(visit.stop.end, delay),
                }, "Задержка визита " + orderId, { delayMin: delay });
                return;
            }
            case "ORDER_TRANSFERRED": {
                if (!orderId) { setError("Укажи наряд."); return; }
                const visit = startedVisit(orderId);
                if (!visit) return;
                await submitDispatchEvent({
                    event_id: id, type: "ORDER_TRANSFERRED", timestamp: eventNow, staff_id: visit.route.staff_id,
                    order_id: orderId, actual_end: eventNow, transferred_to: transferTarget,
                }, "Передача в профильное подразделение " + orderId);
                return;
            }
            case "STAFF_UNAVAILABLE": {
                if (!staffId) { setError("Выбери сотрудника."); return; }
                let until: string | null = null;
                if (unavailableUntil) {
                    until = date + "T" + unavailableUntil + ":00" + offsetSuffix(eventNow);
                    if (Date.parse(until) <= Date.parse(eventNow)) { setError("Конец недоступности должен быть позже времени события."); return; }
                }
                await submitDispatchEvent({
                    event_id: id, type: "STAFF_UNAVAILABLE", timestamp: eventNow, staff_id: staffId, until, reason: unavailableReason,
                }, "Сотрудник недоступен: " + staffId);
                return;
            }
            case "TIME_TICK":
                await handleTimeTick();
                return;
        }
    }

    /**
     * Прогон events_example.json так, как это делает оркестратор организаторов: события по возрастанию
     * timestamp; перед каждым событием отправляются сработавшие триггеры (VISIT_DELAYED /
     * ORDER_TRANSFERRED), которые разворачиваются по текущему плану.
     */
    async function handleRunScenario() {
        if (!plan) { setError("Сначала построй план дня."); return; }
        if (!scenarioFile) { setError("Выбери events_example.json."); return; }
        scenarioAbort.current = false;
        setSending(true); setError(null); setNotice(null);
        const startPlan = plan;
        let current = plan;
        let sent = 0;
        let skipped = 0;
        try {
            const scenario = validateScenario(await readJson(scenarioFile));
            if (scenario.date !== date) {
                throw new Error("Сценарий составлен на " + scenario.date + ", а открыт день " + date + ".");
            }
            const orders = new Map(allOrders.map((order) => [order.order_id, order]));
            const workTypes = new Map((reference?.work_types ?? []).map((item) => [item.code, item]));
            const pending: ScenarioTrigger[] = [...scenario.triggers];
            const total = scenario.events.length + scenario.triggers.length;
            const fire = async (event: DispatchEvent) => {
                setScenarioProgress("Событие " + (sent + 1) + " из ≤" + total + " · " + EVENT_LABELS[event.type] + " · " + formatTime(event.timestamp));
                const outcome = await runEvent(event);
                current = outcome.plan;
                sent += 1;
                if (event.type === "NEW_ORDER") orders.set(event.order.order_id, event.order);
                if (outcome.elapsed > EVENT_LIMIT_MS) throw new Error("Событие " + event.event_id + " обработано за " + outcome.elapsed + " мс — дольше лимита 10 с.");
            };
            const flushDue = async (limit: string | null) => {
                while (!scenarioAbort.current) {
                    let best: { trigger: ScenarioTrigger; at: string } | null = null;
                    for (const trigger of pending) {
                        const at = triggerFireTime(trigger, current);
                        if (!at) continue;
                        if (limit !== null && Date.parse(at) > Date.parse(limit)) continue;
                        if (!best || Date.parse(at) < Date.parse(best.at)) best = { trigger, at };
                    }
                    if (!best) return;
                    pending.splice(pending.indexOf(best.trigger), 1);
                    const step = triggerToEvent(best.trigger, current, orders, workTypes, current.as_of, newEventId(date, sent + eventLog.length));
                    if (step.event) await fire(step.event); else skipped += 1;
                }
            };
            for (const event of scenario.events) {
                await flushDue(event.timestamp);
                if (scenarioAbort.current) break;
                await fire(event);
            }
            if (!scenarioAbort.current) await flushDue(null);
            skipped += pending.length;
            setPlanComparison(comparePlanVersions(startPlan, current, "Сценарий событий"));
            setNotice((scenarioAbort.current ? "Сценарий остановлен. " : "Сценарий выполнен. ") +
                "Отправлено событий: " + sent + ", пропущено триггеров: " + skipped + ". Итоговый план v" + current.plan_version + ".");
        } catch (cause) {
            setPlanComparison(comparePlanVersions(startPlan, current, "Сценарий событий (прерван)"));
            setError("Сценарий прерван после " + sent + " событий: " + describeApiError(cause));
        } finally {
            setScenarioProgress(null);
            setSending(false);
        }
    }

    const totalOrders = plan?.summary?.orders_total ?? allOrders.filter((order) => offsetDate(order.slot.start) === date).length;
    const nowMinute = plan ? minuteOfDay(plan.as_of) : null;
    const timeBehind = Boolean(plan && isIsoWithOffset(eventNow) && Date.parse(eventNow) < Date.parse(plan.as_of));

    return (
        <div className="v1-workspace">
            <div className="v1-page-heading">
                <div><div className="eyebrow">API V1 · api_spec.yaml</div><h1>Рабочее место диспетчера</h1>
                    <p>Маршруты, предупреждения о рисках и оперативное перепланирование по событиям смены.</p></div>
                <div className="v1-heading-actions">
                    <span className={"v1-health v1-health-" + health.state}
                        title={health.state === "down" ? "GET /api/v1/health не отвечает" : health.label}>
                        <i /> {health.state === "ok" ? "Сервис готов" + (health.label ? " · " + health.label : "")
                            : health.state === "down" ? "Сервис недоступен" : "Проверка сервиса…"}
                    </span>
                    <label className="v1-date-control"><CalendarDays size={16} />
                        {orderDates.length > 0 ? (
                            <select value={orderDates.includes(date) ? date : ""} onChange={(event) => changeDate(event.target.value)}>
                                {!orderDates.includes(date) && <option value="">{date}</option>}
                                {orderDates.map((value) => <option key={value} value={value}>{value}</option>)}
                            </select>
                        ) : (
                            <input type="date" value={date} onChange={(event) => changeDate(event.target.value)} />
                        )}
                    </label>
                    <button className="secondary-button" type="button" onClick={() => void handleRefresh()} disabled={refreshing || busy}>
                        <RefreshCw size={15} className={refreshing ? "v1-spin" : ""} /> Обновить
                    </button>
                </div>
            </div>

            {error && <div className="error-banner" role="alert"><span>{error}</span><button type="button" onClick={() => setError(null)}>Закрыть</button></div>}
            {notice && <div className="v1-notice" role="status"><CheckCircle2 size={16} /><span>{notice}</span><button type="button" onClick={() => setNotice(null)}><XCircle size={15} /></button></div>}

            <details className="v1-setup-panel" open={!plan}>
                <summary><span><Upload size={17} /> Загрузка исходных данных</span><small>JSON / CSV · шаги 2–5 сценария оценки</small></summary>
                <div className="v1-setup-grid">
                    <label className="v1-file-field">Сервисные центры<input type="file" accept=".json,application/json" onChange={fileHandler(setServiceCentersFile)} /><small>{serviceCentersFile?.name ?? "service_centers.json"}</small></label>
                    <label className="v1-file-field">Сотрудники<input type="file" accept=".json,application/json" onChange={fileHandler(setStaffFile)} /><small>{staffFile?.name ?? "staff.json"}</small></label>
                    <label className="v1-file-field">Узлы доступа<input type="file" accept=".json,application/json" onChange={fileHandler(setAccessNodesFile)} /><small>{accessNodesFile?.name ?? "access_nodes.json"}</small></label>
                    <label className="v1-file-field">Типы работ<input type="file" accept=".csv,text/csv" onChange={fileHandler(setWorkTypesFile)} /><small>{workTypesFile?.name ?? "work_types.csv"}</small></label>
                    <label className="v1-file-field">Матрица маршрутов<input type="file" accept=".json,application/json" onChange={fileHandler(setMatrixFile)} /><small>{matrixFile?.name ?? "travel_matrix.json"}</small></label>
                    <button className="primary-button v1-upload-button" type="button" onClick={() => void handleUploadReference()} disabled={busy}>
                        {uploading ? <RefreshCw size={16} className="v1-spin" /> : <Upload size={16} />}
                        {uploading ? "Загружаем..." : "Загрузить справочники и матрицу"}
                    </button>
                </div>
                <div className="v1-day-upload">
                    <label className="v1-file-field v1-orders-file">Наряды дня / orders_train.json<input type="file" accept=".json,application/json" onChange={(event) => void handleOrdersFile(event)} /><small>{ordersFile?.name ?? "Выбрать JSON"}</small></label>
                    <label className="v1-text-field">Модельное время now<input value={modelNow} onChange={(event) => setModelNow(event.target.value)} placeholder="2026-10-26T08:30:00+05:00" /></label>
                    <label className="v1-text-field">Не выходят на смену<input value={absentStaffInput} onChange={(event) => setAbsentStaffInput(event.target.value)} placeholder="ST-004, ST-011" /></label>
                    <button className="primary-button" type="button" onClick={() => void handleBuildPlan()} disabled={busy || !reference}>
                        {building ? <RefreshCw size={16} className="v1-spin" /> : <Play size={16} />}
                        {building ? "Строим план (до 5 мин)..." : "Загрузить день и построить план"}
                    </button>
                </div>
                <p className="v1-setup-note">
                    {reference
                        ? "Справочники для карты сохранены в браузере (" + reference.staff.length + " сотрудников, " + reference.service_centers.length + " СЦ). "
                        : "Справочники ещё не загружены. "}
                    Файлы читаются в браузере и отправляются напрямую в API; повторная загрузка справочников удаляет все дни, планы и события на сервере.
                </p>
            </details>

            {plan ? <Fragment>
                <div className="v1-kpi-grid">
                    <div className="v1-kpi"><span>Наряды в дне</span><strong>{totalOrders}</strong><small>Входной набор</small></div>
                    <div className="v1-kpi"><span>Визиты в плане</span><strong>{plan.summary?.visits_planned ?? planOrderIds.length}</strong><small>План v{plan.plan_version}</small></div>
                    <div className="v1-kpi"><span>Нераспределённые</span><strong className={plan.unassigned.length ? "danger" : ""}>{plan.unassigned.length}</strong><small>Требуют решения</small></div>
                    <div className="v1-kpi"><span>Риски опоздания</span><strong className={activeAlerts.length ? "warning" : ""}>{activeAlerts.length}</strong><small>Активные LATE_RISK</small></div>
                    <div className="v1-kpi"><span>В пути</span><strong>{formatDuration(plan.summary?.travel_sec_total ?? plan.routes.reduce((sum, route) => sum + (route.totals?.travel_sec ?? 0), 0))}</strong><small>Сумма по маршрутам</small></div>
                </div>

                <div className="v1-toolbar">
                    <div className="v1-plan-meta"><span className="v1-live-dot" /> План v{plan.plan_version} · модельное время {formatDateTime(plan.as_of)}{plan.computation_ms != null ? " · расчёт " + plan.computation_ms + " мс" : ""}</div>
                    <div className="v1-time-control" title="Модельное время для следующего события. Чтобы сервис обновил прогнозы без события, нажми «Применить»">
                        <Clock3 size={14} />
                        <input type="time" value={isIsoWithOffset(eventNow) ? eventNow.slice(11, 16) : ""}
                            onChange={(event) => event.target.value && setEventNow(date + "T" + event.target.value + ":00" + offsetSuffix(eventNow))} />
                        {[15, 30, 60].map((step) => <button key={step} type="button" className="secondary-button"
                            onClick={() => setEventNow(addMinutes(eventNow, step))}>+{step}</button>)}
                        <button type="button" className="secondary-button" disabled={busy || timeBehind} onClick={() => void handleTimeTick()}>Применить</button>
                    </div>
                    <label className="v1-filter"><Filter size={14} /><select value={staffFilter} onChange={(event) => setStaffFilter(event.target.value)}>
                        <option value="ALL">Все сотрудники</option>
                        {staffOptions.map((staff) =>
                            <option key={staff.staff_id} value={staff.staff_id}>{staff.display_name || staff.staff_id}</option>)}
                    </select></label>
                </div>
                {timeBehind && <div className="error-banner" role="alert"><span>Время события {formatTime(eventNow)} раньше модельного времени плана {formatTime(plan.as_of)} — сервис вернёт EVENT_OUT_OF_ORDER.</span>
                    <button type="button" onClick={() => setEventNow(plan.as_of)}>Сбросить</button></div>}

                {planComparison && <PlanComparisonPanel comparison={planComparison} onClose={() => setPlanComparison(null)} />}

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
                        <p className="v1-map-footnote">Линии показывают порядок посещения по прямой; геометрия дорожного маршрута в ответе API (Plan) не передаётся.</p>
                    </section>

                    <section className="v1-card v1-gantt-card">
                        <div className="v1-card-heading"><div><h2><Activity size={17} /> Загрузка смен</h2><p>Временная шкала остановок · пунктир — модельное время</p></div><span className="v1-card-count">{filteredRoutes.length} сотрудников</span></div>
                        <div className="v1-gantt-scale"><div className="v1-gantt-label-spacer" /><div className="v1-gantt-ticks">
                            {[8, 10, 12, 14, 16, 18, 20, 22].map((hour) => <span key={hour} style={{ left: (((hour * 60 - TIME_START) / (TIME_END - TIME_START)) * 100) + "%" }}>{String(hour).padStart(2, "0")}:00</span>)}
                        </div></div>
                        <div className="v1-gantt-list">
                            {filteredRoutes.map((route, index) => <GanttRow key={route.staff_id} route={route}
                                staff={reference?.staff.find((member) => member.staff_id === route.staff_id)}
                                selectedOrderId={selectedOrderId} onSelectOrder={setSelectedOrderId}
                                onMarkUnavailable={(staffId) => void handleStaffUnavailable(staffId)}
                                busy={busy} nowMinute={nowMinute}
                                color={ROUTE_COLORS[index % ROUTE_COLORS.length]} />)}
                            {!filteredRoutes.length && <div className="v1-empty-inline">Нет маршрутов для выбранного фильтра.</div>}
                        </div>
                        <div className="v1-gantt-legend"><span><i className="visit" /> Визит</span><span><i className="break" /> Перерыв</span><span><i className="depot" /> СЦ / RESTOCK</span><span><i className="unavailable" /> Недоступен</span></div>
                    </section>
                </div>

                {selectedOrderId && <SelectedOrderDetails
                    orderId={selectedOrderId}
                    order={allOrders.find(order => order.order_id === selectedOrderId) ?? null}
                    plan={plan}
                    reference={reference}
                    modelNow={isIsoWithOffset(eventNow) ? eventNow : plan.as_of}
                    actionsDisabled={busy}
                    onTransferOrder={(target) => void handleTransferOrder(selectedOrderId, target)}
                    onDelayOrder={(delay) => void handleDelayOrder(selectedOrderId, delay)}
                    onCancelOrder={(reason) => void handleCancelOrder(selectedOrderId, reason)}
                    onClose={() => setSelectedOrderId(null)}
                />}

                <section className="v1-card v1-lists-card">
                    <div className="v1-card-heading"><div><h2><Layers3 size={17} /> Контроль дня</h2><p>Состояния, требующие внимания диспетчера</p></div></div>
                    <div className="v1-tabs">
                        <button type="button" className={activePanel === "unassigned" ? "active" : ""} onClick={() => setActivePanel("unassigned")}>Нераспределённые <b>{plan.unassigned.length}</b></button>
                        <button type="button" className={activePanel === "risk" ? "active" : ""} onClick={() => setActivePanel("risk")}>Риск опоздания <b>{activeAlerts.length}</b></button>
                        <button type="button" className={activePanel === "pgp" ? "active" : ""} onClick={() => setActivePanel("pgp")}>ПГП <b>{plan.pgp_groups.length}</b></button>
                        <button type="button" className={activePanel === "events" ? "active" : ""} onClick={() => setActivePanel("events")}>События <b>{eventLog.length}</b></button>
                    </div>

                    {activePanel === "unassigned" && <div className="v1-issue-list">
                        {plan.unassigned.map((item: UnassignedOrder) => <button key={item.order_id} type="button"
                            className={"v1-issue-row" + (selectedOrderId === item.order_id ? " selected" : "")}
                            onClick={() => setSelectedOrderId(item.order_id)}>
                            <span className="v1-issue-icon"><AlertTriangle size={17} /></span>
                            <span className="v1-issue-main"><strong>{item.order_id} · {REASON_LABELS[item.reason_code] ?? item.reason_code}</strong>
                                <small>{item.reason_text} → {ACTION_LABELS[item.suggested_action] ?? item.suggested_action}
                                    {item.proposed_slot ? " (слот " + formatDateTime(item.proposed_slot.start) + "–" + formatTime(item.proposed_slot.end) + ")" : ""}</small></span>
                            <span className="v1-issue-code">{item.reason_code}</span>
                        </button>)}
                        {!plan.unassigned.length && <div className="v1-empty-inline"><CheckCircle2 size={17} /> Все наряды распределены.</div>}
                    </div>}

                    {activePanel === "risk" && <div className="v1-issue-list">
                        {activeAlerts.map((alert: PlanAlert) => <button key={alert.alert_id} type="button"
                            className={"v1-issue-row" + (selectedOrderId === alert.order_id ? " selected" : "")}
                            onClick={() => setSelectedOrderId(alert.order_id ?? null)}>
                            <span className="v1-issue-icon risk"><Clock3 size={17} /></span>
                            <span className="v1-issue-main"><strong>{alert.order_id || alert.alert_id}</strong>
                                <small>{alert.message} · {ACTION_LABELS[alert.suggested_action] ?? alert.suggested_action}
                                    {alert.suggested_staff_id ? " → " + alert.suggested_staff_id : ""}{alert.applied ? " · применено" : ""}
                                    {alert.forecast_start ? " · прогноз начала " + formatTime(alert.forecast_start) : ""}</small></span>
                            <span className="v1-risk-value">+{alert.lateness_min ?? 0} мин</span>
                        </button>)}
                        {resolvedAlerts.map((alert: PlanAlert) => <button key={alert.alert_id} type="button"
                            className="v1-issue-row v1-issue-resolved" onClick={() => setSelectedOrderId(alert.order_id ?? null)}>
                            <span className="v1-issue-icon"><CheckCircle2 size={17} /></span>
                            <span className="v1-issue-main"><strong>{alert.order_id || alert.alert_id} · закрыто</strong>
                                <small>{alert.message}{alert.applied ? " · предложение применено" : ""}</small></span>
                            <span className="v1-issue-code">{formatTime(alert.created_at)}</span>
                        </button>)}
                        {!lateRiskAlerts.length && <div className="v1-empty-inline"><CheckCircle2 size={17} /> Предупреждений LATE_RISK нет.</div>}
                    </div>}

                    {activePanel === "pgp" && <div className="v1-issue-list">
                        {plan.pgp_groups.map((group) => <div key={group.group_id} className="v1-pgp-row">
                            <div className="v1-pgp-header"><strong>{group.group_id}</strong><span>{group.access_node_id}</span></div>
                            <p>{group.comment || ACTION_LABELS[group.action] || group.action}</p>
                            <div className="v1-pgp-orders">{group.order_ids.map((id) => <button key={id} type="button"
                                className={selectedOrderId === id ? "selected" : ""} onClick={() => setSelectedOrderId(id)}>{id}</button>)}</div>
                            <small>Обнаружено: {formatDateTime(group.detected_at)}</small>
                        </div>)}
                        {!plan.pgp_groups.length && <div className="v1-empty-inline">Групповых проблем не обнаружено.</div>}
                    </div>}

                    {activePanel === "events" && <div className="v1-issue-list">
                        {eventLog.slice().reverse().map((entry) => <div className="v1-event-history-row" key={entry.event.event_id}>
                            <span className="v1-event-dot" style={entry.error ? { background: "#dc2626" } : undefined} />
                            <div><strong>{EVENT_LABELS[entry.event.type] || entry.event.type}</strong>
                                <small>{("order_id" in entry.event ? entry.event.order_id : "staff_id" in entry.event ? entry.event.staff_id : entry.event.event_id)} · {formatDateTime(entry.event.timestamp)} · {entry.elapsed_ms} мс{entry.elapsed_ms > EVENT_LIMIT_MS ? " (дольше 10 с!)" : ""}</small>
                                {entry.error && <small className="v1-event-error">{entry.error}</small>}</div>
                            <code>{entry.result ? "v" + entry.result.plan_version : "✕"}</code>
                        </div>)}
                        {!eventLog.length && <div className="v1-empty-inline">События из этого сеанса ещё не отправлялись (журнал хранится в браузере).</div>}
                    </div>}
                </section>

                <section className="v1-card v1-event-card">
                    <div className="v1-card-heading"><div><h2><Send size={17} /> Зарегистрировать событие</h2>
                        <p>Сервис сам перепланирует невыполненную часть плана; интерфейс затем запрашивает актуальный план.</p></div><span className="v1-card-count">POST /events → GET /plan</span></div>
                    <div className="v1-event-form">
                        <label className="v1-text-field">Тип события<select value={eventType} onChange={(event) => setEventType(event.target.value as EventType)}>
                            {Object.entries(EVENT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                        </select></label>
                        <label className="v1-text-field">Время события (ISO-8601)<input value={eventNow} onChange={(event) => setEventNow(event.target.value)} /></label>
                        {["ORDER_CANCELLED", "VISIT_DELAYED", "ORDER_TRANSFERRED"].includes(eventType) &&
                            <label className="v1-text-field">Наряд<input list="v1-plan-orders" value={eventOrderId} onChange={(event) => setEventOrderId(event.target.value)} placeholder="ORD-20261026-0001" /></label>}
                        {eventType === "STAFF_UNAVAILABLE" && <Fragment>
                            <label className="v1-text-field">Сотрудник<select value={eventStaffId} onChange={(event) => setEventStaffId(event.target.value)}>
                                <option value="">— выбери —</option>
                                {staffOptions.map((staff) => <option key={staff.staff_id} value={staff.staff_id}>{staff.display_name || staff.staff_id} · {staff.staff_id}</option>)}
                            </select></label>
                            <label className="v1-text-field">Недоступен до (пусто — до конца смены)<input type="time" value={unavailableUntil} onChange={(event) => setUnavailableUntil(event.target.value)} /></label>
                            <label className="v1-text-field">Причина<select value={unavailableReason} onChange={(event) => setUnavailableReason(event.target.value as UnavailableReason)}>
                                <option value="VEHICLE_BREAKDOWN">Поломка автомобиля</option>
                                <option value="ILLNESS">Болезнь</option>
                                <option value="OTHER">Другое</option>
                            </select></label>
                        </Fragment>}
                        {eventType === "ORDER_CANCELLED" && <label className="v1-text-field">Причина отмены<select value={cancelReason} onChange={(event) => setCancelReason(event.target.value as CancelReason)}>
                            <option value="NOT_CONFIRMED">Абонент не подтвердил визит</option>
                            <option value="CUSTOMER_REQUEST">Отказ абонента</option>
                        </select></label>}
                        {eventType === "ORDER_TRANSFERRED" && <label className="v1-text-field">Передано в<select value={transferTarget} onChange={(event) => setTransferTarget(event.target.value as TransferTarget)}>
                            <option value="STATION_EQUIPMENT">Станционное оборудование</option>
                            <option value="TRUNK_CABLE">Магистральный кабель</option>
                        </select></label>}
                        {eventType === "VISIT_DELAYED" && <label className="v1-text-field">Задержка, мин<input type="number" min="1" value={delayMin} onChange={(event) => setDelayMin(event.target.value)} /></label>}
                        {eventType === "NEW_ORDER" && <label className="v1-text-field v1-new-order-json">JSON нового наряда (Order или объект с полем order)<textarea rows={5} value={newOrderJson} onChange={(event) => setNewOrderJson(event.target.value)} placeholder='{"order_id":"ORD-...","work_type_code":"REPAIR", ...}' /></label>}
                        <button type="button" className="primary-button v1-event-submit" onClick={() => void handleSendEvent()} disabled={busy}>
                            {sending && !scenarioProgress ? <RefreshCw size={15} className="v1-spin" /> : <Send size={15} />}
                            {sending && !scenarioProgress ? "Перепланируем..." : "Отправить событие"}
                        </button>
                    </div>
                    <datalist id="v1-plan-orders">{planOrderIds.map((id) => <option key={id} value={id} />)}</datalist>
                    <p className="v1-setup-note"><AlertTriangle size={14} /> Задержка и передача принимаются только для начатого визита (start ≤ времени события), отмена — только для ещё не начатого. Время события не может быть раньше модельного времени плана.</p>

                    <div className="v1-scenario">
                        <label className="v1-file-field">Сценарий событий<input type="file" accept=".json,application/json" onChange={fileHandler(setScenarioFile)} /><small>{scenarioFile?.name ?? "events_example.json"}</small></label>
                        {scenarioProgress ? (
                            <button type="button" className="danger-button" onClick={() => { scenarioAbort.current = true; }}>
                                <Square size={14} /> Остановить · {scenarioProgress}
                            </button>
                        ) : (
                            <button type="button" className="secondary-button" disabled={busy || !scenarioFile} onClick={() => void handleRunScenario()}>
                                <ListVideo size={15} /> Прогнать сценарий
                            </button>
                        )}
                        <small>Как оркестратор: события по возрастанию времени, триггеры (задержка/передача) разворачиваются по текущему плану.</small>
                    </div>
                </section>
            </Fragment> : <div className="v1-empty-workspace"><Route size={32} /><h2>План дня ещё не загружен</h2>
                <p>Загрузи справочники и матрицу, выбери файл нарядов и нажми «Загрузить день и построить план». Здесь появятся карта, график смен и контрольные списки.</p></div>}
        </div>
    );
}
