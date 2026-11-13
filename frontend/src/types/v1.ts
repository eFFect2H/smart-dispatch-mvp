export type Profile = "INSTALL" | "LTP3";
export type Technology = "PON" | "FTTB" | "XDSL" | "DOCSIS";
export type EventType =
    | "NEW_ORDER"
    | "ORDER_CANCELLED"
    | "VISIT_DELAYED"
    | "STAFF_UNAVAILABLE"
    | "ORDER_TRANSFERRED";

export interface Equipment {
    ONT: number;
    ROUTER: number;
    STB: number;
}

export interface ServiceCenter {
    service_center_id: string;
    name: string;
    address: string;
    lat: number;
    lon: number;
    opening_hours: { open: string; close: string };
    restock_duration_min: number;
}

export interface StaffMember {
    staff_id: string;
    display_name?: string;
    service_center_id: string;
    profiles: Profile[];
    technologies: Technology[];
    height_permit: boolean;
    shift: {
        start: string;
        end: string;
        break_duration_min: number;
        break_window: { from: string; to: string };
    };
    equipment_start: Equipment;
    equipment_capacity: Equipment;
    experience_years?: number;
}

export interface Building {
    building_id: string;
    address: string;
    lat: number;
    lon: number;
    building_type: "MKD" | "PRIVATE";
    overhead_line: boolean;
    zone_service_center_id?: string | null;
}

export interface AccessNode {
    access_node_id: string;
    node_type: "OLT" | "SWITCH" | "DSLAM" | "CMTS";
    technology: Technology;
    name: string;
    address?: string;
    lat: number;
    lon: number;
    buildings: Building[];
}

export interface WorkType {
    code: string;
    name: string;
    profiles: Profile[];
    duration_min: number;
    max_start_delay_min: number | null;
    deadline_kind: "SLOT" | "HOURS" | "WORKING_DAYS";
    deadline_value: number;
    repeat_deadline_value: number;
    pgp_relevant: boolean;
}

export interface ReferenceData {
    service_centers: ServiceCenter[];
    staff: StaffMember[];
    access_nodes: AccessNode[];
    work_types: WorkType[];
}

export interface TravelMatrix {
    matrix_id: string;
    points: string[];
    durations_sec: number[][];
    distances_m: number[][];
    hourly_factors?: { hour: number; factor: number }[];
}

export interface Order {
    order_id: string;
    work_type_code: string;
    technology: Technology;
    building_id: string;
    address: string;
    lat: number;
    lon: number;
    building_type: "MKD" | "PRIVATE";
    requires_height: boolean;
    access_node_id: string;
    registered_at: string;
    slot: { start: string; end: string };
    latest_start: string;
    deadline: string;
    required_equipment: Equipment;
    is_repeat: boolean;
    previous_order_id?: string | null;
}

export interface DayInput {
    date: string;
    now: string;
    orders: Order[];
    absent_staff_ids: string[];
}

export interface PlanStop {
    seq: number;
    type: string;
    location_id: string;
    arrival: string;
    start: string;
    end: string;
    order_id?: string | null;
    travel_sec?: number | null;
    distance_m?: number | null;
    status?: string | null;
    equipment_after?: Equipment | null;
}

export interface RouteTotals {
    visits: number;
    travel_sec: number;
    distance_m: number;
    work_min: number;
    utilization: number;
}

export interface PlanRoute {
    staff_id: string;
    service_center_id: string;
    status: string;
    stops: PlanStop[];
    totals?: RouteTotals | null;
}

export interface UnassignedOrder {
    order_id: string;
    reason_code: string;
    reason_text: string;
    suggested_action: string;
    proposed_slot?: { start: string; end: string } | null;
    since?: string | null;
}

export interface PgpGroup {
    group_id: string;
    access_node_id: string;
    order_ids: string[];
    detected_at: string;
    action: string;
    comment: string;
}

export interface CancelledOrder {
    order_id: string;
    cancelled_at: string;
    event_id: string;
}

export interface PlanAlert {
    alert_id: string;
    created_at: string;
    type: string;
    status: string;
    suggested_action: string;
    message: string;
    resolved_at?: string | null;
    order_id?: string | null;
    group_id?: string | null;
    staff_id?: string | null;
    forecast_start?: string | null;
    lateness_min?: number | null;
    suggested_staff_id?: string | null;
    proposed_slot?: { start: string; end: string } | null;
    applied?: boolean | null;
}

export interface PlanResponse {
    date: string;
    plan_version: number;
    as_of: string;
    routes: PlanRoute[];
    unassigned: UnassignedOrder[];
    pgp_groups: PgpGroup[];
    cancelled: CancelledOrder[];
    alerts: PlanAlert[];
    computation_ms: number;
    summary?: {
        orders_total: number;
        visits_planned: number;
        unassigned: number;
        pgp_orders: number;
        cancelled: number;
        travel_sec_total: number;
        utilization_cv: number;
    } | null;
}

export interface EventRequest {
    event_id: string;
    event_type: EventType;
    occurred_at: string;
    order_id?: string;
    staff_id?: string;
    target_staff_id?: string;
    payload?: Record<string, unknown>;
}

export interface EventView extends EventRequest {
    event_sequence: number;
    received_at: string;
}

export interface EventHistory {
    date: string;
    model_now: string;
    events: EventView[];
}

export interface ApiLoadResult {
    status: string;
    counts: Record<string, number>;
}
