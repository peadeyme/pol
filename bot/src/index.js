// Pol sales bot — Cloudflare Worker + Telegram.
//
// Customers: /start → choose plan → send device code → card-to-card → send receipt photo
// → receipt goes to the admin group with Approve / Reject → on approve the bot signs
// a license code and sends it to the customer.
// Owner (whoever ran /setgroup): /start opens the admin panel (admin.js).
//
// Secrets: BOT_TOKEN, LICENSE_KEY, WEBHOOK_SECRET, CARD_NUMBER, CARD_HOLDER
// Vars (wrangler.toml): SUPPORT
// KV binding: DB

import { adminHome, onAdminCallback, onAdminText } from "./admin.js";
import { DEVICE_RE, issueLicense, normalizeDevice } from "./license.js";
import {
  adminGroup, cardInfo, esc, fa, faDate, getJSON, getPlans, isAdmin, listAll, putJSON, send, tg, toman, userLink,
} from "./util.js";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === "/webhook" && request.method === "POST") {
      if (request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const update = await request.json();
      ctx.waitUntil(handleUpdate(update, env).catch((e) => console.error("update failed", e)));
      return new Response("ok");
    }

    // One-time setup: open https://<worker>/setup?secret=<WEBHOOK_SECRET>
    if (url.pathname === "/setup") {
      if (url.searchParams.get("secret") !== env.WEBHOOK_SECRET) return new Response("forbidden", { status: 403 });
      const hook = await tg(env, "setWebhook", {
        url: `${url.origin}/webhook`,
        secret_token: env.WEBHOOK_SECRET,
        allowed_updates: ["message", "callback_query"],
      });
      await tg(env, "setMyCommands", {
        commands: [
          { command: "start", description: "خرید و تمدید اشتراک" },
          { command: "mylicense", description: "اشتراک‌های من" },
          { command: "help", description: "راهنما" },
        ],
      });
      return Response.json({ webhook: hook });
    }

    return new Response("Pol bot — from PEDICODE");
  },

  // Daily: remind customers 3 days and 1 day before expiry.
  async scheduled(event, env, ctx) {
    ctx.waitUntil(sendReminders(env));
  },
};

// ---------- Keyboards ----------

async function plansKeyboard(env) {
  const plans = await getPlans(env);
  return {
    inline_keyboard: [
      ...Object.entries(plans)
        .filter(([, p]) => p.active)
        .map(([id, p]) => [{ text: `${p.title} — ${toman(p.price)}`, callback_data: `plan:${id}` }]),
      [{ text: "اشتراک‌های من", callback_data: "mine" }, { text: "راهنما", callback_data: "help" }],
    ],
  };
}

const cancelKeyboard = { inline_keyboard: [[{ text: "لغو", callback_data: "cancel" }]] };

const WELCOME =
  "سلام! 👋\n" +
  "اینجا می‌تونی اشتراک <b>پل</b> رو بخری یا تمدید کنی.\n" +
  "پل تماس و پیامک گوشی اندرویدت رو روی آیفون میاره.\n\n" +
  "یک پلن انتخاب کن:";

const showWelcome = async (env, chatId) => send(env, chatId, WELCOME, { reply_markup: await plansKeyboard(env) });

function helpText(env) {
  return (
    "<b>راهنمای خرید</b>\n" +
    "۱. پلن رو انتخاب کن.\n" +
    "۲. در اپ پل روی گوشی اندروید، بخش «اشتراک»، روی <b>کد دستگاه</b> بزن تا کپی بشه و اینجا بفرستش.\n" +
    "۳. مبلغ رو کارت‌به‌کارت کن و عکس رسید رو بفرست.\n" +
    "۴. بعد از تأیید، کد لایسنس برات میاد. اون رو در اپ اندروید وارد کن و «فعال‌سازی» رو بزن.\n\n" +
    (env.SUPPORT ? `پشتیبانی: ${esc(env.SUPPORT)}\n` : "") +
    "from PEDICODE — pedicode.ir"
  );
}

// ---------- Update handling ----------

