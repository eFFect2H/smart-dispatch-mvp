export type Profile = "INSTALL" | "LTP3";
export type Technology = "PON" | "FTTB" | "XDSL" | "DOCSIS";
export type EventType =
    | "NEW_ORDER"
    | "ORDER_CANCELLED"
    | "VISIT_DELAYED"
    | "ORDER_TRANSFERRED"
    | "STAFF_UNAVAILABLE"
    | "TIME_TICK";

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
    deadline_value: number | null;
    repeat_deadline_value: number | null;
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
    explanation?: string | null;
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
    unavailable_from?: string | null;
    unavailable_until?: string | null;
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
    comment?: string | null;
}

export interface CancelledOrder {
    order_id: string;
    cancelled_at: string;
    event_id?: string | null;
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
    based_on_event_id?: string | null;
    as_of: string;
    routes: PlanRoute[];
    unassigned: UnassignedOrder[];
    pgp_groups: PgpGroup[];
    cancelled: CancelledOrder[];
    alerts: PlanAlert[];
    computation_ms?: number | null;
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

export type CancelReason = "NOT_CONFIRMED" | "CUSTOMER_REQUEST";
export type UnavailableReason = "VEHICLE_BREAKDOWN" | "ILLNESS" | "OTHER";
export type TransferTarget = "STATION_EQUIPMENT" | "TRUNK_CABLE";

interface EventBase {
    event_id: string;
    timestamp: string;
}
export interface NewOrderEvent extends EventBase { type: "NEW_ORDER"; order: Order }
export interface OrderCancelledEvent extends EventBase {
    type: "ORDER_CANCELLED"; order_id: string; reason: CancelReason;
}
export interface VisitDelayedEvent extends EventBase {
    type: "VISIT_DELAYED"; staff_id: string; order_id: string; expected_end: string;
}
export interface OrderTransferredEvent extends EventBase {
    type: "ORDER_TRANSFERRED"; staff_id: string; order_id: string;
    actual_end: string; transferred_to: TransferTarget;
}
export interface StaffUnavailableEvent extends EventBase {
    type: "STAFF_UNAVAILABLE"; staff_id: string; until?: string | null; reason: UnavailableReason;
}
export interface TimeTickEvent extends EventBase { type: "TIME_TICK" }

/** Событие в формате api_spec.yaml (`POST /days/{date}/events`). */
export type DispatchEvent =
    | NewOrderEvent
    | OrderCancelledEvent
    | VisitDelayedEvent
    | OrderTransferredEvent
    | StaffUnavailableEvent
    | TimeTickEvent;

export interface EventResult {
    event_id: string;
    accepted: boolean;
    duplicate: boolean;
    plan_version: number;
    as_of: string;
    processing_ms?: number;
    changed_staff_ids?: string[];
    new_alerts?: PlanAlert[];
    message?: string;
}

/** Запись локального журнала событий, отправленных из интерфейса. */
export interface EventLogEntry {
    event: DispatchEvent;
    result: EventResult | null;
    /** Время ответа сервиса, замеренное в браузере, мс (лимит спецификации — 10 000). */
    elapsed_ms: number;
    /** Заполнено, если сервис отклонил событие. */
    error?: string;
}

export interface ScenarioTrigger {
    trigger_id: string;
    type: "VISIT_DELAYED" | "ORDER_TRANSFERRED";
    order_id: string;
    fire_after_start_min: number;
    extra_duration_min?: number | null;
    transferred_to?: TransferTarget | null;
}

/** Содержимое `events_example.json`. */
export interface EventScenarioFile {
    date: string;
    now: string;
    events: DispatchEvent[];
    triggers: ScenarioTrigger[];
}

export interface ApiError {
    code: string;
    message: string;
    details?: { field?: string; message: string }[];
}

export interface HealthResponse {
    status: "ok";
    team?: string;
    version?: string;
}

export interface ApiLoadResult {
    status: string;
    counts: Record<string, number>;
}
