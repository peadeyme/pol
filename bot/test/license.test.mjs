// Signs a code with the bot's code path and prints it, for checking against the app's verifier.
import { readFileSync } from "node:fs";
import { issueLicense, normalizeDevice, DEVICE_RE } from "../src/license.js";

const pem = readFileSync(process.argv[2] || process.env.LICENSE_PEM, "utf8");
const device = normalizeDevice("۷f3a2 9c41b");
if (!DEVICE_RE.test(device) || device !== "7F3A2-9C41B") throw new Error("normalize failed: " + device);
const { code } = await issueLicense(pem, device, "s", 183);
console.log(code);
