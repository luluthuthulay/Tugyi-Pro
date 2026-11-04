#!/usr/bin/env python3
"""
Tugyi Pro engine v1.

Fetch the Au1rxx by-country v2ray files for SG, JP, US, dedupe by
server+port+protocol, live-test each candidate with sing-box
(HTTP-over-proxy to http://www.gstatic.com/generate_204, 2 rounds),
keep only nodes with >=50% success, rank by median latency, and write
the top 10 per country to output/<country>.txt in the original
subscription-URI form (so NekoBox / sing-box clients can import them
directly, with no re-encoding).

Limits, stated plainly:
- At most MAX_TEST nodes per country per run, in source order, so a
  scheduled run stays within GitHub Actions time limits. US has 694
  source nodes, so a single run does NOT test every US node; the next
  run starts from the same source order, so coverage is best-effort.
- A node that cannot be translated to a sing-box outbound is skipped,
  not counted as live or dead.
- "Live" here means live from the GitHub runner (US/EU), NOT from
  Myanmar. The phone still needs in-country failover #1 -> #2 -> #3.
- On any fetch failure, the previous output file is left untouched.
"""
import base64
import json
import os
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

SOURCES = {
    "sg": "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-SG.txt",
    "jp": "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-JP.txt",
    "us": "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-US.txt",
}
TEST_URL = "http://www.gstatic.com/generate_204"
MAX_TEST = int(os.environ.get("TUGYI_MAX_TEST", "100"))
TOP_N = int(os.environ.get("TUGYI_TOP_N", "10"))
WORKERS = int(os.environ.get("TUGYI_WORKERS", "10"))
SING_BOX = os.environ.get("SING_BOX", "./sing-box")