async function handleUpdate(update, env) {
  if (update.callback_query) return onCallback(update.callback_query, env);
  const msg = update.message;
  if (!msg) return;

  if (msg.chat.type === "group" || msg.chat.type === "supergroup") return onGroupMessage(msg, env);
  if (msg.chat.type !== "private") return;

  const user = msg.from;
  const key = `user:${user.id}`;
  let state = await getJSON(env, key);
  const admin = await isAdmin(env, user.id);
  const fullName = [user.first_name, user.last_name].filter(Boolean).join(" ");

  if (!state) {
    state = { id: user.id, name: fullName, username: user.username || null, joined: Date.now(), step: null };
    await putJSON(env, key, state);
    const group = await adminGroup(env);
    if (group && !admin) await send(env, group, `👤 <b>کاربر جدید</b>\n${userLink(state)}`);
  } else if (state.username !== (user.username || null) || state.name !== fullName) {
    // Keep name/username fresh for the admin's user list.
    state.username = user.username || null;
    state.name = fullName;
    if (!state.joined) state.joined = Date.now();
    await putJSON(env, key, state);
  }

  const text = (msg.text || "").trim();

  if (admin) {
    if (text.startsWith("/start") || text.startsWith("/admin")) return adminHome(env, user.id);
    if ((state.step || "").startsWith("adm:")) return onAdminText(env, msg, state);
  }

  if (text.startsWith("/start")) {
    state.step = null;
    await putJSON(env, key, state);
    return showWelcome(env, user.id);
  }
  if (text.startsWith("/help")) return send(env, user.id, helpText(env));
  if (text.startsWith("/mylicense")) return showMine(env, user.id);

  if (state.step === "device" && text) return onDeviceCode(env, user, state, text);
  if (state.step === "receipt" && (msg.photo || msg.document)) return onReceipt(env, msg, state);
  if (state.step === "receipt") {
    return send(env, user.id, "لطفاً <b>عکس رسید</b> واریز رو بفرست.", { reply_markup: cancelKeyboard });
  }
  return admin ? adminHome(env, user.id) : showWelcome(env, user.id);
}

async function onDeviceCode(env, user, state, text) {
  const device = normalizeDevice(text);
  if (!DEVICE_RE.test(device)) {
    return send(env, user.id,
      "این کد درست نیست. کد دستگاه ۱۰ کاراکتره، مثل <code>7F3A2-9C41B</code>.\n" +
      "در اپ پل روی گوشی اندروید، بخش «اشتراک» پیداش می‌کنی.",
      { reply_markup: cancelKeyboard });
  }
  const plans = await getPlans(env);
  const plan = plans[state.plan];
  if (!plan || !plan.active) {
    state.step = null;
    await putJSON(env, `user:${user.id}`, state);
    return send(env, user.id, "این پلن دیگه موجود نیست. دوباره انتخاب کن:", { reply_markup: await plansKeyboard(env) });
  }
  const card = await cardInfo(env);
  const orderId = crypto.randomUUID().slice(0, 8);
  // Price and length are frozen on the order, so later plan edits don't change it.
  await putJSON(env, `order:${orderId}`, {
    id: orderId, userId: user.id, plan: state.plan, title: plan.title, days: plan.days, device,
    amount: plan.price, status: "waiting_receipt", created: Date.now(),
  });
  state.step = "receipt";
  state.device = device;
  state.orderId = orderId;
  await putJSON(env, `user:${user.id}`, state);

  return send(env, user.id,
    `پلن: <b>${esc(plan.title)}</b>\n` +
    `کد دستگاه: <code>${device}</code>\n\n` +
    `مبلغ <b>${toman(plan.price)}</b> رو به این کارت واریز کن:\n\n` +
    `<code>${esc(card.number)}</code>\n` +
    `به نام: ${esc(card.holder)}\n\n` +
    "بعد <b>عکس رسید</b> رو همینجا بفرست.",
    { reply_markup: cancelKeyboard });
}

