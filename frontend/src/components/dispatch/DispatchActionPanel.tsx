import { ArrowLeftRight, Ban, Clock3 } from "lucide-react";
import { useState } from "react";
import type { StaffMember } from "../../types/v1";

type OrderActionState = "assigned" | "unassigned" | "pgp";

interface DispatchActionPanelProps {
    state: OrderActionState;
    currentStaffId?: string;
    targetStaff: StaffMember[];
    isFrozen: boolean;
    disabled: boolean;
    onTransfer: (targetStaffId: string) => void;
    onDelay: (delayMin: number) => void;
    onCancel: () => void;
}

export default function DispatchActionPanel({
    state,
    currentStaffId,
    targetStaff,
    isFrozen,
    disabled,
    onTransfer,
    onDelay,
    onCancel,
}: DispatchActionPanelProps) {
    const [targetStaffId, setTargetStaffId] = useState("");
    const [delayText, setDelayText] = useState("15");
    const [confirmCancel, setConfirmCancel] = useState(false);

    const selectedTarget = targetStaff.find(member => member.staff_id === targetStaffId)
        ?? targetStaff[0];
    const delayMin = Number(delayText);
    const validDelay = Number.isInteger(delayMin) && delayMin > 0;
    const transferLabel = state === "assigned"
        ? "Переназначить"
        : state === "pgp" ? "Закрепить за сотрудником" : "Назначить сотрудника";

    return (
        <section className="v1-dispatch-actions">
            <div className="v1-dispatch-actions-heading">
                <strong>Оперативные действия</strong>
                <span>Событие → повторное построение плана</span>
            </div>

            {isFrozen ? (
                <div className="v1-action-warning">
                    <strong>Остановка заморожена по H10</strong>
                    <span>Предыдущая остановка уже завершилась до текущего модельного времени. Назначение, задержка и отмена этой остановки не изменят маршрут.</span>
                </div>
            ) : disabled ? (
                <div className="v1-action-warning">
                    <strong>Сначала примени ожидающие события</strong>
                    <span>В журнале есть события, ещё не отражённые в сохранённом плане. Выполни перепланирование в верхней панели.</span>
                </div>
            ) : (
                <>
                    <div className="v1-dispatch-actions-grid">
                        {targetStaff.length > 0 ? (
                            <div className="v1-action-control">
                                <label className="v1-text-field">
                                    Целевой сотрудник
                                    <select value={selectedTarget?.staff_id ?? ""} onChange={event => setTargetStaffId(event.target.value)}>
                                        {targetStaff.map(member => (
                                            <option key={member.staff_id} value={member.staff_id}>
                                                {(member.display_name || member.staff_id) + " · " + member.staff_id}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                <button
                                    className="primary-button"
                                    type="button"
                                    disabled={!selectedTarget}
                                    onClick={() => selectedTarget && onTransfer(selectedTarget.staff_id)}
                                >
                                    <ArrowLeftRight size={14} />
                                    {transferLabel}
                                </button>
                            </div>
                        ) : (
                            <div className="v1-actions-empty">
                                Нет другого доступного сотрудника, который предварительно подходит по профилю, технологии и допуску.
                            </div>
                        )}

                        <div className="v1-action-control">
                            <label className="v1-text-field">
                                Сдвиг временного окна, минут
                                <input type="number" min="1" step="1" value={delayText} onChange={event => setDelayText(event.target.value)} />
                            </label>
                            <button
                                className="secondary-button"
                                type="button"
                                disabled={!validDelay}
                                onClick={() => onDelay(delayMin)}
                            >
                                <Clock3 size={14} />
                                Применить задержку
                            </button>
                        </div>

                        <div className="v1-action-control v1-action-cancel">
                            {confirmCancel ? (
                                <>
                                    <span>Отменить наряд {currentStaffId ? "у назначенного сотрудника" : ""}?</span>
                                    <div>
                                        <button className="danger-button" type="button" onClick={onCancel}>
                                            <Ban size={14} /> Подтвердить отмену
                                        </button>
                                        <button className="secondary-button" type="button" onClick={() => setConfirmCancel(false)}>
                                            Не отменять
                                        </button>
                                    </div>
                                </>
                            ) : (
                                <button className="danger-button" type="button" onClick={() => setConfirmCancel(true)}>
                                    <Ban size={14} /> Отменить наряд
                                </button>
                            )}
                        </div>
                    </div>

                    <p className="v1-action-note">
                        В список переназначения попадают сотрудники с подходящими профилем, технологией и допуском.
                        Окончательную выполнимость с учётом смены, временных окон, перерыва и оборудования проверяет backend.
                        Если ограничения маршрута не выполняются, наряд останется нераспределённым. Ручное закрепление наряда из ПГП исключает его из автоматической группировки.
                    </p>
                </>
            )}
        </section>
    );
}
