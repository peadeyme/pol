// Admin panel: only for the owner (whoever ran /setgroup in the admin group).
import { DEVICE_RE, issueLicense, normalizeDevice } from "./license.js";
import {
  cardInfo, customers, esc, fa, faDate, getJSON, getPlans, parseNumber, putJSON, readAll, savePlans, send, tg, toman, userLink,
} from "./util.js";

const PAGE = 8;
const back = (to = "a:home", text = "⬅️ برگشت") => [{ text, callback_data: to }];

function homeKeyboard() {
  return {
    inline_keyboard: [
      [{ text: "📊 آمار", callback_data: "a:stats" }, { text: "👥 کاربران", callback_data: "a:users:0" }],
      [{ text: "💳 پلن‌ها و قیمت‌ها", callback_data: "a:plans" }, { text: "🔎 جستجوی کاربر", callback_data: "a:find" }],
      [{ text: "🎁 ساخت لایسنس دستی", callback_data: "a:gift" }, { text: "📣 پیام همگانی", callback_data: "a:bc" }],
      [{ text: "🏦 شماره کارت", callback_data: "a:card" }, { text: "🛒 دیدن ربات مثل مشتری", callback_data: "a:buy" }],
    ],
  };
}

async function setStep(env, userId, step, extra = {}) {
  const key = `user:${userId}`;
  const state = (await getJSON(env, key)) || { id: userId };
  Object.assign(state, { step }, extra);
  await putJSON(env, key, state);
  return state;
}

export async function adminHome(env, chatId) {
  await setStep(env, chatId, null);
  return send(env, chatId, "🛠 <b>پنل مدیریت پل</b>\nیک بخش رو انتخاب کن:", { reply_markup: homeKeyboard() });
}

// ---------- callbacks ----------

export async function onAdminCallback(env, q, data, showCustomerView) {
  const id = q.from.id;
  await tg(env, "answerCallbackQuery", { callback_query_id: q.id });
  const [, action, a1, a2] = data.split(":");

  switch (action) {
    case "home": return adminHome(env, id);
    case "stats": return stats(env, id);
    case "users": return usersPage(env, id, Number(a1) || 0);
    case "u": return userDetail(env, id, a1);
    case "find":
      await setStep(env, id, "adm:find");
      return send(env, id, "آیدی عددی یا یوزرنیم کاربر (مثل <code>@sara</code>) یا کد دستگاه رو بفرست:", { reply_markup: { inline_keyboard: [back()] } });
    case "plans": return plansMenu(env, id);
    case "p": return planDetail(env, id, a1);
    case "pe": {
      const labels = { price: "قیمت جدید (تومان)", days: "مدت جدید (روز)", title: "عنوان جدید" };
      await setStep(env, id, `adm:pe:${a1}:${a2}`);
      return send(env, id, `${labels[a2]} رو بفرست:`, { reply_markup: { inline_keyboard: [back(`a:p:${a1}`, "لغو")] } });
    }
    case "pt": {
      const plans = await getPlans(env);
      if (plans[a1]) plans[a1].active = !plans[a1].active;
      await savePlans(env, plans);
      return planDetail(env, id, a1);
    }
    case "pd": {
      const plans = await getPlans(env);
      if (Object.keys(plans).length <= 1) return send(env, id, "حداقل یک پلن باید بمونه.");
      delete plans[a1];
      await savePlans(env, plans);
      return plansMenu(env, id);
    }
    case "pn":
      await setStep(env, id, "adm:pn:title", { draft: {} });
      return send(env, id, "<b>پلن جدید</b>\nعنوان پلن رو بفرست (مثلاً «سه‌ماهه»):", { reply_markup: { inline_keyboard: [back("a:plans", "لغو")] } });
    case "gift":
      await setStep(env, id, "adm:gift:dev", { giftUser: a1 || null });
      return send(env, id, "🎁 کد دستگاه مشتری رو بفرست (مثل <code>7F3A2-9C41B</code>):", { reply_markup: { inline_keyboard: [back()] } });
    case "gp": return giftIssue(env, id, a1);
    case "msg":
      await setStep(env, id, `adm:msg:${a1}`);
      return send(env, id, "متن پیام برای این کاربر رو بفرست:", { reply_markup: { inline_keyboard: [back(`a:u:${a1}`, "لغو")] } });
    case "bc":
      await setStep(env, id, "adm:bc");
      return send(env, id, "📣 متن پیام همگانی رو بفرست. قبل از ارسال یک پیش‌نمایش نشونت می‌دم.", { reply_markup: { inline_keyboard: [back()] } });
    case "bcgo": return broadcast(env, id);
    case "card": {
      const card = await cardInfo(env);
      await setStep(env, id, "adm:card:num");
      return send(env, id,
        `🏦 کارت فعلی:\n<code>${esc(card.number)}</code>\nبه نام: ${esc(card.holder)}\n\nشماره کارت جدید رو بفرست:`,
        { reply_markup: { inline_keyboard: [back()] } });
    }
    case "buy":
      await setStep(env, id, null);
      return showCustomerView(id);
  }
}

