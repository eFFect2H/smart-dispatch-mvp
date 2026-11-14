import type { Profile, WorkType } from "../types/v1";

/** RFC 4180: запятая-разделитель, кавычки, экранирование `""`, CRLF/LF. */
export function parseCsv(text: string): string[][] {
    const rows: string[][] = [];
    let row: string[] = [];
    let cell = "";
    let quoted = false;
    const source = text.replace(/^﻿/, "");
    for (let i = 0; i < source.length; i += 1) {
        const char = source[i];
        if (quoted) {
            if (char === '"' && source[i + 1] === '"') { cell += '"'; i += 1; }
            else if (char === '"') quoted = false;
            else cell += char;
        } else if (char === '"' && cell.length === 0) quoted = true;
        else if (char === ",") { row.push(cell); cell = ""; }
        else if (char === "\n") {
            row.push(cell.replace(/\r$/, ""));
            if (row.some((value) => value.length > 0)) rows.push(row);
            row = []; cell = "";
        } else cell += char;
    }
    if (quoted) throw new Error("work_types.csv: незакрытое поле в кавычках");
    if (cell.length || row.length) {
        row.push(cell.replace(/\r$/, ""));
        if (row.some((value) => value.length > 0)) rows.push(row);
    }
    return rows;
}

const REQUIRED = ["code", "name", "profiles", "duration_min", "max_start_delay_min",
    "deadline_kind", "deadline_value", "repeat_deadline_value", "pgp_relevant"];
const PROFILES: Profile[] = ["INSTALL", "LTP3"];
const DEADLINE_KINDS = ["SLOT", "HOURS", "WORKING_DAYS"];

/** Пустая ячейка и строка `null` (регистр не важен) — это null. */
function isNull(value: string): boolean {
    return value === "" || value.toLowerCase() === "null";
}

export function parseWorkTypes(text: string): WorkType[] {
    const rows = parseCsv(text);
    if (rows.length < 2) throw new Error("work_types.csv: нет строк типов работ");
    const headers = rows[0].map((value) => value.trim());
    const missing = REQUIRED.filter((name) => !headers.includes(name));
    if (missing.length) throw new Error("work_types.csv: отсутствуют столбцы " + missing.join(", "));

    return rows.slice(1).map((row, index) => {
        const line = "work_types.csv, строка " + (index + 2) + ": ";
        const get = (name: string) => (row[headers.indexOf(name)] ?? "").trim();
        const nullableInt = (name: string): number | null => {
            const raw = get(name);
            if (isNull(raw)) return null;
            const number = Number(raw);
            if (!Number.isInteger(number)) throw new Error(line + name + " должен быть целым числом или null");
            return number;
        };
        const requiredInt = (name: string): number => {
            const value = nullableInt(name);
            if (value === null) throw new Error(line + name + " обязателен");
            return value;
        };
        const profiles = get("profiles").split("|").map((value) => value.trim()).filter(Boolean);
        if (!profiles.length) throw new Error(line + "profiles пуст");
        const unknown = profiles.filter((value) => !PROFILES.includes(value as Profile));
        if (unknown.length) throw new Error(line + "неизвестный профиль " + unknown.join(", "));
        const kind = get("deadline_kind");
        if (!DEADLINE_KINDS.includes(kind)) throw new Error(line + "неизвестный deadline_kind " + kind);
        const pgp = get("pgp_relevant").toLowerCase();
        if (pgp !== "true" && pgp !== "false") throw new Error(line + "pgp_relevant должен быть true/false");
        return {
            code: get("code"),
            name: get("name"),
            profiles: profiles as Profile[],
            duration_min: requiredInt("duration_min"),
            max_start_delay_min: nullableInt("max_start_delay_min"),
            deadline_kind: kind as WorkType["deadline_kind"],
            deadline_value: nullableInt("deadline_value"),
            repeat_deadline_value: nullableInt("repeat_deadline_value"),
            pgp_relevant: pgp === "true",
        };
    });
}
