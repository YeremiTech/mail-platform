#!/usr/bin/env python3
"""Zero-dependency CI guard against infrastructure leaking into application/domain modules."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWED = {
    "mail-domain": ("java.", "javax."),
    "mail-application": ("java.", "javax.", "com.yeremitech.mailplatform.domain.",
                         "com.yeremitech.mailplatform.application."),
}
failures = []
count = 0
for module, allowed in ALLOWED.items():
    for source in (ROOT / module / "src/main/java").rglob("*.java"):
        count += 1
        for match in re.finditer(r"^\s*import\s+(?:static\s+)?([^;\s]+)\s*;", source.read_text(), re.M):
            symbol = match.group(1)
            if not any(symbol.startswith(prefix) for prefix in allowed):
                failures.append(f"{source.relative_to(ROOT)}: forbidden import {symbol}")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print(f"PASS: checked {count} domain/application sources against module dependency rules")
