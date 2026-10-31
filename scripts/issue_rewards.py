#!/usr/bin/env python3
"""
Utility script to issue victory rewards or trigger events via Exaroton API.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
ENV_PATH = ROOT_DIR / ".env"


def load_env() -> dict[str, str]:
    env_vars: dict[str, str] = dict(os.environ)
    if ENV_PATH.exists():
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env_vars[k.strip()] = v.strip().strip("'\"")
    return env_vars


def api_request(
    server_id: str,
    token: str,
    endpoint: str,
    method: str = "GET",
    data: dict | bytes | None = None,
) -> dict | None:
    url = f"https://api.exaroton.com/v1/servers/{server_id}{endpoint}"
    headers = {
        "Authorization": f"Bearer {token}",
        "User-Agent": "ExServerMod-Deployer/1.0",
    }
    encoded_data = None
    if data is not None:
        if isinstance(data, dict):
            headers["Content-Type"] = "application/json"
            encoded_data = json.dumps(data).encode("utf-8")
        else:
            encoded_data = data

    req = urllib.request.Request(url, data=encoded_data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
            if body and resp.headers.get_content_type() == "application/json":
                return json.loads(body.decode("utf-8"))
            return None
    except urllib.error.HTTPError as err:
        err_body = err.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Exaroton API error ({err.code} {err.reason}) on {method} {url}: {err_body}") from err


def send_command(server_id: str, token: str, command: str) -> bool:
    res = api_request(server_id, token, "/command", method="POST", data={"command": command})
    return res is not None and res.get("success", False)


def main():
    env = load_env()
    token = env.get("EXAROTON_API_TOKEN")
    server_id = env.get("EXAROTON_SERVER_ID")

    if not token or not server_id:
        print("Missing EXAROTON_API_TOKEN or EXAROTON_SERVER_ID in .env", file=sys.stderr)
        sys.exit(1)

    cmd = sys.argv[1] if len(sys.argv) > 1 else "reward"

    if cmd == "start-server":
        print("[START] Starting server...")
        api_request(server_id, token, "/start", method="POST")
        print("[DONE] Start signal dispatched.")
        return

    if cmd == "reward":
        players = sys.argv[2:] if len(sys.argv) > 2 else ["soroqn", "l_Sky2Fly_l"]
        for p in players:
            print(f"[REWARD] Issuing wave victory rewards to {p} via 'waves reward {p}'...")
            try:
                success = send_command(server_id, token, f"waves reward {p}")
                print(f"[REWARD] Result for {p}: {'SUCCESS' if success else 'FAILED'}")
            except Exception as e:
                print(f"[ERROR] Failed to send command for {p}: {e}")

    elif cmd == "event-start":
        print("[EVENT] Starting Halloween event via 'event start'...")
        try:
            success = send_command(server_id, token, "event start")
            print(f"[EVENT] Result: {'SUCCESS' if success else 'FAILED'}")
        except Exception as e:
            print(f"[ERROR] Failed: {e}")

    elif cmd == "waves-start":
        print("[WAVES] Starting Wave Defense via 'waves start'...")
        try:
            success = send_command(server_id, token, "waves start")
            print(f"[WAVES] Result: {'SUCCESS' if success else 'FAILED'}")
        except Exception as e:
            print(f"[ERROR] Failed: {e}")

    else:
        # Custom command pass-through
        full_cmd = " ".join(sys.argv[1:])
        print(f"[CMD] Executing: {full_cmd}")
        try:
            success = send_command(server_id, token, full_cmd)
            print(f"[CMD] Result: {'SUCCESS' if success else 'FAILED'}")
        except Exception as e:
            print(f"[ERROR] Failed: {e}")


if __name__ == "__main__":
    main()
