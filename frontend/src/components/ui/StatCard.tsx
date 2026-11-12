import type { LucideIcon } from "lucide-react";

interface StatCardProps {
    title: string;
    value: number | string;
    description: string;
    icon: LucideIcon;
}

export default function StatCard({
                                     title,
                                     value,
                                     description,
                                     icon: Icon,
                                 }: StatCardProps) {
    return (
        <section className="stat-card">
            <div className="stat-card-top">
                <span className="stat-title">{title}</span>
                <div className="stat-icon">
                    <Icon size={20} />
                </div>
            </div>

            <div className="stat-value">{value}</div>
            <div className="stat-description">{description}</div>
        </section>
    );
}