import { api } from "./client";
import type { DispatchPlanResponse } from "../types";

export async function optimizeDispatch(): Promise<DispatchPlanResponse> {
    const response = await api.post("/api/dispatch/optimize");
    return response.data as DispatchPlanResponse;
}