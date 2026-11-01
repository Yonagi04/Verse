"""Exercise CI false-green failure modes without running databases or business code."""

import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_quality
import check_test_reports


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = self.root / "target/surefire-reports"
        self.reports.mkdir(parents=True)
        source = self.root / "src/test/java/example"
        source.mkdir(parents=True)
        (source / "RuleTest.java").write_text(
            'package example;\nclass RuleTest {\n @Test void checksRule() {}\n}', encoding="utf-8")
        (source / "DatabaseTest.java").write_text(
            'package example;\n@Tag("integration")\nclass DatabaseTest {\n @Test void checksDb() {}\n}',
            encoding="utf-8")
        self.patch = patch.object(check_test_reports, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def report(self, *, name="example.RuleTest", cases='<testcase name="checksRule"/>',
               tests=1, errors=0, failures=0):
        (self.reports / "TEST-rule.xml").write_text(
            f'<testsuite name="{name}" tests="{tests}" errors="{errors}" failures="{failures}">'
            f'{cases}</testsuite>', encoding="utf-8")

    def test_complete_suite_passes(self):
        self.report()
        with contextlib.redirect_stdout(io.StringIO()):
            check_test_reports.verify("unit")

    def test_missing_report_fails(self):
        with self.assertRaisesRegex(ValueError, "No XML"):
            check_test_reports.verify("unit")

    def test_wrong_suite_and_zero_tests_fail(self):
        self.report(name="example.DatabaseTest")
        with self.assertRaisesRegex(ValueError, "wrong-suite"):
            check_test_reports.verify("unit")
        self.report(cases="", tests=0)
        with self.assertRaisesRegex(ValueError, "Empty"):
            check_test_reports.verify("unit")

    def test_failure_error_and_skip_fail(self):
        for child in ("failure", "error", "skipped"):
            with self.subTest(child=child):
                self.report(cases=f'<testcase name="checksRule"><{child}/></testcase>')
                with self.assertRaises(ValueError):
                    check_test_reports.verify("unit")

    def test_new_database_dependency_in_unit_fails(self):
        path = self.root / "src/test/java/example/RuleTest.java"
        path.write_text(path.read_text() + "\n// MySqlTestDatabase.create()", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Infrastructure dependency"):
            check_test_reports.inventory()



class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "target").mkdir()
        (self.root / "Rule.java").write_text('class Rule { }\n', encoding="utf-8")
        current = patch.object(check_quality, "ROOT", self.root)
        current.start()
        self.addCleanup(current.stop)
        self.report()

    def report(self, count=1):
        finding = '<error line="1" source="NeedBraces" message="Use braces"/>'
        (self.root / "target/checkstyle-result.xml").write_text(
            '<checkstyle><file name="Rule.java">' + finding * count + '</file></checkstyle>',
            encoding="utf-8")
        (self.root / "target/pmd.xml").write_text('<pmd/>', encoding="utf-8")

    def run_gate(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            check_quality.main()
        return output.getvalue()

    def test_findings_warn_without_blocking_even_when_count_increases(self):
        self.assertIn("::warning", self.run_gate())
        self.report(count=2)
        self.assertIn("2 findings", self.run_gate())

    def test_summary_contains_counts_and_zero_findings_pass(self):
        summary = self.root / "summary.md"
        with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(summary)}):
            self.run_gate()
        self.assertIn("| checkstyle | 1 |", summary.read_text(encoding="utf-8"))
        self.report(count=0)
        self.assertNotIn("::warning", self.run_gate())

    def test_missing_or_processing_error_report_fails(self):
        (self.root / "target/pmd.xml").unlink()
        with self.assertRaises(FileNotFoundError):
            check_quality.summarize()
        (self.root / "target/pmd.xml").write_text(
            '<pmd><error filename="Rule.java" msg="Parse failure"/></pmd>', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "processing"):
            check_quality.summarize()
        (self.root / "target/pmd.xml").write_text('<pmd>', encoding="utf-8")
        with self.assertRaises(check_quality.ET.ParseError):
            check_quality.summarize()

    def test_external_path_is_rejected(self):
        (self.root / "target/checkstyle-result.xml").write_text(
            '<checkstyle><file name="../outside.java"/></checkstyle>', encoding="utf-8")
        with self.assertRaises(ValueError):
            check_quality.summarize()


if __name__ == "__main__":
    unittest.main()
