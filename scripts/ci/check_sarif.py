"""Fail closed on CodeQL high/critical findings and analysis failures."""

import argparse
import json
import math
from pathlib import Path
import sys


def check(path):
    report = json.loads(Path(path).read_text(encoding="utf-8"))
    if report.get("version") != "2.1.0" or not report.get("runs"):
        raise ValueError("Missing or unsupported SARIF runs")
    blocked = []
    for run in report["runs"]:
        driver = run.get("tool", {}).get("driver", {})
        rules = driver.get("rules", [])
        if driver.get("name") != "CodeQL" or not rules:
            raise ValueError("Missing CodeQL rule inventory")
        if any(item.get("executionSuccessful") is False for item in run.get("invocations", [])):
            raise ValueError("CodeQL invocation failed")
        by_id = {rule["id"]: rule for rule in rules}
        for result in run.get("results", []):
            if result.get("kind") in ("pass", "notApplicable", "informational"):
                continue
            rule_id = result.get("ruleId") or result.get("rule", {}).get("id")
            index = result.get("ruleIndex", result.get("rule", {}).get("index"))
            rule = by_id.get(rule_id) if rule_id else rules[index] if isinstance(index, int) else None
            if not rule:
                raise ValueError("Finding has no known rule")
            score = rule.get("properties", {}).get("security-severity")
            if score is not None and (not math.isfinite(float(score)) or not 0 <= float(score) <= 10):
                raise ValueError("Invalid security severity")
            level = result.get("level", rule.get("defaultConfiguration", {}).get("level", "warning"))
            if (score is not None and float(score) >= 7) or (score is None and level == "error"):
                blocked.append(rule["id"])
    if blocked:
        raise ValueError("Blocking CodeQL findings: " + ", ".join(sorted(set(blocked))))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        files = list(args.path.rglob("*.sarif")) if args.path.is_dir() else [args.path]
        if not files:
            raise ValueError("No SARIF report")
        for file in files:
            check(file)
        print(f"CodeQL gate passed: {len(files)} reports")
    except (ValueError, KeyError, IndexError, OSError) as error:
        print(f"CodeQL gate failed: {error}", file=sys.stderr)
        sys.exit(1)
