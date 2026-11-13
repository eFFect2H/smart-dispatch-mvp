import type { PlanResponse } from "../types/v1";

export type PlanItemKind = "ASSIGNED" | "UNASSIGNED" | "PGP" | "CANCELLED";

export interface PlanItemState {
    kind: PlanItemKind;
    staffId?: string;
    start?: string;
    end?: string;
    reasonCode?: string;
    reasonText?: string;
    groupId?: string;
}

export interface PlanItemChange {
    orderId: string;
    title: string;
    description: string;
}

export interface PlanMetricSnapshot {
    visits: number;
    unassigned: number;
    pgpOrders: number;
    cancelled: number;
    travelSec: number;
    activeLateRisks: number;
}

export interface PlanComparison {
    before: PlanResponse;
    after: PlanResponse;
    trigger: string;
    changes: PlanItemChange[];
    beforeMetrics: PlanMetricSnapshot;
    afterMetrics: PlanMetricSnapshot;
}

function collectOrderStates(plan: PlanResponse): Map<string, PlanItemState> {
    const states = new Map<string, PlanItemState>();

    for (const route of plan.routes) {
        for (const stop of route.stops) {
            if (stop.type !== "VISIT" || !stop.order_id) continue;
            states.set(stop.order_id, {
                kind: "ASSIGNED",
                staffId: route.staff_id,
                start: stop.start,
                end: stop.end,
            });
        }
    }

    for (const item of plan.unassigned) {
        states.set(item.order_id, {
            kind: "UNASSIGNED",
            reasonCode: item.reason_code,
            reasonText: item.reason_text,
        });
    }

    for (const group of plan.pgp_groups) {
        for (const orderId of group.order_ids) {
            states.set(orderId, { kind: "PGP", groupId: group.group_id });
        }
    }

    for (const item of plan.cancelled) {
        states.set(item.order_id, { kind: "CANCELLED", reasonText: item.event_id });
    }

    return states;
}

function stateLabel(state: PlanItemState): string {
    switch (state.kind) {
        case "ASSIGNED": return "Назначен";
        case "UNASSIGNED": return "Не распределён";
        case "PGP": return "В группе ПГП";
        case "CANCELLED": return "Отменён";
    }
}

function describeState(state: PlanItemState): string {
    if (state.kind === "ASSIGNED") {
        return [state.staffId, state.start ? state.start.slice(11, 16) : null]
            .filter(Boolean).join(" · ");
    }
    if (state.kind === "UNASSIGNED") {
        return [state.reasonCode, state.reasonText].filter(Boolean).join(" — ");
    }
    if (state.kind === "PGP") return state.groupId ? "Группа " + state.groupId : "ПГП";
    return state.reasonText ? "Событие " + state.reasonText : "Отменён";
}

export function summarizePlan(plan: PlanResponse): PlanMetricSnapshot {
    return {
        visits: plan.summary?.visits_planned ??
            plan.routes.reduce((sum, route) => sum + route.stops.filter(stop => stop.type === "VISIT").length, 0),
        unassigned: plan.unassigned.length,
        pgpOrders: plan.summary?.pgp_orders ??
            plan.pgp_groups.reduce((sum, group) => sum + group.order_ids.length, 0),
        cancelled: plan.cancelled.length,
        travelSec: plan.summary?.travel_sec_total ??
            plan.routes.reduce((sum, route) => sum + (route.totals?.travel_sec ?? 0), 0),
        activeLateRisks: plan.alerts.filter(alert => alert.type === "LATE_RISK" && alert.status === "ACTIVE").length,
    };
}

export function comparePlanVersions(
    before: PlanResponse | null,
    after: PlanResponse,
    trigger: string,
): PlanComparison | null {
    if (!before || before.date !== after.date || before.plan_version === after.plan_version) return null;

    const oldStates = collectOrderStates(before);
    const newStates = collectOrderStates(after);
    const ids = new Set([...oldStates.keys(), ...newStates.keys()]);
    const changes: PlanItemChange[] = [];

    for (const orderId of ids) {
        const oldState = oldStates.get(orderId);
        const newState = newStates.get(orderId);

        if (!oldState && newState) {
            changes.push({
                orderId,
                title: "Наряд появился в плане",
                description: stateLabel(newState) + (describeState(newState) ? " · " + describeState(newState) : ""),
            });
            continue;
        }
        if (oldState && !newState) {
            changes.push({ orderId, title: "Наряд исчез из результата", description: stateLabel(oldState) });
            continue;
        }
        if (!oldState || !newState) continue;

        if (oldState.kind !== newState.kind) {
            changes.push({
                orderId,
                title: stateLabel(oldState) + " → " + stateLabel(newState),
                description: describeState(newState) || "Изменилось состояние наряда",
            });
            continue;
        }

        if (oldState.kind === "ASSIGNED" && newState.kind === "ASSIGNED") {
            if (oldState.staffId !== newState.staffId) {
                changes.push({
                    orderId,
                    title: "Изменён исполнитель",
                    description: (oldState.staffId ?? "—") + " → " + (newState.staffId ?? "—"),
                });
            } else if (oldState.start !== newState.start || oldState.end !== newState.end) {
                changes.push({
                    orderId,
                    title: "Изменено время визита",
                    description: (oldState.start?.slice(11, 16) ?? "—") + "–" +
                        (oldState.end?.slice(11, 16) ?? "—") + " → " +
                        (newState.start?.slice(11, 16) ?? "—") + "–" +
                        (newState.end?.slice(11, 16) ?? "—"),
                });
            }
        } else if (oldState.kind === "UNASSIGNED" && newState.kind === "UNASSIGNED" &&
            (oldState.reasonCode !== newState.reasonCode || oldState.reasonText !== newState.reasonText)) {
            changes.push({
                orderId,
                title: "Изменилась причина неназначения",
                description: newState.reasonCode + " — " + (newState.reasonText ?? ""),
            });
        } else if (oldState.kind === "PGP" && newState.kind === "PGP" && oldState.groupId !== newState.groupId) {
            changes.push({
                orderId,
                title: "Изменилась группа ПГП",
                description: (oldState.groupId ?? "—") + " → " + (newState.groupId ?? "—"),
            });
        }
    }

    changes.sort((a, b) => a.orderId.localeCompare(b.orderId));

    return {
        before,
        after,
        trigger,
        changes,
        beforeMetrics: summarizePlan(before),
        afterMetrics: summarizePlan(after),
    };
}
