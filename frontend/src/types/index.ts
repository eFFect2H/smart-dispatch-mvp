export interface ApiCollection<T> {
    value: T[];
    Count: number;
}

export interface Team {
    id: number;
    name: string;
    capacity: number;
    startLatitude: number;
    startLongitude: number;
    shiftStartMinute: number;
    shiftEndMinute: number;
    active: boolean;
}

export interface TransportRequest {
    id: number;
    title: string;
    address: string;
    latitude: number;
    longitude: number;
    windowStartMinute: number;
    windowEndMinute: number;
    serviceDurationMinutes: number;
    demand: number;
    status: string;
    assignedTeamId: number | null;
    plannedArrivalMinute: number | null;
    createdAt: string;
}

export interface TransportRequestCreateRequest {
    title: string;
    address: string;
    latitude: number;
    longitude: number;
    windowStartMinute: number;
    windowEndMinute: number;
    serviceDurationMinutes: number;
    demand: number;
}

export interface DispatchRoutePoint {
    latitude: number;
    longitude: number;
}

export interface DispatchStopPlan {
    requestId: number;
    title: string;
    address: string;
    latitude: number;
    longitude: number;
    arrivalMinute: number;
    travelFromPreviousMinutes: number;
    serviceDurationMinutes: number;
}

export interface DispatchRoutePlan {
    teamId: number;
    teamName: string;
    baseLatitude: number;
    baseLongitude: number;
    shiftStartMinute: number;
    finishMinute: number;
    totalTravelMinutes: number;
    stops: DispatchStopPlan[];
    geometry?: DispatchRoutePoint[];
}

export interface DispatchPlanResponse {
    assignedRequests: number;
    totalTravelMinutes: number;
    routes: DispatchRoutePlan[];
}