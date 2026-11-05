#!/usr/bin/env python3
"""
Tugyi Pro engine v4 (phone-proven pattern filter, per Hsu phone tests 2026-11-05).

Sources per country (all verified live on 2026-11-04, pushed that day):
- Au1rxx by-country files (base64, country-specific, SG 216 / JP 148 /
  US 694 at check time).
- 0xRadikal/Free-v2ray-Configs Countries files (plain text, updated
  every 15 minutes, SG 71 / JP 26 / US 288 at check time).
- Global lists, filtered by node display name to that country only:
  NodesRadar v2ray.txt, FastNodes verified.txt, ZorVPN-nodes.txt,
  Pawdroid English sub (US only in practice).
Global lists whose nodes carry no country name (for example
free-nodes/v2rayfree, mostly "unknown") are NOT used, because an
unknown-country node must never be put into an SG/JP/US list.

Dedupe, live-test, ranking and output are as in v1: sing-box
HTTP-over-proxy to http://www.gstatic.com/generate_204, 2 rounds,
>=50% success, median ms ranking, top 10 per country to
output/<country>.txt in original URI form.

Limits, stated plainly:
- At most MAX_TEST nodes per country per run, in source order, so a
  scheduled run stays within GitHub Actions time limits.
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
    # (url, already_country_specific)
    "sg": [
        ("https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-SG.txt", True),
        ("https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/Countries/Singapore.txt", True),
        ("https://radarhub.top/v2ray.txt", False),
        ("https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/verified.txt", False),
        ("https://raw.githubusercontent.com/hamzawih0/zorVPN/main/ZorVPN-nodes.txt", False),
        ("https://gist.githubusercontent.com/cybershieldvpn/d7981da88de440c4ef8ee9271b32eb6f/raw/cybershieldvpn", False),
    ],
    "jp": [
        ("https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-JP.txt", True),
        ("https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/Countries/Japan.txt", True),
        ("https://radarhub.top/v2ray.txt", False),
        ("https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/verified.txt", False),
        ("https://raw.githubusercontent.com/hamzawih0/zorVPN/main/ZorVPN-nodes.txt", False),
        ("https://gist.githubusercontent.com/cybershieldvpn/d7981da88de440c4ef8ee9271b32eb6f/raw/cybershieldvpn", False),
    ],
    "us": [
        ("https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-US.txt", True),
        ("https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/Countries/United_States.txt", True),
        ("https://radarhub.top/v2ray.txt", False),
        ("https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/verified.txt", False),
        ("https://raw.githubusercontent.com/hamzawih0/zorVPN/main/ZorVPN-nodes.txt", False),
        ("https://raw.githubusercontent.com/Pawdroid/Free-servers/main/static/sub_en", False),
        ("https://gist.githubusercontent.com/cybershieldvpn/d7981da88de440c4ef8ee9271b32eb6f/raw/cybershieldvpn", False),
    ],
}

# Phone-verified pattern 2026-11-04 (Hsu, Myanmar network):
# Shadowsocks aes-256-gcm port 19999 nodes connect, while plain
# VMess port 18000 and SS rc4-md5 port 8080 do not. So these nodes
# are tested first and ranked first in the output, ahead of ms order.
# GitHub-runner ms alone does not prove Myanmar connectivity.


def is_ss_19999(uri):
    """True for ss:// nodes with method aes-256-gcm and port 19999.
    SS userinfo may be plain method:password or base64(method:password)."""
    try:
        if not uri.startswith("ss://"):
            return False
        rest = uri[5:].split("#", 1)[0]
        if "@" not in rest:
            return False
        userinfo, hostport = rest.rsplit("@", 1)
        if ":" not in hostport or hostport.rsplit(":", 1)[1] != "19999":
            return False
        method = userinfo.split(":", 1)[0]
        if method == "aes-256-gcm":
            return True
        try:
            decoded = b64decode_padded(userinfo).decode(errors="ignore")
            return decoded.split(":", 1)[0] == "aes-256-gcm"
        except Exception:
            return False
    except Exception:
        return False

# Phone-proven working pattern, Hsu 2026-11-05 (Happ app, Myanmar network).
# SG working file (14 nodes, all connect): VLESS/TCP/Reality, flow
# xtls-rprx-vision, real SNI (apple/yandex/icloud), real browser fp
# (chrome/safari/firefox/ios, never none). Port, SNI brand, fp brand
# and source do NOT decide connection - the 3-part pattern does.
# JP working file (22 nodes, all connect): same VLESS Reality pattern
# PLUS Hysteria2 (TLS 443, SNI = server domain itself, no fp field -
# Hy2 is QUIC/UDP, do NOT apply VLESS Reality criteria to it).
# US working file (9 nodes, all connect): CyberShield SS19999 (6/8
# connect - individual servers can die, e.g. .72/.27) + Radikal US
# VLESS Reality (3 nodes). Radikal US SS19999 does NOT connect, all
# Au1rxx/FastNodes US VLESS in the test were n/a (do not connect).
# Excluded by this rule, even if GitHub-live: VMess WS TLS (connects
# with poor line in SG, names rotate out), plain VMess, SS rc4-md5,
# SS19999 from any source EXCEPT CyberShield, VLESS TLS non-Reality,
# VLESS Reality with fp none or SNI = IP / empty, Trojan (unproven).
REAL_FPS = {"chrome", "safari", "firefox", "ios"}

def _is_ip(host):
    try:
        import ipaddress
        ipaddress.ip_address(host)
        return True
    except Exception:
        return False

def _sni_is_real_domain(uri_query, server):
    sni = uri_query.get("sni", [""])[0] if isinstance(uri_query, dict) else ""
    if not sni or sni == "off":
        return False
    if _is_ip(sni) or sni == server:
        return False
    return "." in sni

def is_vless_reality_proven(uri):
    """VLESS Reality node matching the phone-proven SG/JP pattern."""
    try:
        if not uri.startswith("vless://"):
            return False
        parsed = urllib.parse.urlparse(uri)
        q = urllib.parse.parse_qs(parsed.query)
        if q.get("security", [""])[0] != "reality":
            return False
        if q.get("flow", [""])[0] not in ("xtls-rprx-vision",):
            return False
        if q.get("fp", [""])[0].lower() not in REAL_FPS:
            return False
        if not _sni_is_real_domain(q, parsed.hostname or ""):
            return False
        return True
    except Exception:
        return False

def is_hy2_proven(uri):
    """Hysteria2 node: TLS, SNI present. No real-SNI/fp requirement."""
    try:
        if not (uri.startswith("hysteria2://") or uri.startswith("hy2://")):
            return False
        parsed = urllib.parse.urlparse(uri)
        q = urllib.parse.parse_qs(parsed.query)
        sni = q.get("sni", [""])[0]
        return bool(sni and sni != "off")
    except Exception:
        return False

def is_working_pattern(uri, source_url, code):
    """True only for phone-proven patterns for that country."""
    if is_vless_reality_proven(uri):
        return True
    if is_hy2_proven(uri):
        return True
    # SS19999 ONLY from CyberShield gist (US family proven; Radikal
    # SS19999 does NOT connect, despite the same port/cipher).
    if code == "us" and "cybershield" in (source_url or "").lower() and is_ss_19999(uri):
        return True
    return False

def pattern_tier(uri, source_url, code):
    if is_vless_reality_proven(uri):
        return 0
    if is_hy2_proven(uri):
        return 1
    return 2  # CyberShield SS19999 (US only)

COUNTRY_KEYWORDS = {
    "sg": ["singapore", "🇸🇬", "新加坡"],
    "jp": ["japan", "🇯🇵", "日本", "tokyo", "osaka"],
    "us": ["united states", "🇺🇸", "美国", "usa"],
}


def node_country_name(uri):
    """Display name from the URI fragment, URL-decoded, lowercased.
    unquote_plus is needed because some lists encode spaces as '+'."""
    if "#" not in uri:
        return ""
    return urllib.parse.unquote_plus(uri.split("#", 1)[1]).lower()


def matches_country(uri, code):
    name = node_country_name(uri)
    if not name:
        return False
    return any(kw in name for kw in COUNTRY_KEYWORDS[code])
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
            return [l.strip() for l in decoded.splitlines()
                    if l.strip() and not l.strip().startswith("#") and "://" in l]
    except Exception:
        pass
    return [l.strip() for l in text.splitlines()
            if l.strip() and not l.strip().startswith("#") and "://" in l]


def b64decode_padded(s):
    s = s.strip().replace("-", "+").replace("_", "/")
    return base64.b64decode(s + "=" * (-len(s) % 4))


def node_identity(uri):
    """Server identity for dedupe and blocklist: (scheme, credential,
    host, port). Query params and fragment are IGNORED on purpose.
    The old exact-URI key failed twice: (1) the same server+UUID
    reappeared 7-10 times with trivially different query params
    (param order, extra path/encryption flags), wasting most of the
    10 output slots on one server; (2) a blocklisted node came back
    in sg.txt with the same params in a different ORDER, so the
    string comparison missed it (2026-11-05: 206.206.76.75 was in
    blocklist.txt yet back in the output). Identity dedupe collapses
    those variants to one entry, and identity blocklisting catches
    them no matter how the params are ordered. Distinct UUIDs /
    passwords on a shared host/port stay distinct, as before.
    Returns None if the URI has no scheme/host information at all.
    """
    try:
        if "://" not in uri:
            return None
        scheme, rest = uri.split("://", 1)
        body = rest.split("#", 1)[0].split("?", 1)[0]
        userinfo, _, hostport = body.rpartition("@")
        parsed = urllib.parse.urlparse(f"//{hostport}")
        if not parsed.hostname or not parsed.port:
            return None
        return (scheme.lower(), userinfo, parsed.hostname.lower(), parsed.port)
    except Exception:
        return None


def node_key(uri):
    """Kept for compatibility: exact URI without fragment. Prefer
    node_identity() for dedupe/blocklist (see above)."""
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


def load_blocklist():
    """Server identities Hsu reported n/a / not connecting from
    Myanmar. Engine can never know Myanmar n/a from a GitHub runner
    - only Hsu's phone can - so these are excluded here and never
    come back in a later run. Each line is parsed with
    node_identity(), so an old full-URI entry and a short
    scheme://credential@host:port entry both block every query
    variant of that server."""
    blocked = set()
    for path in ("engine/blocklist.txt", "blocklist.txt"):
        try:
            with open(path) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        ident = node_identity(line)
                        if ident is not None:
                            blocked.add(ident)
        except FileNotFoundError:
            pass
    return blocked

BLOCKLIST = load_blocklist()

def run_country(code):
    seen = set()
    unique = []  # list of (uri, source_url)
    for url, already_specific in SOURCES[code]:
        try:
            print(f"[{code}] fetching {url}", flush=True)
            raw = fetch_text(url)
            lines = decode_subscription(raw)
        except Exception as e:
            print(f"[{code}] source failed, skipping: {url} ({e})", flush=True)
            continue
        kept = 0
        for line in lines:
            if not already_specific and not matches_country(line, code):
                continue
            key = node_identity(line)
            if key is None or key in seen:
                continue
            # v4 rule (Hsu: I will not filter myself any more - engine
            # must send only truly working nodes to the app): drop
            # every node that does not match a phone-proven pattern
            # BEFORE live-testing, so MAX_TEST is not wasted on them.
            if not is_working_pattern(line, url, code):
                continue
            if key in BLOCKLIST:
                continue
            seen.add(key)
            unique.append((line, url))
            kept += 1
        print(f"[{code}] {url} -> {len(lines)} lines, {kept} pattern-kept", flush=True)
    # CyberShield gist nodes are phone-proven from Myanmar as a family
    # (Hsu: SS19999 connects, SG VLESS Reality matches the proven
    # pattern), but the GitHub probe cannot judge Myanmar connectivity
    # - a CyberShield SG node passes this filter yet fails the probe,
    # while GitHub-live nodes turn out Myanmar-dead (2026-11-05: all
    # 10 SG nodes GitHub-live, all Myanmar-dead). So a CyberShield
    # node is kept even when the probe fails it; within its tier it
    # ranks FIRST (family phone-proven beats GitHub-only ms), and the
    # app's own in-app ping check then ranks it and drops any n/a,
    # as Hsu instructed. Blocklist still applies (see above).
    # Test pattern-proven nodes in tier order (VLESS Reality, then
    # Hysteria2, then CyberShield SS19999), otherwise the best tier
    # sits past MAX_TEST in a 1000+ node list.
    unique.sort(key=lambda t: pattern_tier(t[0], t[1], code))
    print(f"[{code}] pattern-unique={len(unique)}", flush=True)
    candidates = [u for u, _src in unique[:MAX_TEST]]
    print(f"[{code}] testing={len(candidates)}", flush=True)

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
    print(f"[{code}] live={len(results)} skipped={skipped} "
          f"best={results[0][0]:.0f}ms" if results else f"[{code}] live=0 skipped={skipped}", flush=True)
    # Final rank: phone-proven tier first, then CyberShield first
    # within the tier, then GitHub ms (probe-failed CyberShield last
    # within the tier). Ms alone does not prove Myanmar connectivity
    # or line quality.
    src_by_uri = {u: src for u, src in unique}
    live_uris = {uri for _ms, uri in results}
    cyber_dead = [(None, uri) for uri in candidates
                  if uri not in live_uris
                  and "cybershield" in src_by_uri.get(uri, "").lower()]
    combined = results + cyber_dead

    def order_key(item):
        ms, uri = item
        src = src_by_uri.get(uri, "")
        return (pattern_tier(uri, src, code),
                0 if "cybershield" in src.lower() else 1,
                ms if ms is not None else float("inf"))

    combined.sort(key=order_key)
    out = [uri for _ms, uri in combined]
    print(f"[{code}] output={len(out)} (live={len(results)} "
          f"+ cybershield-kept={len(cyber_dead)})", flush=True)
    return out[:TOP_N]


def main():
    os.makedirs("output", exist_ok=True)
    for code in SOURCES:
        try:
            top = run_country(code)
        except Exception as e:
            print(f"[{code}] FAILED, keeping previous output: {e}", flush=True)
            continue
        if not top:
            print(f"[{code}] no live nodes, keeping previous output", flush=True)
            continue
        # Write base64-encoded subscription, the standard v2ray format
        # used by Au1rxx and CyberShield. Plain lines FAIL in v2rayNG
        # on Hsu's phone (empty list + Failure, 2026-11-04 23:49),
        # while base64 imports fine there and also works in NekoBox.
        encoded = base64.b64encode(("\n".join(top) + "\n").encode()).decode()
        with open(f"output/{code}.txt", "w") as f:
            f.write(encoded + "\n")
        print(f"[{code}] wrote output/{code}.txt with {len(top)} nodes (base64)", flush=True)


if __name__ == "__main__":
    main()
