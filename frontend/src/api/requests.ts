import { api } from "./client";
import type {
    TransportRequest,
    TransportRequestCreateRequest,
} from "../types";

export async function getRequests(): Promise<TransportRequest[]> {
    const response = await api.get("/api/requests");
    const data = response.data;

    if (Array.isArray(data)) {
        return data as TransportRequest[];
    }

    if (Array.isArray(data?.value)) {
        return data.value as TransportRequest[];
    }

    throw new Error(
        "API /api/requests вернул неожиданный формат данных",
    );
}

export async function createRequest(
    request: TransportRequestCreateRequest,
): Promise<TransportRequest> {
    const response = await api.post("/api/requests", request);
    return response.data as TransportRequest;
}