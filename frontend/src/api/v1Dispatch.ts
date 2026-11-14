import axios from "axios";
import { api, TIMEOUTS_MS } from "./client";
import { toWireEvent } from "../utils/events";
import type {
    ApiError,
    ApiLoadResult,
    DispatchEvent,
    EventResult,
    HealthResponse,
    DemoInfoResponse,
    DemoDayResponse,
    PlanResponse,
} from "../types/v1";

export async function getDemoInfo(): Promise<DemoInfoResponse> {
    const response = await api.get("/api/v1/demo");
    return response.data as DemoInfoResponse;
}

export async function getReference(): Promise<ReferenceData> {
    const response = await api.get("/api/v1/reference");
    return response.data as ReferenceData;
}

export async function initializeDemoDay(date: string): Promise<DemoDayResponse> {
    const response = await api.post(`/api/v1/demo/days/${date}/initialize`);
    return response.data as DemoDayResponse;
}

export async function getHealth(): Promise<HealthResponse> {
    const response = await api.get("/api/v1/health", { timeout: TIMEOUTS_MS.health });
    return response.data as HealthResponse;
}

export async function uploadReference(data: ReferenceData): Promise<ApiLoadResult> {
    const response = await api.put("/api/v1/reference", data);
    return response.data as ApiLoadResult;
}

export async function uploadTravelMatrix(data: TravelMatrix): Promise<ApiLoadResult> {
    const response = await api.put("/api/v1/reference/travel-matrix", data);
    return response.data as ApiLoadResult;
}

export async function uploadDay(date: string, data: DayInput): Promise<ApiLoadResult> {
    const response = await api.put(`/api/v1/days/${date}`, data);
    return response.data as ApiLoadResult;
}

/** `POST /plan` — первоначальный план (plan_version = 1). До 300 с. */
export async function generatePlan(date: string, timeLimitSec = 300): Promise<PlanResponse> {
    const response = await api.post(
        `/api/v1/days/${date}/plan`,
        { time_limit_sec: timeLimitSec },
        { timeout: TIMEOUTS_MS.plan },
    );
    return response.data as PlanResponse;
}

export async function getPlan(date: string): Promise<PlanResponse> {
    const response = await api.get(`/api/v1/days/${date}/plan`);
    return response.data as PlanResponse;
}

/** Ответ POST /events приводится к EventResult; старый backend отдаёт status/model_now. */
function normalizeEventResult(raw: unknown, event: DispatchEvent): EventResult {
    const data = (raw ?? {}) as Partial<EventResult> & { status?: string; model_now?: string };
    return {
        event_id: data.event_id ?? event.event_id,
        accepted: data.accepted ?? true,
        duplicate: Boolean(data.duplicate),
        plan_version: data.plan_version ?? 0,
        as_of: data.as_of ?? data.model_now ?? event.timestamp,
        processing_ms: data.processing_ms,
        changed_staff_ids: data.changed_staff_ids,
        new_alerts: data.new_alerts,
        message: data.message,
    };
}

export async function postEvent(
    date: string,
    event: DispatchEvent,
    hints: { delayMin?: number } = {},
): Promise<EventResult> {
    const response = await api.post(`/api/v1/days/${date}/events`, toWireEvent(event, hints), {
        timeout: TIMEOUTS_MS.event,
    });
    return normalizeEventResult(response.data, event);
}

export interface EventOutcome {
    result: EventResult;
    plan: PlanResponse;
    /** true, если пришлось вызвать POST /plan, потому что сервис не перепланировал сам. */
    usedLegacyReplan: boolean;
}

/**
 * Сценарий шага 6 из api_spec.yaml: `POST /events`, затем `GET /plan`.
 *
 * Спецификация: событие само перепланирует, а `POST /plan` после первого события
 * возвращает 409. Backend прежней версии только записывает событие и ждёт `POST /plan`.
 * Если актуальный план не отражает событие (`based_on_event_id` не совпал), один раз
 * вызывается `POST /plan`; ответ 409 при этом не считается ошибкой.
 */
export async function sendEvent(
    date: string,
    event: DispatchEvent,
    hints: { delayMin?: number } = {},
): Promise<EventOutcome> {
    const result = await postEvent(date, event, hints);
    let plan = await getPlan(date);
    let usedLegacyReplan = false;
    if (!result.duplicate && plan.based_on_event_id !== event.event_id) {
        try {
            await generatePlan(date);
            usedLegacyReplan = true;
        } catch (error) {
            if (!(axios.isAxiosError(error) && error.response?.status === 409)) throw error;
        }
        plan = await getPlan(date);
    }
    return {
        result: { ...result, plan_version: result.plan_version || plan.plan_version, as_of: plan.as_of },
        plan,
        usedLegacyReplan,
    };
}

/** Текст ошибки из ErrorResponse (`error.code/message/details`) либо сетевой ошибки. */
export function describeApiError(error: unknown): string {
    if (axios.isAxiosError(error)) {
        if (error.code === "ECONNABORTED") {
            return "Сервис не ответил за отведённое время (" + Math.round((error.config?.timeout ?? 0) / 1000) + " с).";
        }
        if (!error.response) {
            return "Нет связи с сервисом. Проверь, что backend запущен на порту 8080 (GET /api/v1/health).";
        }
        const data = error.response.data as { error?: ApiError; message?: string } | string | undefined;
        if (typeof data === "string" && data) return "HTTP " + error.response.status + ": " + data.slice(0, 300);
        if (data && typeof data === "object") {
            if (data.error?.message) {
                const details = (data.error.details ?? [])
                    .map((item) => (item.field ? item.field + ": " : "") + item.message)
                    .slice(0, 3).join("; ");
                return data.error.code + ": " + data.error.message + (details ? " (" + details + ")" : "");
            }
            if (data.message) return data.message;
        }
        return "Ошибка API: HTTP " + error.response.status;
    }
    return error instanceof Error ? error.message : "Произошла непредвиденная ошибка.";
}