async function onReceipt(env, msg, state) {
  const user = msg.from;
  const group = await adminGroup(env);
  const order = await getJSON(env, `order:${state.orderId}`);
  if (!order) {
    state.step = null;
    await putJSON(env, `user:${user.id}`, state);
    return send(env, user.id, "سفارش پیدا نشد. دوباره از /start شروع کن.");
  }
  if (!group) {
    return send(env, user.id, "سیستم تأیید موقتاً در دسترس نیست. چند دقیقه دیگه دوباره رسید رو بفرست.");
  }

  const caption =
    `🧾 <b>رسید جدید</b> — سفارش <code>${order.id}</code>\n` +
    `${userLink(state)}\n` +
    `پلن: ${esc(order.title)} — ${toman(order.amount)}\n` +
    `کد دستگاه: <code>${order.device}</code>`;
  const buttons = {
    inline_keyboard: [[
      { text: "✅ تأیید", callback_data: `ok:${order.id}` },
      { text: "❌ رد", callback_data: `no:${order.id}` },
    ]],
  };
  const sent = msg.photo
    ? await tg(env, "sendPhoto", { chat_id: group, photo: msg.photo[msg.photo.length - 1].file_id, caption, parse_mode: "HTML", reply_markup: buttons })
    : await tg(env, "sendDocument", { chat_id: group, document: msg.document.file_id, caption, parse_mode: "HTML", reply_markup: buttons });

  order.status = "pending";
  order.groupMessage = sent.result?.message_id ?? null;
  await putJSON(env, `order:${order.id}`, order);
  state.step = null;
  await putJSON(env, `user:${user.id}`, state);

  return send(env, user.id, "رسید دریافت شد ✅\nبعد از بررسی، کد لایسنس همینجا برات فرستاده می‌شه.");
}

async function onCallback(q, env) {
  const data = q.data || "";
  const chatId = q.message?.chat?.id;
  const answer = (text) => tg(env, "answerCallbackQuery", { callback_query_id: q.id, text });

  // Decisions on receipts: only inside the admin group.
  if (data.startsWith("ok:") || data.startsWith("no:")) {
    const group = await adminGroup(env);
    if (String(chatId) !== String(group)) return answer("اجازه نداری");
    return decide(env, q, data.slice(0, 2) === "ok", data.slice(3));
  }

  // Admin panel.
  if (data.startsWith("a:")) {
    if (!(await isAdmin(env, q.from.id))) return answer("اجازه نداری");
    return onAdminCallback(env, q, data, (id) => showWelcome(env, id));
  }

  const user = q.from;
  const key = `user:${user.id}`;
  const state = (await getJSON(env, key)) || { id: user.id, step: null };
  await answer();

  if (data.startsWith("plan:")) {
    const pid = data.slice(5);
    const plans = await getPlans(env);
    const plan = plans[pid];
    if (!plan || !plan.active) return send(env, user.id, "این پلن دیگه موجود نیست.", { reply_markup: await plansKeyboard(env) });
    state.step = "device";
    state.plan = pid;
    await putJSON(env, key, state);
    return send(env, user.id,
      `پلن <b>${esc(plan.title)}</b> — ${toman(plan.price)}\n\n` +
      "حالا <b>کد دستگاه</b> رو بفرست.\n" +
      "در اپ پل روی گوشی اندروید ← بخش «اشتراک» ← روی کد دستگاه بزن تا کپی بشه.\n" +
      "مثال: <code>7F3A2-9C41B</code>",
      { reply_markup: cancelKeyboard });
  }
  if (data === "cancel") {
    state.step = null;
    await putJSON(env, key, state);
    return send(env, user.id, "لغو شد.", { reply_markup: await plansKeyboard(env) });
  }
  if (data === "mine") return showMine(env, user.id);
  if (data === "help") return send(env, user.id, helpText(env));
}

