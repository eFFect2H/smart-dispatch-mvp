import type {
    DispatchEvent,
    EventScenarioFile,
    Order,
    PlanResponse,
    PlanRoute,
    PlanStop,
    ScenarioTrigger,
    WorkType,
} from "../types/v1";

const OFFSET_RE = /([+-])(\d{2}):(\d{2})$|Z$/;

/** Смещение ISO-строки в минутах (`+05:00` → 300). Без смещения — 0. */
export function offsetMinutes(iso: string): number {
    const match = OFFSET_RE.exec(iso);
    if (!match || match[0] === "Z") return 0;
    const sign = match[1] === "-" ? -1 : 1;
    return sign * (Number(match[2]) * 60 + Number(match[3]));
}

/** Сдвигает момент времени на `minutes`, сохраняя исходное смещение. */
export function addMinutes(iso: string, minutes: number): string {
    return formatWithOffset(Date.parse(iso) + minutes * 60_000, offsetMinutes(iso));
}

export function formatWithOffset(epochMs: number, offsetMin: number): string {
    const shifted = new Date(epochMs + offsetMin * 60_000);
    const pad = (n: number, w = 2) => String(n).padStart(w, "0");
    const sign = offsetMin < 0 ? "-" : "+";
    const abs = Math.abs(offsetMin);
    return (
        shifted.getUTCFullYear() + "-" + pad(shifted.getUTCMonth() + 1) + "-" + pad(shifted.getUTCDate()) +
        "T" + pad(shifted.getUTCHours()) + ":" + pad(shifted.getUTCMinutes()) + ":" + pad(shifted.getUTCSeconds()) +
        sign + pad(Math.floor(abs / 60)) + ":" + pad(abs % 60)
    );
}

export function isIsoWithOffset(value: string): boolean {
    return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?([+-]\d{2}:\d{2}|Z)$/.test(value) &&
        Number.isFinite(Date.parse(value));
}

export function newEventId(date: string, existing: number): string {
    const rnd = typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID().slice(0, 8)
        : Math.random().toString(16).slice(2, 10);
    return "EV-" + date.replace(/-/g, "") + "-" + String(existing + 1).padStart(4, "0") + "-" + rnd;
}

/**
 * Тело запроса `POST /events`.
 *
 * Основа — формат `api_spec.yaml` (`type`, `timestamp`, поля по типу). Дополнительно
 * дублируются поля прежнего формата backend (`event_type`, `occurred_at`, `order_id`,
 * `staff_id`, `payload`): спецификация требует игнорировать неизвестные поля, поэтому
 * строгий сервис их пропустит, а сервис со старой схемой событий сможет разобрать запрос.
 */
export function toWireEvent(
    event: DispatchEvent,
    hints: { delayMin?: number } = {},
): Record<string, unknown> {
    const legacy: Record<string, unknown> = {
        event_type: event.type,
        occurred_at: event.timestamp,
    };
    switch (event.type) {
        case "NEW_ORDER":
            legacy.order_id = event.order.order_id;
            legacy.payload = { order: event.order };
            break;
        case "ORDER_CANCELLED":
            legacy.payload = { reason: event.reason };
            break;
        case "VISIT_DELAYED":
            legacy.payload = hints.delayMin != null ? { delay_min: hints.delayMin } : {};
            break;
        case "ORDER_TRANSFERRED":
            legacy.payload = { transferred_to: event.transferred_to, actual_end: event.actual_end };
            break;
        case "STAFF_UNAVAILABLE":
            legacy.payload = { until: event.until ?? null, reason: event.reason };
            break;
        default:
            legacy.payload = {};
    }
    return { ...legacy, ...event };
}

export interface VisitRef {
    route: PlanRoute;
    stop: PlanStop;
}

export function findVisit(plan: PlanResponse, orderId: string): VisitRef | null {
    for (const route of plan.routes) {
        const stop = route.stops.find((item) => item.type === "VISIT" && item.order_id === orderId);
        if (stop) return { route, stop };
    }
    return null;
}

export function visitStarted(stop: PlanStop, modelNow: string): boolean {
    return Date.parse(stop.start) < Date.parse(modelNow);
}

export interface ScenarioStep {
    trigger: ScenarioTrigger;
    event: DispatchEvent | null;
    /** Причина, по которой триггер не превратился в событие. */
    skippedReason?: string;
}

/**
 * Превращает триггер сценария в событие по текущему плану (api_spec.yaml, EventScenarioFile):
 * `timestamp = VISIT.start + fire_after_start_min`, `staff_id` — исполнитель из плана.
 * Если наряда нет в маршрутах, триггер пропускается. Время не может уйти назад
 * относительно модельного времени — иначе сервис вернёт EVENT_OUT_OF_ORDER.
 */
export function triggerToEvent(
    trigger: ScenarioTrigger,
    plan: PlanResponse,
    orders: Map<string, Order>,
    workTypes: Map<string, WorkType>,
    modelNow: string,
    eventId: string,
): ScenarioStep {
    const visit = findVisit(plan, trigger.order_id);
    if (!visit) return { trigger, event: null, skippedReason: "наряд не в маршруте" };
    const rawFire = addMinutes(visit.stop.start, trigger.fire_after_start_min);
    const timestamp = Date.parse(rawFire) < Date.parse(modelNow) ? modelNow : rawFire;
    const staff_id = visit.route.staff_id;

    if (trigger.type === "ORDER_TRANSFERRED") {
        return {
            trigger,
            event: {
                event_id: eventId, type: "ORDER_TRANSFERRED", timestamp, staff_id,
                order_id: trigger.order_id, actual_end: timestamp,
                transferred_to: trigger.transferred_to ?? "STATION_EQUIPMENT",
            },
        };
    }
    const order = orders.get(trigger.order_id);
    const duration = order ? workTypes.get(order.work_type_code)?.duration_min : undefined;
    const planned = duration != null
        ? addMinutes(visit.stop.start, duration + (trigger.extra_duration_min ?? 0))
        : addMinutes(visit.stop.end, trigger.extra_duration_min ?? 0);
    return {
        trigger,
        event: {
            event_id: eventId, type: "VISIT_DELAYED", timestamp, staff_id,
            order_id: trigger.order_id, expected_end: planned,
        },
    };
}

/** Время срабатывания триггера по текущему плану; `null`, если наряда в плане нет. */
export function triggerFireTime(trigger: ScenarioTrigger, plan: PlanResponse): string | null {
    const visit = findVisit(plan, trigger.order_id);
    return visit ? addMinutes(visit.stop.start, trigger.fire_after_start_min) : null;
}

export function validateScenario(raw: unknown): EventScenarioFile {
    if (typeof raw !== "object" || raw === null) throw new Error("ожидался объект сценария событий");
    const obj = raw as Partial<EventScenarioFile>;
    if (!Array.isArray(obj.events)) throw new Error("в сценарии нет массива events");
    if (typeof obj.date !== "string") throw new Error("в сценарии нет date");
    return {
        date: obj.date,
        now: typeof obj.now === "string" ? obj.now : obj.date + "T08:30:00+05:00",
        events: [...obj.events].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)),
        triggers: Array.isArray(obj.triggers) ? obj.triggers : [],
    };
}
