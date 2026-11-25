// Shared helpers for the bot.
import { PLANS } from "./license.js";

export async function tg(env, method, body) {
  const res = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!data.ok) console.error(method, data.description);
  return data;
}

export const send = (env, chat_id, text, extra = {}) =>
  tg(env, "sendMessage", { chat_id, text, parse_mode: "HTML", disable_web_page_preview: true, ...extra });

export const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export const fa = (n) => String(n).replace(/\d/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[d]);
export const toman = (n) => fa(Number(n).toLocaleString("en-US").replace(/,/g, "٬")) + " تومان";

/** Parses user-typed numbers: Persian digits, commas, spaces. */
export function parseNumber(text) {
  const latin = String(text || "").replace(/[۰-۹]/g, (c) => "۰۱۲۳۴۵۶۷۸۹".indexOf(c)).replace(/[٠-٩]/g, (c) => "٠١٢٣٤٥٦٧٨٩".indexOf(c));
  const digits = latin.replace(/[^\d]/g, "");
  return digits ? Number(digits) : NaN;
}

export const faDate = (sec) => {
  try {
    return new Date(sec * 1000).toLocaleDateString("fa-IR", { year: "numeric", month: "long", day: "numeric" });
  } catch {
    return new Date(sec * 1000).toISOString().slice(0, 10);
  }
};

export const userLink = (u) => {
  const name = esc(u.name || [u.first_name, u.last_name].filter(Boolean).join(" ") || "بی‌نام");
  return `<a href="tg://user?id=${u.id}">${name}</a>${u.username ? " @" + esc(u.username) : ""} — <code>${u.id}</code>`;
};

export const getJSON = async (env, key) => JSON.parse((await env.DB.get(key)) || "null");
export const putJSON = (env, key, value) => env.DB.put(key, JSON.stringify(value));

export async function adminGroup(env) {
  return (await env.DB.get("config:group")) || null;
}

export async function isAdmin(env, userId) {
  const owner = await env.DB.get("config:owner");
  return owner != null && String(owner) === String(userId);
}

/** Current plans: defaults merged with the admin's edits. */
export async function getPlans(env) {
  const saved = await getJSON(env, "config:plans");
  return saved || structuredClone(PLANS);
}

export const savePlans = (env, plans) => putJSON(env, "config:plans", plans);

export async function cardInfo(env) {
  const saved = await getJSON(env, "config:card");
  return { number: saved?.number || env.CARD_NUMBER || "", holder: saved?.holder || env.CARD_HOLDER || "" };
}

/** Reads every key under a prefix (KV pages are 1000 keys). */
export async function listAll(env, prefix) {
  const keys = [];
  let cursor;
  do {
    const page = await env.DB.list({ prefix, cursor });
    keys.push(...page.keys.map((k) => k.name));
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return keys;
}

export async function readAll(env, prefix) {
  const keys = await listAll(env, prefix);
  const values = await Promise.all(keys.map((k) => getJSON(env, k)));
  return values.filter(Boolean);
}

/** All customers (everyone who used the bot, except the owner). */
export async function customers(env) {
  const owner = await env.DB.get("config:owner");
  return (await readAll(env, "user:")).filter((u) => String(u.id) !== String(owner));
}
