import { Ban, Clock3, Share2 } from "lucide-react";
import { useState } from "react";
import type { CancelReason, TransferTarget } from "../../types/v1";

type OrderActionState = "assigned" | "unassigned" | "pgp";
export type VisitPhase = "none" | "planned" | "in_progress" | "done";

interface DispatchActionPanelProps {
    state: OrderActionState;
    /** Фаза визита относительно модельного времени (start < now — визит начат). */
    phase: VisitPhase;
    disabled: boolean;
    onTransfer: (target: TransferTarget) => void;
    onDelay: (delayMin: number) => void;
    onCancel: (reason: CancelReason) => void;
}

const TRANSFER_LABELS: Record<TransferTarget, string> = {
    STATION_EQUIPMENT: "Станционное оборудование",
    TRUNK_CABLE: "Магистральный кабель",
};
const CANCEL_LABELS: Record<CancelReason, string> = {
    NOT_CONFIRMED: "Абонент не подтвердил визит",
    CUSTOMER_REQUEST: "Отказ абонента",
};

export default function DispatchActionPanel({
    state, phase, disabled, onTransfer, onDelay, onCancel,
}: DispatchActionPanelProps) {
    const [target, setTarget] = useState<TransferTarget>("STATION_EQUIPMENT");
    const [reason, setReason] = useState<CancelReason>("NOT_CONFIRMED");
    const [delayText, setDelayText] = useState("15");
    const [confirmCancel, setConfirmCancel] = useState(false);

    const delayMin = Number(delayText);
    const validDelay = Number.isInteger(delayMin) && delayMin > 0;
    const inProgress = state === "assigned" && phase === "in_progress";
    const canCancel = state !== "assigned" || phase === "planned";

    return (
        <section className="v1-dispatch-actions">
            <div className="v1-dispatch-actions-heading">
                <strong>Оперативные действия</strong>
                <span>Событие → сервис перепланирует невыполненную часть</span>
            </div>

            {phase === "done" ? (
                <div className="v1-action-warning">
                    <strong>Визит уже завершён</strong>
                    <span>Выполненную часть плана менять нельзя (H10) — события по этому наряду не принимаются.</span>
                </div>
            ) : disabled ? (
                <div className="v1-action-warning">
                    <strong>Идёт обработка события</strong>
                    <span>Дождись ответа сервиса — события отправляются строго по очереди.</span>
                </div>
            ) : (
                <>
                    <div className="v1-dispatch-actions-grid">
                        <div className="v1-action-control">
                            <label className="v1-text-field">
                                Задержка на объекте, минут
                                <input type="number" min="1" step="1" value={delayText}
                                    disabled={!inProgress}
                                    onChange={(event) => setDelayText(event.target.value)} />
                            </label>
                            <button className="secondary-button" type="button"
                                disabled={!inProgress || !validDelay}
                                onClick={() => onDelay(delayMin)}>
                                <Clock3 size={14} /> VISIT_DELAYED
                            </button>
                        </div>

                        <div className="v1-action-control">
                            <label className="v1-text-field">
                                Повреждение вне зоны 3ЛТП
                                <select value={target} disabled={!inProgress}
                                    onChange={(event) => setTarget(event.target.value as TransferTarget)}>
                                    {Object.entries(TRANSFER_LABELS).map(([value, label]) =>
                                        <option key={value} value={value}>{label}</option>)}
                                </select>
                            </label>
                            <button className="primary-button" type="button" disabled={!inProgress}
                                onClick={() => onTransfer(target)}>
                                <Share2 size={14} /> Передать в профильное подразделение
                            </button>
                        </div>

                        <div className="v1-action-control v1-action-cancel">
                            <label className="v1-text-field">
                                Причина отмены
                                <select value={reason} disabled={!canCancel}
                                    onChange={(event) => setReason(event.target.value as CancelReason)}>
                                    {Object.entries(CANCEL_LABELS).map(([value, label]) =>
                                        <option key={value} value={value}>{label}</option>)}
                                </select>
                            </label>
                            {confirmCancel ? (
                                <div>
                                    <button className="danger-button" type="button"
                                        onClick={() => { setConfirmCancel(false); onCancel(reason); }}>
                                        <Ban size={14} /> Подтвердить отмену
                                    </button>
                                    <button className="secondary-button" type="button"
                                        onClick={() => setConfirmCancel(false)}>Не отменять</button>
                                </div>
                            ) : (
                                <button className="danger-button" type="button" disabled={!canCancel}
                                    onClick={() => setConfirmCancel(true)}>
                                    <Ban size={14} /> Отменить наряд
                                </button>
                            )}
                        </div>
                    </div>

                    <p className="v1-action-note">
                        {state === "assigned" && phase === "planned" &&
                            "Визит ещё не начат: доступна только отмена. Задержка и передача появляются, когда модельное время перейдёт за начало визита — сдвинь время на панели над картой. "}
                        {inProgress && "Визит начат: можно сообщить о задержке или передать наряд в профильное подразделение. Отмена начатого визита не принимается. "}
                        Переназначение на другого сотрудника сервис делает сам при риске опоздания (предупреждение LATE_RISK) — вручную оно в API не предусмотрено.
                    </p>
                </>
            )}
        </section>
    );
}