async function decide(env, q, approve, orderId) {
  const order = await getJSON(env, `order:${orderId}`);
  const answer = (text) => tg(env, "answerCallbackQuery", { callback_query_id: q.id, text, show_alert: !!text });
  if (!order) return answer("سفارش پیدا نشد");
  if (order.status !== "pending") return answer("این سفارش قبلاً بررسی شده");

  const admin = q.from.username ? "@" + q.from.username : q.from.first_name;
  const oldCaption = q.message.caption || "";

  if (!approve) {
    order.status = "rejected";
    order.decidedBy = admin;
    await putJSON(env, `order:${orderId}`, order);
    await tg(env, "editMessageCaption", {
      chat_id: q.message.chat.id, message_id: q.message.message_id,
      caption: `${esc(oldCaption)}\n\n❌ رد شد — ${esc(admin)}`, parse_mode: "HTML",
    });
    await send(env, order.userId,
      "رسید تأیید نشد ❌\nاگه فکر می‌کنی اشتباه شده، با پشتیبانی در ارتباط باش" +
      (env.SUPPORT ? `: ${esc(env.SUPPORT)}` : "."),
      { reply_markup: await plansKeyboard(env) });
    return answer();
  }

  // Extend from the current expiry when renewing the same phone.
  const licKey = `lic:${order.userId}:${order.device}`;
  const existing = await getJSON(env, licKey);
  const from = existing && existing.expires > Date.now() / 1000 ? existing.expires : 0;
  const days = order.days || (await getPlans(env))[order.plan]?.days || 31;
  const { code, expires } = await issueLicense(env.LICENSE_KEY, order.device, order.plan, days, from);

  order.status = "approved";
  order.decidedBy = admin;
  order.approvedAt = Date.now();
  await putJSON(env, `order:${orderId}`, order);
  await putJSON(env, licKey, { userId: order.userId, device: order.device, plan: order.plan, title: order.title, expires, code, reminded: [] });

  await send(env, order.userId,
    `پرداختت تأیید شد ✅\n\n` +
    `پلن: <b>${esc(order.title || "")}</b>\n` +
    `معتبر تا: <b>${faDate(expires)}</b>\n\n` +
    "کد لایسنس (روش بزن تا کپی بشه):\n" +
    `<code>${code}</code>\n\n` +
    "در اپ پل روی گوشی اندروید ← بخش «اشتراک» ← کد رو وارد کن ← «فعال‌سازی».");
  await tg(env, "editMessageCaption", {
    chat_id: q.message.chat.id, message_id: q.message.message_id,
    caption: `${esc(oldCaption)}\n\n✅ تأیید شد — ${esc(admin)}\nتا ${faDate(expires)}`, parse_mode: "HTML",
  });
  return answer();
}

async function showMine(env, userId) {
  const keys = await listAll(env, `lic:${userId}:`);
  if (!keys.length) {
    return send(env, userId, "هنوز اشتراکی نداری.", { reply_markup: await plansKeyboard(env) });
  }
  const now = Date.now() / 1000;
  const lines = [];
  for (const k of keys) {
    const lic = await getJSON(env, k);
    if (!lic) continue;
    const status = lic.expires > now ? `فعال تا ${faDate(lic.expires)}` : "منقضی شده";
    lines.push(`• <code>${lic.device}</code> — ${esc(lic.title || "")} — ${status}\n<code>${lic.code}</code>`);
  }
  return send(env, userId, "<b>اشتراک‌های تو</b>\n\n" + lines.join("\n\n"), { reply_markup: await plansKeyboard(env) });
}

// ---------- Group commands ----------

async function onGroupMessage(msg, env) {
  const text = (msg.text || "").trim();
  if (!text.startsWith("/setgroup")) return;

  const current = await adminGroup(env);
  const owner = await env.DB.get("config:owner");
  if (current && owner && String(owner) !== String(msg.from.id)) {
    return send(env, msg.chat.id, "گروه مدیریت قبلاً تنظیم شده.");
  }
  // Only a group admin can claim the group.
  const member = await tg(env, "getChatMember", { chat_id: msg.chat.id, user_id: msg.from.id });
  const status = member.result?.status;
  if (status !== "creator" && status !== "administrator") {
    return send(env, msg.chat.id, "فقط ادمین گروه می‌تونه این کار رو بکنه.");
  }
  await env.DB.put("config:group", String(msg.chat.id));
  await env.DB.put("config:owner", String(msg.from.id));
  return send(env, msg.chat.id,
    "✅ این گروه برای <b>رسیدها</b> و <b>اطلاع‌رسانی کاربران جدید</b> تنظیم شد.\n" +
    "رسیدها با دکمه‌ی تأیید / رد اینجا میان.\n" +
    "پنل مدیریت: در چت خصوصی ربات /start بزن.");
}

// ---------- Reminders ----------

async function sendReminders(env) {
  const now = Date.now() / 1000;
  const keys = await listAll(env, "lic:");
  for (const k of keys) {
    const lic = await getJSON(env, k);
    if (!lic || !/^\d+$/.test(String(lic.userId))) continue;
    const daysLeft = Math.ceil((lic.expires - now) / 86400);
    for (const mark of [3, 1]) {
      if (daysLeft === mark && !lic.reminded.includes(mark)) {
        await send(env, lic.userId,
          `⏰ اشتراک پل برای دستگاه <code>${lic.device}</code> ${fa(mark)} روز دیگه تموم می‌شه.\nبرای تمدید یک پلن انتخاب کن:`,
          { reply_markup: await plansKeyboard(env) });
        lic.reminded.push(mark);
        await putJSON(env, k, lic);
      }
    }
  }
}
