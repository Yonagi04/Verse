"""Fail closed on CodeQL high/critical findings and analysis failures."""

import argparse
import json
import math
from pathlib import Path
import sys


def resolve_rule(tool, result):
    reference = result.get("rule", {})
    component_reference = reference.get("toolComponent")
    components = [tool.get("driver", {}), *tool.get("extensions", [])]
    if component_reference is not None:
        index = component_reference.get("index")
        if index is not None:
            extensions = tool.get("extensions", [])
            if type(index) is not int or not 0 <= index < len(extensions):
                raise ValueError("Finding has invalid tool component index")
            components = [extensions[index]]
            if any(components[0].get(key) != component_reference[key]
                   for key in ("name", "guid") if key in component_reference):
                raise ValueError("Finding has inconsistent tool component reference")
        else:
            keys = [key for key in ("name", "guid") if key in component_reference]
            components = [component for component in components if keys and all(
                component.get(key) == component_reference[key] for key in keys)]
            if len(components) != 1:
                raise ValueError("Finding has unknown or ambiguous tool component")

    rule_id = result.get("ruleId") or reference.get("id")
    index = reference.get("index", result.get("ruleIndex"))
    if component_reference is not None or not rule_id:
        # SARIF 的规则索引属于组件内部，不能把各查询包的规则拼接后索引。
        component = components[0] if component_reference is not None else tool.get("driver", {})
        rules = component.get("rules", [])
        if index is not None:
            if type(index) is not int or not 0 <= index < len(rules):
                raise ValueError("Finding has invalid rule index")
            rule = rules[index]
            if rule_id and rule.get("id") != rule_id:
                raise ValueError("Finding has inconsistent rule reference")
            return rule

    matches = [rule for component in components for rule in component.get("rules", [])
               if rule_id and rule.get("id") == rule_id]
    if len(matches) != 1:
        raise ValueError("Finding has no known unique rule")
    return matches[0]


def check(path):
    report = json.loads(Path(path).read_text(encoding="utf-8"))
    if report.get("version") != "2.1.0" or not report.get("runs"):
        raise ValueError("Missing or unsupported SARIF runs")
    blocked = []
    for run in report["runs"]:
        tool = run.get("tool", {})
        driver = tool.get("driver", {})
        components = [driver, *tool.get("extensions", [])]
        if driver.get("name") != "CodeQL" or not any(component.get("rules") for component in components):
            raise ValueError("Missing CodeQL rule inventory")
        if any(item.get("executionSuccessful") is False for item in run.get("invocations", [])):
            raise ValueError("CodeQL invocation failed")
        for result in run.get("results", []):
            if result.get("kind") in ("pass", "notApplicable", "informational"):
                continue
            rule = resolve_rule(tool, result)
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
