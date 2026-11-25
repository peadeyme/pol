// Pol sales bot — Cloudflare Worker + Telegram.
//
// Flow: /start → choose plan → send device code → card-to-card → send receipt photo
// → receipt goes to the admin group with Approve / Reject → on approve the bot signs
// a license code and sends it to the customer.
//
// Secrets: BOT_TOKEN, LICENSE_KEY, WEBHOOK_SECRET, CARD_NUMBER, CARD_HOLDER
// Vars (wrangler.toml): SUPPORT
// KV binding: DB

import { DEVICE_RE, PLANS, issueLicense, normalizeDevice } from "./license.js";

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
        allowed_updates: ["message", "callback_query", "my_chat_member"],
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

// ---------- Telegram helpers ----------

async function tg(env, method, body) {
  const res = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!data.ok) console.error(method, data.description);
  return data;
}

const send = (env, chat_id, text, extra = {}) =>
  tg(env, "sendMessage", { chat_id, text, parse_mode: "HTML", disable_web_page_preview: true, ...extra });

const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const fa = (n) => String(n).replace(/\d/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[d]);
const toman = (n) => fa(n.toLocaleString("en-US").replace(/,/g, "٬")) + " تومان";
const faDate = (sec) => {
  try {
    return new Date(sec * 1000).toLocaleDateString("fa-IR", { year: "numeric", month: "long", day: "numeric" });
  } catch {
    return new Date(sec * 1000).toISOString().slice(0, 10);
  }
};

const who = (u) => `${esc([u.first_name, u.last_name].filter(Boolean).join(" "))}${u.username ? " (@" + esc(u.username) + ")" : ""} — <code>${u.id}</code>`;

// ---------- Storage ----------

const getJSON = async (env, key) => JSON.parse((await env.DB.get(key)) || "null");
const putJSON = (env, key, value) => env.DB.put(key, JSON.stringify(value));

async function adminGroup(env) {
  return (await env.DB.get("config:group")) || null;
}

// ---------- Keyboards ----------

