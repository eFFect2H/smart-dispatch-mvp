import { api } from "./client";
import type { Team } from "../types";

export interface TeamCreateRequest {
    name: string;
    capacity: number;
    startLatitude: number;
    startLongitude: number;
    shiftStartMinute: number;
    shiftEndMinute: number;
    active: boolean;
}

export async function getTeams(): Promise<Team[]> {
    const response = await api.get("/api/teams");
    const data = response.data;

    if (Array.isArray(data)) {
        return data as Team[];
    }

    if (Array.isArray(data?.value)) {
        return data.value as Team[];
    }

    throw new Error("API /api/teams вернул неожиданный формат данных");
}

export async function createTeam(
    team: TeamCreateRequest,
): Promise<Team> {
    const response = await api.post("/api/teams", team);
    return response.data as Team;
}