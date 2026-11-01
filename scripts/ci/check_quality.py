"""Summarize Java quality warnings; fail only for invalid or failed analysis."""

import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def summarize():
    counts = {}
    for tool, report in (("checkstyle", "checkstyle-result.xml"), ("pmd", "pmd.xml")):
        root = ET.parse(ROOT / "target" / report).getroot()
        # PMD uses an XML namespace; normalize tags before validation.
        for node in root.iter():
            node.tag = node.tag.rsplit("}", 1)[-1]
        if root.tag != tool:
            raise ValueError(f"Invalid {tool} report")
        if (tool == "pmd" and root.findall(".//error")) or root.findall(".//configerror"):
            raise ValueError(f"{tool} processing/configuration errors")
        count = 0
        for file in root.findall("file"):
            path = Path(file.attrib["name"])
            path = path if path.is_absolute() else ROOT / path
            path.resolve().relative_to(ROOT)
            for finding in file:
                if finding.tag not in ("error", "violation"):
                    raise ValueError(f"Unexpected {tool} finding type: {finding.tag}")
                rule = finding.attrib.get("source", finding.attrib.get("rule"))
                line = int(finding.attrib.get("line", finding.attrib.get("beginline", "0")))
                if not rule or line < 1 or "Exception" in rule:
                    raise ValueError(f"Invalid/processing {tool} finding")
                count += 1
        counts[tool] = count
    return counts


def main():
    counts = summarize()
    rows = ["## Java quality report", "", "Rule findings are warnings; see java-static-analysis artifacts.",
            "", "| Tool | Findings |", "| --- | ---: |"]
    for tool, count in counts.items():
        print(f"::warning title={tool} quality findings::{count} findings; see uploaded report"
              if count else f"{tool}: 0 findings")
        rows.append(f"| {tool} | {count} |")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        print(f"Quality gate failed: {error}", file=sys.stderr)
        sys.exit(1)
