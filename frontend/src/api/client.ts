import axios from "axios";

/** Лимиты из api_spec.yaml: справочники/день — до 60 с, план — до 300 с, событие — до 10 с. */
export const TIMEOUTS_MS = {
    default: 120_000,
    plan: 330_000,
    geometry: 270_000,
    event: 30_000,
    health: 5_000,
} as const;

export const api = axios.create({
    baseURL: import.meta.env.VITE_API_URL || undefined,
    timeout: TIMEOUTS_MS.default,
    maxBodyLength: Infinity,
    maxContentLength: Infinity,
    headers: {
        "Content-Type": "application/json",
    },
});