// ---------- text input while in an admin step ----------

export async function onAdminText(env, msg, state) {
  const id = msg.from.id;
  const text = (msg.text || "").trim();
  const step = state.step || "";

  if (step === "adm:find") return findUser(env, id, text);

  if (step.startsWith("adm:pe:")) {
    const [, , pid, field] = step.split(":");
    const plans = await getPlans(env);
    if (!plans[pid]) return adminHome(env, id);
    if (field === "title") {
      if (!text) return send(env, id, "عنوان خالیه، دوباره بفرست.");
      plans[pid].title = text.slice(0, 40);
    } else {
      const n = parseNumber(text);
      if (!Number.isFinite(n) || n <= 0) return send(env, id, "یک عدد درست بفرست.");
      plans[pid][field] = n;
    }
    await savePlans(env, plans);
    await setStep(env, id, null);
    return planDetail(env, id, pid);
  }

  if (step.startsWith("adm:pn:")) {
    const part = step.split(":")[2];
    const draft = state.draft || {};
    if (part === "title") {
      if (!text) return send(env, id, "عنوان خالیه.");
      draft.title = text.slice(0, 40);
      await setStep(env, id, "adm:pn:price", { draft });
      return send(env, id, "قیمت (تومان) رو بفرست:");
    }
    if (part === "price") {
      const n = parseNumber(text);
      if (!(n > 0)) return send(env, id, "یک عدد درست بفرست.");
      draft.price = n;
      await setStep(env, id, "adm:pn:days", { draft });
      return send(env, id, "مدت (روز) رو بفرست، مثلاً <code>92</code>:");
    }
    if (part === "days") {
      const n = parseNumber(text);
      if (!(n > 0)) return send(env, id, "یک عدد درست بفرست.");
      const plans = await getPlans(env);
      const pid = "p" + Date.now().toString(36).slice(-5);
      plans[pid] = { title: draft.title, price: draft.price, days: n, active: true };
      await savePlans(env, plans);
      await setStep(env, id, null, { draft: null });
      return planDetail(env, id, pid);
    }
  }

  if (step === "adm:gift:dev") {
    const device = normalizeDevice(text);
    if (!DEVICE_RE.test(device)) return send(env, id, "کد دستگاه درست نیست. مثل <code>7F3A2-9C41B</code> بفرست.");
    await setStep(env, id, "adm:gift:plan", { giftDevice: device });
    const plans = await getPlans(env);
    return send(env, id, `کد دستگاه: <code>${device}</code>\nکدوم پلن؟`, {
      reply_markup: {
        inline_keyboard: [
          ...Object.entries(plans).map(([pid, p]) => [{ text: `${p.title} — ${fa(p.days)} روز`, callback_data: `a:gp:${pid}` }]),
          back(),
        ],
      },
    });
  }

  if (step.startsWith("adm:msg:")) {
    const target = step.split(":")[2];
    if (!text) return send(env, id, "متن خالیه.");
    const r = await send(env, target, `📩 <b>پیام از پشتیبانی پل</b>\n\n${esc(text)}`);
    await setStep(env, id, null);
    return send(env, id, r.ok ? "✅ ارسال شد." : "❌ ارسال نشد (احتمالاً کاربر ربات رو بلاک کرده).", {
      reply_markup: { inline_keyboard: [back(`a:u:${target}`, "⬅️ برگشت به کاربر")] },
    });
  }

  if (step === "adm:bc") {
    if (!text) return send(env, id, "متن خالیه.");
    await setStep(env, id, "adm:bc:ready", { broadcastText: text });
    const users = await customers(env);
    return send(env, id, `<b>پیش‌نمایش</b> — برای ${fa(users.length)} کاربر:\n\n${esc(text)}`, {
      reply_markup: { inline_keyboard: [[{ text: "✅ ارسال برای همه", callback_data: "a:bcgo" }], back("a:home", "لغو")] },
    });
  }

  if (step === "adm:card:num") {
    const digits = parseNumber(text);
    if (!Number.isFinite(digits) || String(digits).length < 16) return send(env, id, "شماره کارت ۱۶ رقمی رو بفرست.");
    const num = String(text).replace(/[۰-۹]/g, (c) => "۰۱۲۳۴۵۶۷۸۹".indexOf(c)).replace(/[^\d]/g, "").replace(/(\d{4})(?=\d)/g, "$1-");
    await setStep(env, id, "adm:card:holder", { cardDraft: num });
    return send(env, id, "اسم صاحب کارت رو بفرست:");
  }

  if (step === "adm:card:holder") {
    if (!text) return send(env, id, "اسم خالیه.");
    await putJSON(env, "config:card", { number: state.cardDraft, holder: text.slice(0, 60) });
    await setStep(env, id, null, { cardDraft: null });
    return send(env, id, `✅ کارت عوض شد:\n<code>${esc(state.cardDraft)}</code>\nبه نام: ${esc(text)}`, { reply_markup: { inline_keyboard: [back()] } });
  }

  return adminHome(env, id);
}

