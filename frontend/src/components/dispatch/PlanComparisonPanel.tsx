import { ArrowDownRight, ArrowUpRight, GitCompare, X } from "lucide-react";
import type { PlanComparison } from "../../utils/planInsights";

interface PlanComparisonPanelProps {
    comparison: PlanComparison;
    onClose: () => void;
}

function formatDuration(seconds: number): string {
    const minutes = Math.round(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const remaining = minutes % 60;
    return hours ? hours + " ч " + remaining + " мин" : remaining + " мин";
}

function signedDelta(before: number, after: number, duration = false): string {
    const delta = after - before;
    if (delta === 0) return "Без изменений";
    const prefix = delta > 0 ? "+" : "−";
    return prefix + (duration ? formatDuration(Math.abs(delta)) : String(Math.abs(delta)));
}

function Metric({
    label,
    before,
    after,
    duration = false,
}: {
    label: string;
    before: number;
    after: number;
    duration?: boolean;
}) {
    const delta = after - before;
    const deltaText = signedDelta(before, after, duration);
    return (
        <div className="v1-diff-metric">
            <span>{label}</span>
            <strong>{duration ? formatDuration(before) : before} <i>→</i> {duration ? formatDuration(after) : after}</strong>
            <small className={delta === 0 ? "neutral" : delta > 0 ? "up" : "down"}>
                {delta > 0 ? <ArrowUpRight size={12} /> : delta < 0 ? <ArrowDownRight size={12} /> : null}
                {deltaText}
            </small>
        </div>
    );
}


export default function PlanComparisonPanel({ comparison, onClose }: PlanComparisonPanelProps) {
    const before = comparison.beforeMetrics;
    const after = comparison.afterMetrics;
    const shownChanges = comparison.changes.slice(0, 12);

    return (
        <section className="v1-card v1-diff-card">
            <div className="v1-card-heading">
                <div>
                    <h2><GitCompare size={17} /> Изменения после перепланирования</h2>
                    <p>
                        Версия v{comparison.before.plan_version} → v{comparison.after.plan_version}
                        {" · "}{comparison.trigger}
                    </p>
                </div>
                <button type="button" className="v1-panel-close" onClick={onClose} aria-label="Скрыть сравнение">
                    <X size={16} />
                </button>
            </div>

            <div className="v1-diff-metric-grid">
                <Metric label="Запланировано визитов" before={before.visits} after={after.visits} />
                <Metric label="Нераспределённые" before={before.unassigned} after={after.unassigned} />
                <Metric label="Наряды в ПГП" before={before.pgpOrders} after={after.pgpOrders} />
                <Metric label="Отменённые" before={before.cancelled} after={after.cancelled} />
                <Metric label="Время в пути" before={before.travelSec} after={after.travelSec} duration />
                <Metric label="Активные LATE_RISK" before={before.activeLateRisks} after={after.activeLateRisks} />
            </div>

            <div className="v1-diff-change-section">
                <div className="v1-diff-change-heading">
                    <strong>Изменения по нарядам</strong>
                    <span>{comparison.changes.length} изменений</span>
                </div>
                {shownChanges.length ? (
                    <div className="v1-diff-change-list">
                        {shownChanges.map((change) => (
                            <div className="v1-diff-change-row" key={change.orderId}>
                                <code>{change.orderId}</code>
                                <div><strong>{change.title}</strong><small>{change.description}</small></div>
                            </div>
                        ))}
                        {comparison.changes.length > shownChanges.length && (
                            <p className="v1-diff-overflow">
                                И ещё {comparison.changes.length - shownChanges.length} изменений.
                            </p>
                        )}
                    </div>
                ) : (
                    <p className="v1-diff-empty">Состав назначений и время визитов между версиями не изменились.</p>
                )}
            </div>

            <p className="v1-diff-note">
                Сравнение строится по двум версиям, находившимся в памяти страницы. API пока возвращает только последний сохранённый план;
                после перезагрузки браузера историческое сравнение недоступно.
            </p>
        </section>
    );
}
