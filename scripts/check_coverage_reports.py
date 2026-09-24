#!/usr/bin/env python3
"""Fail closed when a required JaCoCo XML report or critical class is missing.

Maven JaCoCo CHECK stays authoritative; this independent guard also records the
actual measured ratios and prevents an empty/missing report from passing CI.
"""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "mail-api": {"bundle": {"LINE": .50}},
    "mail-application": {"bundle": {"LINE": .60}},
    "mail-worker": {"bundle": {"LINE": .65}},
    "mail-provider-smtp": {
        "classes": {"com/yeremitech/mailplatform/provider/smtp/SmtpMailProvider":
                    {"LINE": .70, "BRANCH": .55}}
    },
    "mail-infrastructure": {
        "classes": {"com/yeremitech/mailplatform/infrastructure/adapter/JdbcAttachmentStorageAdapter":
                    {"LINE": .65, "BRANCH": .50}}
    },
}


def counters(node):
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed")))
            for c in node.findall("counter")}


def ratio(values, key):
    covered, missed = values.get(key, (0, 0))
    if covered + missed == 0:
        raise AssertionError(f"missing/empty {key} coverage counter")
    return covered / (covered + missed)


def validate_report(module, xml_path, policy):
    if not xml_path.is_file():
        raise AssertionError(f"missing JaCoCo report: {xml_path}")
    tree = ET.parse(xml_path).getroot()
    if tree.tag != "report":
        raise AssertionError(f"invalid JaCoCo report: {xml_path}")
    results = {}
    for name, minimum in policy.get("bundle", {}).items():
        actual = ratio(counters(tree), name)
        results[f"bundle/{name}"] = round(actual, 4)
        if actual < minimum:
            raise AssertionError(f"{module} {name}: {actual:.1%} < {minimum:.1%}")
    classes = {cl.get("name"): cl for pkg in tree.findall("package")
               for cl in pkg.findall("class")}
    for class_name, limits in policy.get("classes", {}).items():
        if class_name not in classes:
            raise AssertionError(f"{module}: missing critical class {class_name}")
        for name, minimum in limits.items():
            actual = ratio(counters(classes[class_name]), name)
            results[f"{class_name}/{name}"] = round(actual, 4)
            if actual < minimum:
                raise AssertionError(f"{module} {class_name} {name}: {actual:.1%} < {minimum:.1%}")
    return results


def main():
    results = {}
    for module, policy in REQUIRED.items():
        results[module] = validate_report(module, ROOT / module / "target/site/jacoco/jacoco.xml", policy)
        print(f"PASS {module}: {results[module]}")
    output = ROOT / "target/coverage-evidence.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n")
    print(f"Coverage evidence saved to {output}")


if __name__ == "__main__":
    main()