def fetch_text(url):
    req = urllib.request.Request(url, headers={"User-Agent": "TugyiPro-Engine/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode(errors="ignore")


def decode_subscription(text):
    text = text.strip()
    try:
        decoded = base64.b64decode(text).decode(errors="ignore")
        if "://" in decoded:
            return [l.strip() for l in decoded.splitlines() if l.strip()]
    except Exception:
        pass
    return [l.strip() for l in text.splitlines() if l.strip()]


def b64decode_padded(s):
    s = s.strip().replace("-", "+").replace("_", "/")
    return base64.b64decode(s + "=" * (-len(s) % 4))


def node_key(uri):
    """Exact URI (without the display name) for dedupe.
    Dedupe by server+port alone would wrongly collapse distinct nodes
    that share a CDN host/port but have different UUIDs/passwords, so
    the full credential-bearing URI is the key. Returns None if the
    URI has no scheme/host information at all.
    """
    try:
        if "://" not in uri:
            return None
        return uri.split("#", 1)[0]
    except Exception:
        return None


def transport_from(net, query, host_fallback=""):
    if net in ("ws", "websocket"):
        t = {"type": "ws", "path": query.get("path", ["/"])[0] or "/"}
        host = query.get("host", [""])[0] or host_fallback
        if host:
            t["headers"] = {"Host": host}
        return t
    if net == "grpc":
        return {"type": "grpc", "service_name": query.get("serviceName", [""])[0] or query.get("path", [""])[0]}
    return None


def tls_from(query, security, sni_fallback=""):
    if security in ("tls", "reality"):
        tls = {"enabled": True}
        sni = query.get("sni", [""])[0] or sni_fallback
        if sni and sni != "off":
            tls["server_name"] = sni
        if query.get("insecure", [""])[0] == "1":
            tls["insecure"] = True
        fp = query.get("fp", [""])[0]
        if fp:
            tls["utls"] = {"enabled": True, "fingerprint": fp}
        if security == "reality":
            pbk = query.get("pbk", [""])[0]
            if not pbk:
                return None
            tls["reality"] = {
                "enabled": True,
                "public_key": pbk,
                "short_id": query.get("sid", [""])[0],
            }
        return tls
    return None


def to_outbound(uri):
    """Translate a node URI to a sing-box outbound dict, or None to skip."""
    scheme, rest = uri.split("://", 1)
    body, _, _frag = rest.partition("#")
    if scheme == "ss":
        userinfo, _, hostport = body.rpartition("@")
        try:
            method, password = b64decode_padded(userinfo).decode().split(":", 1)
        except Exception:
            return None
        parsed = urllib.parse.urlparse(f"//{hostport}")
        if not parsed.hostname or not parsed.port:
            return None
        return {"type": "shadowsocks", "server": parsed.hostname, "server_port": parsed.port,
                "method": method, "password": password}
    if scheme == "trojan":
        parsed = urllib.parse.urlparse(uri)
        query = urllib.parse.parse_qs(parsed.query)
        tls = {"enabled": True}
        sni = query.get("sni", [""])[0]
        if sni:
            tls["server_name"] = sni
        if query.get("insecure", [""])[0] == "1":
            tls["insecure"] = True
        out = {"type": "trojan", "server": parsed.hostname, "server_port": parsed.port,
               "password": parsed.username or "", "tls": tls}
        t = transport_from(query.get("type", ["tcp"])[0], query)
        if t:
            out["transport"] = t
        return out
    if scheme == "vless":
        parsed = urllib.parse.urlparse(uri)
        query = urllib.parse.parse_qs(parsed.query)
        security = query.get("security", [""])[0]
        out = {"type": "vless", "server": parsed.hostname, "server_port": parsed.port,
               "uuid": parsed.username or ""}
        flow = query.get("flow", [""])[0]
        if flow:
            out["flow"] = flow
        tls = tls_from(query, security, parsed.hostname or "")
        if security in ("tls", "reality") and tls is None:
            return None
        if tls:
            out["tls"] = tls
        t = transport_from(query.get("type", ["tcp"])[0], query, query.get("host", [""])[0])
        if t:
            out["transport"] = t
        return out
    if scheme == "vmess":
        try:
            data = json.loads(b64decode_padded(body))
        except Exception:
            return None
        out = {"type": "vmess", "server": data.get("add"), "server_port": int(data.get("port", 0)),
               "uuid": data.get("id"), "security": "auto", "alter_id": int(data.get("aid", 0))}
        if data.get("tls") == "tls":
            out["tls"] = {"enabled": True, "server_name": data.get("sni") or data.get("add")}
        net = data.get("net", "tcp")
        if net == "ws":
            out["transport"] = {"type": "ws", "path": data.get("path") or "/",
                                "headers": {"Host": data.get("host") or ""} if data.get("host") else {}}
        elif net == "grpc":
            out["transport"] = {"type": "grpc", "service_name": data.get("path") or ""}
        return out
    if scheme in ("hy2", "hysteria2"):
        parsed = urllib.parse.urlparse(uri)
        query = urllib.parse.parse_qs(parsed.query)
        tls = {"enabled": True}
        sni = query.get("sni", [""])[0]
        if sni and sni != "off":
            tls["server_name"] = sni
        if query.get("insecure", [""])[0] == "1":
            tls["insecure"] = True
        out = {"type": "hysteria2", "server": parsed.hostname, "server_port": parsed.port,
               "password": parsed.username or "", "tls": tls}
        obfs = query.get("obfs", [""])[0]
        if obfs:
            out["obfs"] = {"type": obfs, "password": query.get("obfs-password", [""])[0]}
        return out
    return None


def probe(uri, port):
    """2 rounds via sing-box SOCKS inbound. Returns median ms or None."""
    outbound = to_outbound(uri)
    if outbound is None:
        return "skip", None
    config = {
        "log": {"level": "error"},
        "inbounds": [{"type": "socks", "listen": "127.0.0.1", "listen_port": port}],
        "outbounds": [outbound, {"type": "direct", "tag": "direct"}],
    }
    times = []
    with tempfile.TemporaryDirectory() as td:
        cfg = os.path.join(td, "config.json")
        with open(cfg, "w") as f:
            json.dump(config, f)
        proc = subprocess.Popen([SING_BOX, "run", "-c", cfg],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            time.sleep(1.0)
            for _ in range(2):
                start = time.time()
                r = subprocess.run(
                    ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                     "--max-time", "5", "--proxy", f"socks5h://127.0.0.1:{port}", TEST_URL],
                    capture_output=True, text=True, timeout=8)
                elapsed = (time.time() - start) * 1000
                if r.stdout.strip() == "204":
                    times.append(elapsed)
        except Exception:
            pass
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except Exception:
                proc.kill()
    if len(times) < 1:  # need >=50% of 2 rounds = at least 1 success
        return "dead", None
    return "live", statistics.median(times)


def run_country(code, url):
    print(f"[{code}] fetching {url}", flush=True)
    raw = fetch_text(url)
    lines = decode_subscription(raw)
    seen = set()
    unique = []
    for line in lines:
        key = node_key(line)
        if key is None or key in seen:
            continue
        seen.add(key)
        unique.append(line)
    candidates = unique[:MAX_TEST]
    print(f"[{code}] source={len(lines)} unique={len(unique)} testing={len(candidates)}", flush=True)

    results = []
    skipped = 0
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {}
        for i, uri in enumerate(candidates):
            port = 30000 + (i % 5000)
            futures[pool.submit(probe, uri, port)] = uri
        for fut, uri in futures.items():
            status, ms = fut.result()
            if status == "skip":
                skipped += 1
            elif status == "live":
                results.append((ms, uri))
    results.sort(key=lambda x: x[0])
    print(f"[{code}] live={len(results)} skipped={skipped} "
          f"best={results[0][0]:.0f}ms" if results else f"[{code}] live=0 skipped={skipped}", flush=True)
    return [uri for _ms, uri in results[:TOP_N]]


def main():
    os.makedirs("output", exist_ok=True)
    for code, url in SOURCES.items():
        try:
            top = run_country(code, url)
        except Exception as e:
            print(f"[{code}] FAILED, keeping previous output: {e}", flush=True)
            continue
        if not top:
            print(f"[{code}] no live nodes, keeping previous output", flush=True)
            continue
        with open(f"output/{code}.txt", "w") as f:
            f.write("\n".join(top) + "\n")
        print(f"[{code}] wrote output/{code}.txt with {len(top)} nodes", flush=True)


if __name__ == "__main__":
    main()
