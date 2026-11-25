// Pol license codes: base64url(payload) + "." + base64url(DER ECDSA-P256-SHA256 signature).
// Must match android/.../data/License.kt and tools/license.py.

/** Default plans; the admin can change them from the bot (stored in KV under config:plans). */
export const PLANS = {
  m: { title: "ماهانه", days: 31, price: 300000, active: true },
  s: { title: "شش‌ماهه", days: 183, price: 1500000, active: true },
  y: { title: "سالانه", days: 366, price: 2500000, active: true },
};

export const DEVICE_RE = /^[0-9A-F]{5}-[0-9A-F]{5}$/;

/** Normalises what users paste: Persian/Arabic digits, spaces, lowercase. */
export function normalizeDevice(text) {
  const map = { "۰": "0", "۱": "1", "۲": "2", "۳": "3", "۴": "4", "۵": "5", "۶": "6", "۷": "7", "۸": "8", "۹": "9",
    "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9" };
  let s = String(text || "").trim().toUpperCase().replace(/[۰-۹٠-٩]/g, (c) => map[c]).replace(/[\s_–—]/g, "");
  if (/^[0-9A-F]{10}$/.test(s)) s = s.slice(0, 5) + "-" + s.slice(5);
  return s;
}

function b64url(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromB64(s) {
  const bin = atob(s.replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** WebCrypto returns r||s (64 bytes); Java/Android expect ASN.1 DER. */
function rawToDer(raw) {
  const int = (bytes) => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = bytes.slice(i);
    if (v[0] & 0x80) v = Uint8Array.from([0, ...v]);
    return Uint8Array.from([0x02, v.length, ...v]);
  };
  const r = int(raw.slice(0, 32));
  const s = int(raw.slice(32, 64));
  return Uint8Array.from([0x30, r.length + s.length, ...r, ...s]);
}

let cachedKey;

/** keyB64 = base64 of the PKCS#8 DER private key (the PEM body without header lines). */
async function importKey(keyB64) {
  if (!cachedKey) {
    const body = keyB64.replace(/-----[^-]+-----/g, "");
    cachedKey = await crypto.subtle.importKey(
      "pkcs8", fromB64(body), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"],
    );
  }
  return cachedKey;
}

/** Returns { code, expires } for a device; `days` is the plan length, `from` extends an existing license. */
export async function issueLicense(keyB64, device, plan, days, from = 0) {
  if (!days) throw new Error("missing days");
  const now = Math.floor(Date.now() / 1000);
  const expires = Math.max(now, from) + days * 86400;
  const payload = new TextEncoder().encode(JSON.stringify({ d: device, p: plan, e: expires }));
  const key = await importKey(keyB64);
  const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, payload));
  return { code: b64url(payload) + "." + b64url(rawToDer(raw)), expires };
}
