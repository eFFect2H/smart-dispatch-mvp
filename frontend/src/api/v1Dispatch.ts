import { api } from "./client";
import type {
    ApiLoadResult,
    DayInput,
    EventHistory,
    EventRequest,
    PlanResponse,
    ReferenceData,
    TravelMatrix,
} from "../types/v1";

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

export async function generatePlan(date: string, timeLimitSec = 300): Promise<PlanResponse> {
    const response = await api.post(`/api/v1/days/${date}/plan`, {
        time_limit_sec: timeLimitSec,
    });
    return response.data as PlanResponse;
}

export async function getPlan(date: string): Promise<PlanResponse> {
    const response = await api.get(`/api/v1/days/${date}/plan`);
    return response.data as PlanResponse;
}

export async function appendEvent(date: string, event: EventRequest): Promise<unknown> {
    const response = await api.post(`/api/v1/days/${date}/events`, event);
    return response.data;
}

export async function getEventHistory(date: string): Promise<EventHistory> {
    const response = await api.get(`/api/v1/days/${date}/events`);
    return response.data as EventHistory;
}
