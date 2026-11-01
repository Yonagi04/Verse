"""Check security false-green cases without Docker or network credentials."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import check_sarif


class SecurityGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_high_findings_block_even_when_report_level_is_warning(self):
        for score in ("7.0", "9.8", "nan"):
            with self.subTest(score=score):
                report = {"version": "2.1.0", "runs": [{"tool": {"driver": {
                    "name": "CodeQL", "rules": [{"id": "security", "properties": {"security-severity": score}}]}},
                    "results": [{"ruleId": "security", "level": "warning"}]}]}
                path = self.root / "report.sarif"
                path.write_text(json.dumps(report), encoding="utf-8")
                with self.assertRaises(ValueError):
                    check_sarif.check(path)

    def test_analysis_error_missing_inventory_and_unscored_error_block(self):
        driver = {"name": "CodeQL", "rules": [{"id": "error"}]}
        for run in ({}, {"tool": {"driver": driver}, "invocations": [{"executionSuccessful": False}]},
                    {"tool": {"driver": driver}, "results": [{"ruleId": "error", "level": "error"}]}):
            path = self.root / "report.sarif"
            path.write_text(json.dumps({"version": "2.1.0", "runs": [run]}), encoding="utf-8")
            with self.assertRaises(ValueError):
                check_sarif.check(path)
        with self.assertRaises(OSError):
            check_sarif.check(self.root / "missing.sarif")

    def test_successful_analysis_without_findings_passes(self):
        path = self.root / "report.sarif"
        path.write_text(json.dumps({"version": "2.1.0", "runs": [{"tool": {"driver": {
            "name": "CodeQL", "rules": [{"id": "rule"}]}}, "results": [],
            "invocations": [{"executionSuccessful": True}]}]}), encoding="utf-8")
        check_sarif.check(path)

    def test_grouped_rules_resolve_severity_in_the_referenced_pack(self):
        tool = {"driver": {"name": "CodeQL", "rules": []}, "extensions": [
            {"name": "low-pack", "rules": [{"id": "low", "properties": {"security-severity": "4.0"}}]},
            {"name": "high-pack", "rules": [{"id": "high", "properties": {"security-severity": "8.0"}}]},
        ]}
        cases = [([], False), ([{"ruleId": "low"}], False),
                 ([{"ruleId": "high", "level": "warning"}], True),
                 ([{"rule": {"index": 0, "toolComponent": {"index": 0}}}], False),
                 ([{"rule": {"index": 0, "toolComponent": {"index": 1}}}], True),
                 ([{"rule": {"id": "high", "toolComponent": {"name": "high-pack"}}}], True)]
        for results, blocked in cases:
            with self.subTest(results=results):
                path = self.root / "report.sarif"
                path.write_text(json.dumps({"version": "2.1.0", "runs": [
                    {"tool": tool, "results": results}]}), encoding="utf-8")
                if blocked:
                    with self.assertRaisesRegex(ValueError, "Blocking CodeQL findings: high"):
                        check_sarif.check(path)
                else:
                    check_sarif.check(path)

    def test_grouped_rules_do_not_allow_unknown_or_invalid_references(self):
        tool = {"driver": {"name": "CodeQL"}, "extensions": [
            {"name": "pack", "rules": [{"id": "known"}]}]}
        references = [{"ruleId": "unknown"}, {"ruleIndex": 0},
                      {"rule": {"index": -1, "toolComponent": {"index": 0}}},
                      {"rule": {"index": 1, "toolComponent": {"index": 0}}},
                      {"rule": {"index": 0, "toolComponent": {"index": -1}}},
                      {"rule": {"index": 0, "toolComponent": {"index": 1}}},
                      {"rule": {"index": 0, "toolComponent": {"name": "missing"}}},
                      {"rule": {"id": "unknown", "index": 0, "toolComponent": {"index": 0}}}]
        for result in references:
            with self.subTest(result=result):
                path = self.root / "report.sarif"
                path.write_text(json.dumps({"version": "2.1.0", "runs": [
                    {"tool": tool, "results": [result]}]}), encoding="utf-8")
                with self.assertRaises(ValueError):
                    check_sarif.check(path)

    def test_empty_grouped_inventory_still_blocks(self):
        path = self.root / "report.sarif"
        path.write_text(json.dumps({"version": "2.1.0", "runs": [{"tool": {
            "driver": {"name": "CodeQL"}, "extensions": [{"rules": []}]}}]}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing CodeQL rule inventory"):
            check_sarif.check(path)

    def test_dependency_workflow_blocks_high_critical_and_empty_scans(self):
        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/security.yml"
        script = workflow.read_text(encoding="utf-8").split("python3 - <<'PY'\n")[1].split("\n          PY")[0]
        script = textwrap.dedent(script)
        for severity in (None, "LOW", "MEDIUM", "UNKNOWN", "HIGH", "CRITICAL", "EMPTY"):
            with self.subTest(severity=severity):
                result = {"Packages": [{"Name": "example"}], "Vulnerabilities": []}
                if severity == "EMPTY":
                    result["Packages"] = []
                elif severity:
                    result["Vulnerabilities"] = [{"Severity": severity}]
                output = io.StringIO()
                with patch.object(Path, "read_text", return_value=json.dumps({"Results": [result]})), \
                        contextlib.redirect_stdout(output):
                    if severity in ("HIGH", "CRITICAL", "EMPTY"):
                        with self.assertRaises(SystemExit):
                            exec(script, {})
                    else:
                        exec(script, {})
                        self.assertEqual(bool(severity), "::warning" in output.getvalue())


if __name__ == "__main__":
    unittest.main()