// ---------- sections ----------

async function stats(env, id) {
  const now = Date.now();
  const [users, orders, lics] = await Promise.all([customers(env), readAll(env, "order:"), readAll(env, "lic:")]);
  const plans = await getPlans(env);

  const day = 86400000;
  const approved = orders.filter((o) => o.status === "approved" && o.amount > 0);
  const sum = (list) => list.reduce((t, o) => t + (o.amount || 0), 0);
  const monthStart = now - 30 * day;
  const byPlan = {};
  for (const o of approved) byPlan[o.plan] = (byPlan[o.plan] || 0) + 1;

  const text =
    "📊 <b>آمار</b>\n\n" +
    `👥 کاربران: <b>${fa(users.length)}</b>\n` +
    `   امروز: ${fa(users.filter((u) => now - (u.joined || 0) < day).length)} · ۷ روز: ${fa(users.filter((u) => now - (u.joined || 0) < 7 * day).length)}\n\n` +
    `🔑 اشتراک فعال: <b>${fa(lics.filter((l) => l.expires * 1000 > now).length)}</b>\n\n` +
    `🧾 سفارش‌ها:\n` +
    `   در انتظار بررسی: ${fa(orders.filter((o) => o.status === "pending").length)}\n` +
    `   تأیید شده: ${fa(approved.length)}\n` +
    `   رد شده: ${fa(orders.filter((o) => o.status === "rejected").length)}\n\n` +
    `💰 درآمد کل: <b>${toman(sum(approved))}</b>\n` +
    `   ۳۰ روز اخیر: ${toman(sum(approved.filter((o) => (o.approvedAt || o.created) > monthStart)))}\n\n` +
    "فروش هر پلن:\n" +
    (Object.entries(byPlan).map(([pid, n]) => `   ${esc(plans[pid]?.title || o_title(orders, pid))}: ${fa(n)}`).join("\n") || "   هنوز فروشی نیست");

  return send(env, id, text, { reply_markup: { inline_keyboard: [back()] } });
}

const o_title = (orders, pid) => orders.find((o) => o.plan === pid && o.title)?.title || pid;

