"""Verify suite ownership and reject missing, failed, or unexpectedly skipped tests."""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SIMULATOR = "com.yonagi.verse.common.messaging.LocalMessageSimulatorIntegrationTest"
OPTIONAL = {
    (SIMULATOR, "sendsAndReadsSmsDevMessage"),
    (SIMULATOR, "sendsAndFindsMailpitMessage"),
}
TEST = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


def inventory():
    suites = {"unit": set(), "integration": set()}
    paths = list((ROOT / "src/test/java").rglob("*.java"))
    for path in paths:
        source = path.read_text(encoding="utf-8")
        if not TEST.search(source):
            continue
        package = re.search(r"^package\s+([\w.]+);", source, re.M)
        if not package or not path.stem.endswith("Test"):
            raise ValueError(f"Unsupported test discovery convention: {path}")
        declaration = re.search(r"^(?:public\s+)?(?:final\s+)?class\s+" + path.stem + r"\b", source, re.M)
        if not declaration:
            raise ValueError(f"Missing test class declaration: {path}")
        prefix = source[:declaration.start()]
        integration = '@Tag("integration")' in prefix
        if '@Tag("integration")' in source[declaration.end():] or "@Nested" in source:
            raise ValueError(f"Review inventory before introducing nested/method-level grouping: {path}")
        if not integration and any(marker in source for marker in (
                "MySqlTestDatabase", "Redisson.create", "VERSE_BUDGET_TEST_REDIS")):
            raise ValueError(f"Infrastructure dependency in unit suite: {path}")
        suite = "integration" if integration else "unit"
        suites[suite].add(package[1] + "." + path.stem)
    if not suites["unit"] or not suites["integration"]:
        raise ValueError("Both test suites must be nonempty")
    return suites


def verify(suite):
    expected = inventory()[suite]
    folders = {"unit": "surefire-reports", "integration": "failsafe-reports"}
    folder = ROOT / "target" / folders[suite]
    reports = [ET.parse(path).getroot() for path in folder.glob("TEST-*.xml")]
    if not reports:
        raise ValueError(f"No XML reports in {folder}")
    actual = set()
    total = skipped = 0
    for report in reports:
        name = report.attrib["name"]
        if name in actual or name not in expected:
            raise ValueError(f"Duplicate or wrong-suite report: {name}")
        actual.add(name)
        cases = report.findall("testcase")
        if not cases or int(report.attrib["tests"]) != len(cases):
            raise ValueError(f"Empty or inconsistent report: {name}")
        if int(report.attrib.get("errors", 0)) or int(report.attrib.get("failures", 0)):
            raise ValueError(f"Failed suite: {name}")
        for case in cases:
            total += 1
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed test: {name}.{case.attrib['name']}")
            if case.find("skipped") is not None:
                skipped += 1
                if (name, case.attrib["name"]) not in OPTIONAL:
                    raise ValueError(f"Unexpected skip: {name}.{case.attrib['name']}")
    if missing := expected - actual:
        raise ValueError(f"Missing test classes: {sorted(missing)}")
    print(f"{suite}: {len(actual)} classes, {total} tests, {skipped} allowed skips")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", choices=("unit", "integration", "inventory"))
    args = parser.parse_args()
    try:
        if args.suite == "inventory":
            for suite, names in inventory().items():
                print(f"{suite}: {len(names)} classes")
                for name in sorted(names):
                    print(f"  {name}")
        else:
            verify(args.suite)
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        print(f"Test report gate failed: {error}", file=sys.stderr)
        sys.exit(1)
