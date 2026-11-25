#!/usr/bin/env python3
"""
Issue Pol license codes.

  python3 tools/license.py issue 7F3A2-9C41B monthly
  python3 tools/license.py issue 7F3A2-9C41B 6month
  python3 tools/license.py issue 7F3A2-9C41B yearly
  python3 tools/license.py issue 7F3A2-9C41B monthly --days 10      # custom length

The private key is read from --key (default: ~/pol-license/license_private_key.pem).
Keep that file secret and NEVER commit it: whoever has it can make licenses.

Requires: pip install cryptography
"""
import argparse
import base64
import json
import os
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

PLANS = {"monthly": ("m", 31), "6month": ("s", 183), "yearly": ("y", 366)}
DEFAULT_KEY = os.path.expanduser("~/pol-license/license_private_key.pem")


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def issue(device: str, plan: str, days: int | None, key_path: str) -> str:
    code, default_days = PLANS[plan]
    with open(key_path, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    expires = int(time.time()) + 86400 * (days or default_days)
    payload = json.dumps({"d": device.strip().upper(), "p": code, "e": expires}, separators=(",", ":")).encode()
    signature = key.sign(payload, ec.ECDSA(hashes.SHA256()))
    return b64(payload) + "." + b64(signature)


def main() -> None:
    parser = argparse.ArgumentParser(description="Pol license tool")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("issue", help="make a license code for one Android phone")
    p.add_argument("device", help="device code shown in the Android app, e.g. 7F3A2-9C41B")
    p.add_argument("plan", choices=PLANS)
    p.add_argument("--days", type=int, help="override length in days")
    p.add_argument("--key", default=DEFAULT_KEY)
    args = parser.parse_args()

    if not os.path.exists(args.key):
        sys.exit(f"private key not found: {args.key}")
    code = issue(args.device, args.plan, args.days, args.key)
    print(code)


if __name__ == "__main__":
    main()