async function usersPage(env, id, page) {
  const now = Date.now() / 1000;
  const [users, lics] = await Promise.all([customers(env), readAll(env, "lic:")]);
  const active = new Set(lics.filter((l) => l.expires > now).map((l) => String(l.userId)));
  users.sort((a, b) => (b.joined || 0) - (a.joined || 0));
  const pages = Math.max(1, Math.ceil(users.length / PAGE));
  page = Math.min(Math.max(0, page), pages - 1);
  const slice = users.slice(page * PAGE, page * PAGE + PAGE);

  const nav = [];
  if (page > 0) nav.push({ text: "◀️ قبلی", callback_data: `a:users:${page - 1}` });
  if (page < pages - 1) nav.push({ text: "بعدی ▶️", callback_data: `a:users:${page + 1}` });

  return send(env, id,
    `👥 <b>کاربران</b> — ${fa(users.length)} نفر (صفحه ${fa(page + 1)} از ${fa(pages)})\n✅ = اشتراک فعال\nروی هر کاربر بزن تا جزئیاتش رو ببینی:`,
    {
      reply_markup: {
        inline_keyboard: [
          ...slice.map((u) => [{
            text: `${active.has(String(u.id)) ? "✅ " : ""}${(u.name || "بی‌نام").slice(0, 24)}${u.username ? " @" + u.username : ""}`,
            callback_data: `a:u:${u.id}`,
          }]),
          ...(nav.length ? [nav] : []),
          back(),
        ],
      },
    });
}

async function userDetail(env, id, userId) {
  const u = await getJSON(env, `user:${userId}`);
  if (!u) return send(env, id, "کاربر پیدا نشد.", { reply_markup: { inline_keyboard: [back()] } });
  const now = Date.now() / 1000;
  const [lics, orders, plans] = await Promise.all([readAll(env, `lic:${userId}:`), readAll(env, "order:"), getPlans(env)]);
  const mine = orders.filter((o) => String(o.userId) === String(userId));
  const paid = mine.filter((o) => o.status === "approved").reduce((t, o) => t + (o.amount || 0), 0);

  const licLines = lics.map((l) =>
    `• <code>${l.device}</code> — ${esc(plans[l.plan]?.title || l.title || l.plan)} — ${l.expires > now ? "فعال تا " + faDate(l.expires) : "منقضی"}`);

  return send(env, id,
    "👤 <b>کاربر</b>\n" +
    `${userLink(u)}\n` +
    `عضویت: ${u.joined ? faDate(Math.floor(u.joined / 1000)) : "—"}\n` +
    `سفارش‌ها: ${fa(mine.length)} · پرداخت شده: ${toman(paid)}\n\n` +
    `<b>اشتراک‌ها</b>\n${licLines.join("\n") || "ندارد"}`,
    {
      reply_markup: {
        inline_keyboard: [
          [{ text: "🎁 لایسنس برای این کاربر", callback_data: `a:gift:${userId}` }, { text: "✉️ پیام", callback_data: `a:msg:${userId}` }],
          back("a:users:0", "⬅️ لیست کاربران"),
        ],
      },
    });
}

async function findUser(env, id, text) {
  const q = text.trim();
  await putJSON(env, `user:${id}`, { ...(await getJSON(env, `user:${id}`)), step: null });
  const latin = q.replace(/[۰-۹]/g, (c) => "۰۱۲۳۴۵۶۷۸۹".indexOf(c));
  if (/^\d+$/.test(latin) && (await getJSON(env, `user:${latin}`))) return userDetail(env, id, latin);
  const device = normalizeDevice(q);
  if (DEVICE_RE.test(device)) {
    const lic = (await readAll(env, "lic:")).find((l) => l.device === device);
    if (lic) return userDetail(env, id, String(lic.userId));
  }
  const name = q.replace(/^@/, "").toLowerCase();
  const users = await readAll(env, "user:");
  const hit = users.find((u) => (u.username || "").toLowerCase() === name) ||
    users.find((u) => (u.name || "").toLowerCase().includes(name));
  if (hit) return userDetail(env, id, String(hit.id));
  return send(env, id, "کسی پیدا نشد.", { reply_markup: { inline_keyboard: [[{ text: "🔎 دوباره", callback_data: "a:find" }], back()] } });
}

async function plansMenu(env, id) {
  const plans = await getPlans(env);
  return send(env, id,
    "💳 <b>پلن‌ها</b>\n" +
    Object.values(plans).map((p) => `${p.active ? "🟢" : "⚪️"} ${esc(p.title)} — ${toman(p.price)} — ${fa(p.days)} روز`).join("\n") +
    "\n\n🟢 = برای خرید نمایش داده می‌شود\nبرای ویرایش روی پلن بزن:",
    {
      reply_markup: {
        inline_keyboard: [
          ...Object.entries(plans).map(([pid, p]) => [{ text: `✏️ ${p.title}`, callback_data: `a:p:${pid}` }]),
          [{ text: "➕ پلن جدید", callback_data: "a:pn" }],
          back(),
        ],
      },
    });
}