function plansKeyboard() {
  return {
    inline_keyboard: [
      ...Object.entries(PLANS).map(([id, p]) => [{ text: `${p.title} — ${toman(p.price)}`, callback_data: `plan:${id}` }]),
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
  const isNew = !state;
  if (isNew) {
    state = { id: user.id, name: [user.first_name, user.last_name].filter(Boolean).join(" "), username: user.username || null, joined: Date.now(), step: null };
    await putJSON(env, key, state);
    const group = await adminGroup(env);
    if (group) await send(env, group, `👤 <b>کاربر جدید</b>\n${who(user)}`);
  }

  const text = (msg.text || "").trim();

  if (text.startsWith("/start")) {
    state.step = null;
    await putJSON(env, key, state);
    return send(env, user.id, WELCOME, { reply_markup: plansKeyboard() });
  }
  if (text.startsWith("/help")) return send(env, user.id, helpText(env));
  if (text.startsWith("/mylicense")) return showMine(env, user.id);

  if (state.step === "device" && text) return onDeviceCode(env, user, state, text);

  if (state.step === "receipt" && (msg.photo || msg.document)) return onReceipt(env, msg, state);

  if (state.step === "receipt") {
    return send(env, user.id, "لطفاً <b>عکس رسید</b> واریز رو بفرست.", { reply_markup: cancelKeyboard });
  }
  return send(env, user.id, WELCOME, { reply_markup: plansKeyboard() });
}

async function onDeviceCode(env, user, state, text) {
  const device = normalizeDevice(text);
  if (!DEVICE_RE.test(device)) {
    return send(env, user.id,
      "این کد درست نیست. کد دستگاه ۱۰ کاراکتره، مثل <code>7F3A2-9C41B</code>.\n" +
      "در اپ پل روی گوشی اندروید، بخش «اشتراک» پیداش می‌کنی.",
      { reply_markup: cancelKeyboard });
  }
  const plan = PLANS[state.plan];
  const orderId = crypto.randomUUID().slice(0, 8);
  await putJSON(env, `order:${orderId}`, {
    id: orderId, userId: user.id, plan: state.plan, device, amount: plan.price, status: "waiting_receipt", created: Date.now(),
  });
  state.step = "receipt";
  state.device = device;
  state.orderId = orderId;
  await putJSON(env, `user:${user.id}`, state);

  return send(env, user.id,
    `پلن: <b>${plan.title}</b>\n` +
    `کد دستگاه: <code>${device}</code>\n\n` +
    `مبلغ <b>${toman(plan.price)}</b> رو به این کارت واریز کن:\n\n` +
    `<code>${esc(env.CARD_NUMBER)}</code>\n` +
    `به نام: ${esc(env.CARD_HOLDER)}\n\n` +
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

  const plan = PLANS[order.plan];
  const caption =
    `🧾 <b>رسید جدید</b> — سفارش <code>${order.id}</code>\n` +
    `${who(user)}\n` +
    `پلن: ${plan.title} — ${toman(plan.price)}\n` +
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

  // Admin decisions: only inside the admin group.
  if (data.startsWith("ok:") || data.startsWith("no:")) {
    const group = await adminGroup(env);
    if (String(chatId) !== String(group)) return answer("اجازه نداری");
    return decide(env, q, data.slice(0, 2) === "ok", data.slice(3));
  }

  const user = q.from;
  const key = `user:${user.id}`;
  const state = (await getJSON(env, key)) || { id: user.id, step: null };
  await answer();

  if (data.startsWith("plan:")) {
    const plan = data.slice(5);
    if (!PLANS[plan]) return;
    state.step = "device";
    state.plan = plan;
    await putJSON(env, key, state);
    return send(env, user.id,
      `پلن <b>${PLANS[plan].title}</b> — ${toman(PLANS[plan].price)}\n\n` +
      "حالا <b>کد دستگاه</b> رو بفرست.\n" +
      "در اپ پل روی گوشی اندروید ← بخش «اشتراک» ← روی کد دستگاه بزن تا کپی بشه.\n" +
      "مثال: <code>7F3A2-9C41B</code>",
      { reply_markup: cancelKeyboard });
  }
  if (data === "cancel") {
    state.step = null;
    await putJSON(env, key, state);
    return send(env, user.id, "لغو شد.", { reply_markup: plansKeyboard() });
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
    await putJSON(env, `order:${orderId}`, order);
    await tg(env, "editMessageCaption", {
      chat_id: q.message.chat.id, message_id: q.message.message_id,
      caption: `${esc(oldCaption)}\n\n❌ رد شد — ${esc(admin)}`, parse_mode: "HTML",
    });
    await send(env, order.userId,
      "رسید تأیید نشد ❌\nاگه فکر می‌کنی اشتباه شده، با پشتیبانی در ارتباط باش" +
      (env.SUPPORT ? `: ${esc(env.SUPPORT)}` : "."),
      { reply_markup: plansKeyboard() });
    return answer();
  }

  // Extend from the current expiry when renewing the same phone.
  const licKey = `lic:${order.userId}:${order.device}`;
  const existing = await getJSON(env, licKey);
  const from = existing && existing.expires > Date.now() / 1000 ? existing.expires : 0;
  const { code, expires } = await issueLicense(env.LICENSE_KEY, order.device, order.plan, from);

  order.status = "approved";
  order.approvedBy = admin;
  await putJSON(env, `order:${orderId}`, order);
  await putJSON(env, licKey, { userId: order.userId, device: order.device, plan: order.plan, expires, code, reminded: [] });

  await send(env, order.userId,
    `پرداختت تأیید شد ✅\n\n` +
    `پلن: <b>${PLANS[order.plan].title}</b>\n` +
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
  const list = await env.DB.list({ prefix: `lic:${userId}:` });
  if (!list.keys.length) {
    return send(env, userId, "هنوز اشتراکی نداری.", { reply_markup: plansKeyboard() });
  }
  const now = Date.now() / 1000;
  const lines = [];
  for (const k of list.keys) {
    const lic = await getJSON(env, k.name);
    if (!lic) continue;
    const status = lic.expires > now ? `فعال تا ${faDate(lic.expires)}` : "منقضی شده";
    lines.push(`• <code>${lic.device}</code> — ${PLANS[lic.plan]?.title ?? ""} — ${status}\n<code>${lic.code}</code>`);
  }
  return send(env, userId, "<b>اشتراک‌های تو</b>\n\n" + lines.join("\n\n"), { reply_markup: plansKeyboard() });
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
    "رسیدها با دکمه‌ی تأیید / رد اینجا میان.");
}

// ---------- Reminders ----------

async function sendReminders(env) {
  const now = Date.now() / 1000;
  let cursor;
  do {
    const page = await env.DB.list({ prefix: "lic:", cursor });
    for (const k of page.keys) {
      const lic = await getJSON(env, k.name);
      if (!lic) continue;
      const daysLeft = Math.ceil((lic.expires - now) / 86400);
      for (const mark of [3, 1]) {
        if (daysLeft === mark && !lic.reminded.includes(mark)) {
          await send(env, lic.userId,
            `⏰ اشتراک پل برای دستگاه <code>${lic.device}</code> ${fa(mark)} روز دیگه تموم می‌شه.\nبرای تمدید یک پلن انتخاب کن:`,
            { reply_markup: plansKeyboard() });
          lic.reminded.push(mark);
          await putJSON(env, k.name, lic);
        }
      }
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
}
