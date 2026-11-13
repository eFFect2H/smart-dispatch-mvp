import { AlertTriangle, CheckCircle2, Info, X } from "lucide-react";
import type { Order, PlanResponse, ReferenceData } from "../../types/v1";
import DispatchActionPanel from "./DispatchActionPanel";

interface SelectedOrderDetailsProps {
    orderId: string;
    order: Order | null;
    plan: PlanResponse;
    reference: ReferenceData | null;
    modelNow: string;
    actionsDisabled: boolean;
    onTransferOrder: (targetStaffId: string) => void;
    onDelayOrder: (delayMin: number) => void;
    onCancelOrder: () => void;
    onClose: () => void;
}

function formatTime(value?: string | null): string {
    if (!value) return "—";
    const match = value.match(/T(\d{2}):(\d{2})/);
    return match ? match[1] + ":" + match[2] : value.slice(0, 5);
}

function formatDateTime(value?: string | null): string {
    return value ? value.slice(8, 10) + "." + value.slice(5, 7) + " " + formatTime(value) : "—";
}

function formatDuration(seconds?: number | null): string {
    if (seconds == null) return "—";
    const minutes = Math.round(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const remainder = minutes % 60;
    return hours ? hours + " ч " + remainder + " мин" : remainder + " мин";
}

function formatDistance(meters?: number | null): string {
    if (meters == null) return "—";
    return meters >= 1000 ? (meters / 1000).toFixed(1) + " км" : meters + " м";
}

function equipmentText(value?: { ONT: number; ROUTER: number; STB: number } | null): string {
    return value ? `ONT ${value.ONT} · ROUTER ${value.ROUTER} · STB ${value.STB}` : "Нет данных";
}

function Fact({ label, value }: { label: string; value: string }) {
    return <div className="v1-order-fact"><span>{label}</span><strong>{value}</strong></div>;
}

function CheckLine({ label, value, passed }: { label: string; value: string; passed: boolean | null }) {
    return (
        <div className={"v1-explain-check" + (passed === null ? " info" : passed ? " pass" : " fail")}>
            {passed === null ? <Info size={14} /> : passed ? <CheckCircle2 size={14} /> : <AlertTriangle size={14} />}
            <span><strong>{label}</strong><small>{value}</small></span>
        </div>
    );
}

export default function SelectedOrderDetails({
    orderId,
    order,
    plan,
    reference,
    modelNow,
    actionsDisabled,
    onTransferOrder,
    onDelayOrder,
    onCancelOrder,
    onClose,
}: SelectedOrderDetailsProps) {
    const assignedRoute = plan.routes.find(route =>
        route.stops.some(stop => stop.type === "VISIT" && stop.order_id === orderId)
    );
    const stopIndex = assignedRoute?.stops.findIndex(stop => stop.type === "VISIT" && stop.order_id === orderId) ?? -1;
    const visit = stopIndex >= 0 ? assignedRoute?.stops[stopIndex] : undefined;
    const previousStop = stopIndex > 0 ? assignedRoute?.stops[stopIndex - 1] : undefined;
    const unassigned = plan.unassigned.find(item => item.order_id === orderId);
    const pgpGroup = plan.pgp_groups.find(group => group.order_ids.includes(orderId));
    const cancelled = plan.cancelled.find(item => item.order_id === orderId);
    const activeAlert = plan.alerts.find(alert => alert.order_id === orderId && alert.status === "ACTIVE");
    const staff = assignedRoute ? reference?.staff.find(item => item.staff_id === assignedRoute.staff_id) : undefined;
    const workType = order ? reference?.work_types.find(item => item.code === order.work_type_code) : undefined;

    const matchingProfiles = workType && staff
        ? staff.profiles.filter(profile => workType.profiles.includes(profile))
        : [];
    const profilePass = workType && staff ? matchingProfiles.length > 0 : null;
    const technologyPass = order && staff ? staff.technologies.includes(order.technology) : null;
    const heightPass = order && staff
        ? (!order.requires_height || staff.height_permit)
        : null;

    const equipmentBefore = previousStop?.equipment_after ?? (stopIndex === 0 ? staff?.equipment_start : undefined);
    const equipmentPass = order && equipmentBefore ? (
        equipmentBefore.ONT >= order.required_equipment.ONT &&
        equipmentBefore.ROUTER >= order.required_equipment.ROUTER &&
        equipmentBefore.STB >= order.required_equipment.STB
    ) : null;

    const state = assignedRoute && visit ? "Назначен" :
        unassigned ? "Не распределён" :
        pgpGroup ? "В группе ПГП" :
        cancelled ? "Отменён" : "Состояние не определено";
    const orderState = assignedRoute && visit ? "assigned" :
        unassigned ? "unassigned" : pgpGroup ? "pgp" : null;
    const modelNowMs = Date.parse(modelNow);
    const isFrozen = Boolean(assignedRoute && stopIndex > 0 && Number.isFinite(modelNowMs) &&
        assignedRoute.stops.slice(0, stopIndex).every(stop => Date.parse(stop.end) < modelNowMs));
    const transferStaff = order && workType && reference
        ? reference.staff.filter(member => {
            if (member.staff_id === assignedRoute?.staff_id) return false;
            const profileMatch = member.profiles.some(profile => workType.profiles.includes(profile));
            const technologyMatch = member.technologies.includes(order.technology);
            const heightMatch = !order.requires_height || member.height_permit;
            const route = plan.routes.find(candidate => candidate.staff_id === member.staff_id);
            const routeFinished = Boolean(route && route.stops.length > 0 &&
                route.stops[route.stops.length - 1].type === "DEPOT_END" &&
                route.stops.slice(0, -1).every(stop => Date.parse(stop.end) < modelNowMs));
            return profileMatch && technologyMatch && heightMatch &&
                route?.status !== "UNAVAILABLE" && !routeFinished;
        })
        : [];

    return (
        <section className="v1-card v1-order-details">
            <div className="v1-card-heading">
                <div>
                    <h2>Карточка наряда · {orderId}</h2>
                    <p>Статус, временные ограничения и основания назначения</p>
                </div>
                <div className="v1-order-heading-actions">
                    <span className={"v1-order-status " + (
                        assignedRoute ? "assigned" :
                        unassigned ? "unassigned" :
                        pgpGroup ? "pgp" :
                        cancelled ? "cancelled" : "unknown"
                    )}>{state}</span>
                    <button type="button" className="v1-panel-close" onClick={onClose} aria-label="Скрыть карточку">
                        <X size={16} />
                    </button>
                </div>
            </div>

            {!order && (
                <div className="v1-order-missing">
                    Данные наряда не найдены среди загруженных заказов. Состояние ниже взято из сохранённого плана.
                </div>
            )}

            {order && orderState && (
                <DispatchActionPanel
                    key={orderId}
                    state={orderState}
                    currentStaffId={assignedRoute?.staff_id}
                    targetStaff={transferStaff}
                    isFrozen={isFrozen}
                    disabled={actionsDisabled}
                    onTransfer={onTransferOrder}
                    onDelay={onDelayOrder}
                    onCancel={onCancelOrder}
                />
            )}

            {order && (
                <div className="v1-order-facts">
                    <Fact label="Адрес" value={order.address} />
                    <Fact label="Тип работ" value={(workType?.name ?? order.work_type_code) + " · " + order.work_type_code} />
                    <Fact label="Технология / узел" value={order.technology + " · " + order.access_node_id} />
                    <Fact label="Временное окно" value={formatDateTime(order.slot.start) + " — " + formatDateTime(order.slot.end)} />
                    <Fact label="Последнее допустимое начало" value={formatDateTime(order.latest_start)} />
                    <Fact label="Дедлайн завершения" value={formatDateTime(order.deadline)} />
                    <Fact label="Оборудование требуется" value={equipmentText(order.required_equipment)} />
                </div>
            )}

            {assignedRoute && visit && (
                <div className="v1-order-assignment">
                    <div className="v1-order-assignment-summary">
                        <strong>{staff?.display_name || assignedRoute.staff_id}</strong>
                        <span>{assignedRoute.staff_id} · {assignedRoute.service_center_id} · остановка #{visit.seq}</span>
                        <span>Визит: {formatTime(visit.start)}–{formatTime(visit.end)}</span>
                    </div>
                    <div className="v1-order-facts compact">
                        <Fact label="Прибытие" value={formatTime(visit.arrival)} />
                        <Fact label="Время в пути до визита" value={formatDuration(visit.travel_sec)} />
                        <Fact label="Расстояние до визита" value={formatDistance(visit.distance_m)} />
                        <Fact label="Предыдущая остановка" value={previousStop
                            ? previousStop.type + " · " + previousStop.location_id + " · " + formatTime(previousStop.start)
                            : "Начало маршрута"} />
                        <Fact label="Запас перед визитом" value={equipmentText(equipmentBefore)} />
                        <Fact label="Запас после визита" value={equipmentText(visit.equipment_after)} />
                    </div>

                    <div className="v1-explain-section">
                        <strong>Проверки квалификации и ресурсов</strong>
                        {reference && staff && order && workType ? (
                            <div className="v1-explain-checks">
                                <CheckLine
                                    label="Профиль работы"
                                    value={matchingProfiles.length
                                        ? "Совпадает: " + matchingProfiles.join(", ")
                                        : "Требуются: " + workType.profiles.join(", ")}
                                    passed={profilePass}
                                />
                                <CheckLine
                                    label="Технология"
                                    value={order.technology + (technologyPass ? " поддерживается" : " не поддерживается")}
                                    passed={technologyPass}
                                />
                                <CheckLine
                                    label="Допуск к высотным работам"
                                    value={!order.requires_height
                                        ? "Для наряда не требуется"
                                        : staff.height_permit ? "У сотрудника есть допуск" : "У сотрудника нет допуска"}
                                    passed={heightPass}
                                />
                                <CheckLine
                                    label="Оборудование на маршруте"
                                    value={equipmentBefore
                                        ? equipmentPass ? "Остатка перед визитом достаточно" : "Остаток перед визитом ниже потребности"
                                        : "В ответе API нет остатка до этой остановки"}
                                    passed={equipmentPass}
                                />
                            </div>
                        ) : (
                            <p className="v1-order-missing">Загрузи справочники, чтобы увидеть квалификацию сотрудника и проверку ресурсов.</p>
                        )}
                    </div>
                    <div className="v1-algorithm-note">
                        <strong>Как алгоритм выбирает сотрудника</strong>
                        <p>
                            Сначала отсеиваются сотрудники без нужного профиля, технологии или допуска. Для оставшихся проверяется
                            выполнимость маршрута с временным окном, длительностью, перерывом, сменой и пополнением оборудования.
                            Среди выполнимых вариантов минимизируется дополнительное время в пути; при равенстве — увеличение времени
                            до возврата в сервисный центр.
                        </p>
                        <small>API не возвращает оценки всех альтернативных сотрудников, поэтому это объяснение правила выбора, а не рейтинг кандидатов.</small>
                    </div>
                </div>
            )}

            {unassigned && (
                <div className="v1-order-state-detail warning">
                    <strong>{unassigned.reason_code}</strong>
                    <p>{unassigned.reason_text}</p>
                    <small>Рекомендуемое действие: {unassigned.suggested_action}</small>
                    {activeAlert && <small>{activeAlert.message}</small>}
                </div>
            )}
            {pgpGroup && (
                <div className="v1-order-state-detail info">
                    <strong>Группа {pgpGroup.group_id} · {pgpGroup.access_node_id}</strong>
                    <p>{pgpGroup.comment || pgpGroup.action}</p>
                    <small>В группе {pgpGroup.order_ids.length} нарядов · {pgpGroup.action}</small>
                </div>
            )}
            {cancelled && (
                <div className="v1-order-state-detail muted">
                    <strong>Наряд отменён событием</strong>
                    <p>{formatDateTime(cancelled.cancelled_at)}</p>
                    <small>Event ID: {cancelled.event_id}</small>
                </div>
            )}
        </section>
    );
}