async function planDetail(env, id, pid) {
  const plans = await getPlans(env);
  const p = plans[pid];
  if (!p) return plansMenu(env, id);
  return send(env, id,
    `✏️ <b>${esc(p.title)}</b>\n` +
    `قیمت: ${toman(p.price)}\n` +
    `مدت: ${fa(p.days)} روز\n` +
    `وضعیت: ${p.active ? "🟢 فعال" : "⚪️ مخفی"}\n\n` +
    "تغییر قیمت روی لایسنس‌های قبلی اثری ندارد.",
    {
      reply_markup: {
        inline_keyboard: [
          [{ text: "💰 قیمت", callback_data: `a:pe:${pid}:price` }, { text: "📅 مدت", callback_data: `a:pe:${pid}:days` }, { text: "🏷 عنوان", callback_data: `a:pe:${pid}:title` }],
          [{ text: p.active ? "⚪️ مخفی کن" : "🟢 فعال کن", callback_data: `a:pt:${pid}` }, { text: "🗑 حذف", callback_data: `a:pd:${pid}` }],
          back("a:plans", "⬅️ پلن‌ها"),
        ],
      },
    });
}

async function giftIssue(env, id, pid) {
  const state = await getJSON(env, `user:${id}`);
  const device = state?.giftDevice;
  const plans = await getPlans(env);
  const plan = plans[pid];
  if (!device || !plan) return adminHome(env, id);

  const target = state.giftUser || null;
  const licKey = `lic:${target || "manual"}:${device}`;
  const existing = await getJSON(env, licKey);
  const from = existing && existing.expires > Date.now() / 1000 ? existing.expires : 0;
  const { code, expires } = await issueLicense(env.LICENSE_KEY, device, pid, plan.days, from);
  await putJSON(env, licKey, { userId: target || "manual", device, plan: pid, title: plan.title, expires, code, reminded: [] });
  const orderId = crypto.randomUUID().slice(0, 8);
  await putJSON(env, `order:${orderId}`, {
    id: orderId, userId: target || "manual", plan: pid, title: plan.title, days: plan.days, device, amount: 0,
    status: "approved", kind: "gift", created: Date.now(), approvedAt: Date.now(),
  });
  await putJSON(env, `user:${id}`, { ...state, step: null, giftDevice: null, giftUser: null });

  if (target) {
    await send(env, target,
      `🎁 یک اشتراک <b>${esc(plan.title)}</b> برات فعال شد.\nمعتبر تا: <b>${faDate(expires)}</b>\n\nکد لایسنس:\n<code>${code}</code>\n\n` +
      "در اپ پل روی گوشی اندروید ← بخش «اشتراک» ← کد رو وارد کن ← «فعال‌سازی».");
  }
  return send(env, id,
    `✅ لایسنس ساخته شد${target ? " و برای کاربر فرستاده شد" : ""}.\n` +
    `دستگاه: <code>${device}</code>\nپلن: ${esc(plan.title)} — تا ${faDate(expires)}\n\n<code>${code}</code>`,
    { reply_markup: { inline_keyboard: [back()] } });
}

async function broadcast(env, id) {
  const state = await getJSON(env, `user:${id}`);
  const text = state?.broadcastText;
  if (!text) return adminHome(env, id);
  await putJSON(env, `user:${id}`, { ...state, step: null, broadcastText: null });
  const users = await customers(env);
  await send(env, id, `⏳ در حال ارسال برای ${fa(users.length)} نفر…`);
  let ok = 0;
  for (const u of users) {
    const r = await send(env, u.id, `📣 ${esc(text)}`);
    if (r.ok) ok++;
    await new Promise((res) => setTimeout(res, 40)); // stay under Telegram's rate limit
  }
  return send(env, id, `✅ ارسال شد: ${fa(ok)} از ${fa(users.length)}`, { reply_markup: { inline_keyboard: [back()] } });
}
