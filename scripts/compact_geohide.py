"""Build the GeoHide EU NextDNS snapshot. Run from the repository root."""
import hashlib
import ipaddress
import re
import sys
from collections import defaultdict
from pathlib import Path
from urllib.request import urlopen

SOURCE = "https://raw.githubusercontent.com/Internet-Helper/GeoHideDNS/refs/heads/main/hosts/eu/hosts"
OUTPUT = Path("hosts/geohide-eu.hosts")
BLOCK_IPS = {"0.0.0.0", "127.0.0.1", "::1"}
LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

def compact(lines):
    raw = defaultdict(list)
    first = {}
    for line in lines:
        data = line.partition("#")[0].lower().split()
        if not data:
            continue
        ip, name = data
        ipaddress.ip_address(ip)
        if ip in BLOCK_IPS:
            continue
        if len(name) > 253 or "." not in name or not all(LABEL.fullmatch(label) for label in name.split(".")):
            raise ValueError(f"Invalid hostname {name}")
        raw[name].append(ip)
        first.setdefault(name.removeprefix("www."), ip)

    groups = defaultdict(list)
    for name in raw:
        labels = name.removeprefix("www.").split(".")
        root = next(suffix for i in reversed(range(len(labels))) if (suffix := ".".join(labels[i:])) in first)
        groups[root].append(name)

    active = {}
    retired = set()
    for root, names in groups.items():
        common = set.intersection(*(set(raw[name]) for name in names))
        if not common:
            raise ValueError(f"No shared GeoHide IP for {root} and its listed descendants")
        # Keep DnsConf's first IP for the root whenever every listed name accepts it.
        candidate_order = [first[root], *raw[names[0]]]
        active[root] = next(ip for ip in candidate_order if ip in common)
        for name in names:
            old_name = name.removeprefix("www.")
            if old_name != root or first[old_name] != active[root]:
                retired.add((old_name, first[old_name]))
    if not 0 < len(active) <= 500:
        raise ValueError(f"Snapshot requires {len(active)} rewrites (limit 500)")
    return active, retired, groups

def update(data):
    lines = data.decode("utf-8-sig").splitlines()
    if "# Регион серверов: EU" not in lines or lines[-1].strip() != "### geohide.ru: end hosts file":
        raise ValueError("Expected a complete GeoHide EU hosts file")
    active, retired, groups = compact(lines)
    # Keep previous managed pairs for profiles that miss one or more updates.
    if OUTPUT.exists():
        for line in OUTPUT.read_text(encoding="utf-8").splitlines():
            if line.startswith("# retire "):
                ip, name = line.removeprefix("# retire ").split()
            elif line and not line.startswith("#"):
                ip, name = line.partition("#")[0].split()
            else:
                continue
            retired.add((name, ip))
    retired.difference_update(active.items())
    content = [
        f"# Source: {SOURCE}",
        f"# SHA-256: {hashlib.sha256(data).hexdigest()}",
        "# NextDNS rewrites (one common upstream IP per subtree)",
    ]
    for name, ip in sorted(active.items()):
        children = sorted(set(groups[name]) - {name})
        suffix = " # covers " + " ".join(children) if children else ""
        content.append(f"{ip} {name}{suffix}")
    content.extend(["", "# Previous DnsConf pairs eligible for removal during sync"])
    content.extend(f"# retire {ip} {name}" for name, ip in sorted(retired))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(("\n".join(content) + "\n").encode())
    print(f"{len(active)} rewrites, {len(retired)} superseded pairs")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        update(Path(sys.argv[1]).read_bytes())
    else:
        with urlopen(SOURCE, timeout=30) as response:
            update(response.read())
