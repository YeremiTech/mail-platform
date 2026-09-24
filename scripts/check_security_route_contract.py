#!/usr/bin/env python3
"""Fail CI when a new REST operation lacks an explicit Spring Security rule.

This static guard complements real MockMvc ACL tests; it never certifies runtime
security or the correctness of Spring's matcher implementation.
"""
from __future__ import annotations

from fnmatch import fnmatchcase
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CONTROLLERS = ROOT / "mail-api/src/main/java/com/yeremitech/mailplatform/api/controller"
SECURITY = ROOT / "mail-api/src/main/java/com/yeremitech/mailplatform/api/security/SecurityConfiguration.java"
MAPPINGS = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?", re.DOTALL)
RULES = re.compile(
    r"a\.requestMatchers\((.*?)\)\s*\.(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|permitAll|denyAll)\s*\(",
    re.DOTALL,
)


def routes(source: str):
    base_match = re.search(r'@RequestMapping\s*\(\s*"([^"]+)"', source)
    if not base_match:
        raise AssertionError("controller is missing a class-level @RequestMapping")
    base = base_match.group(1).rstrip("/")
    found = []
    for match in MAPPINGS.finditer(source):
        params = match.group(2) or ""
        explicit = re.search(r'(?:path|value)\s*=\s*"([^"]+)"', params)
        shorthand = re.search(r'^\s*"([^"]+)"', params)
        suffix = (explicit or shorthand).group(1) if (explicit or shorthand) else ""
        found.append((match.group(1).upper(), base + ("/" + suffix.lstrip("/") if suffix else "")))
    if not found:
        raise AssertionError("controller exposes no supported HTTP mapping")
    return found


def explicit_rules(source: str):
    found = []
    for match in RULES.finditer(source):
        params, action = match.group(1), match.group(2)
        method = re.search(r'HttpMethod\.([A-Z]+)', params)
        for path in re.findall(r'"(/[^"\n]+)"', params):
            # denyAll is deliberately excluded: it is the default, not proof
            # that a new endpoint has been explicitly reviewed.
            if action != "denyAll":
                found.append((method.group(1) if method else None, path, action))
    if not re.search(r'a\.requestMatchers\("/api/\*\*"\)\.denyAll\(\)', source):
        raise AssertionError("/api/** must deny all requests not explicitly reviewed")
    return found


def is_covered(method: str, path: str, rules):
    for secured_method, secured_path, action in rules:
        if secured_method not in (None, method):
            continue
        if secured_path.endswith("/**"):
            base = secured_path[:-3]
            if path == base or path.startswith(base + "/"):
                return True
        elif fnmatchcase(path, secured_path.replace("**", "*")):
            return True
    return False


def verify(controllers=CONTROLLERS, security=SECURITY):
    source = security.read_text(encoding="utf-8")
    rules = explicit_rules(source)
    endpoints = []
    for controller in sorted(controllers.glob("*Controller.java")):
        for method, path in routes(controller.read_text(encoding="utf-8")):
            endpoints.append((controller.name, method, path))
    if not endpoints:
        raise AssertionError("no REST controllers discovered")
    uncovered = [f"{name}: {method} {path}" for name, method, path in endpoints
                 if not is_covered(method, path, rules)]
    if uncovered:
        raise AssertionError("REST operations without reviewed security rules:\n" + "\n".join(uncovered))
    return len(endpoints)


if __name__ == "__main__":
    print(f"PASS: {verify()} REST method/path declarations have explicit security rules and /api/** denies by default")
