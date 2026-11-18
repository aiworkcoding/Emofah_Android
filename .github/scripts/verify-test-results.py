#!/usr/bin/env python3
"""Validates Gradle JUnit XML test reports and summarizes suite/case counts.

Exit codes:
  0 - Valid test reports found, total_tests > 0, failures == 0, errors == 0.
  1 - Test execution failure (one or more tests failed or errored).
  2 - Missing directory, non-directory path, empty report set, or zero tests executed.
  3 - Malformed XML or invalid JUnit XML structure.
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import List, Set, Tuple


EXIT_OK = 0
EXIT_TEST_FAILURE = 1
EXIT_MISSING_OR_EMPTY = 2
EXIT_MALFORMED_XML = 3


@dataclass
class SuiteStats:
    file_path: str
    suite_name: str
    tests: int
    failures: int
    errors: int
    skipped: int
    failed_cases: List[str]


def _parse_non_negative_int(elem: ET.Element, attr: str, file_path: str, required: bool = False) -> int:
    raw = elem.attrib.get(attr)
    if raw is None:
        if required:
            raise ValueError(f"Missing required attribute '{attr}' on <{elem.tag}> in {file_path}")
        return 0
    try:
        val = int(raw)
    except ValueError as exc:
        raise ValueError(f"Non-integer attribute '{attr}={raw!r}' on <{elem.tag}> in {file_path}") from exc
    if val < 0:
        raise ValueError(f"Negative attribute '{attr}={val}' on <{elem.tag}> in {file_path}")
    return val


def _extract_suites_from_xml(file_path: str) -> List[SuiteStats]:
    try:
        tree = ET.parse(file_path)
    except (ET.ParseError, OSError, UnicodeDecodeError) as exc:
        raise ValueError(f"Malformed XML in {file_path}: {exc}") from exc

    root = tree.getroot()
    if root.tag == "testsuite":
        suite_elements = [root]
    elif root.tag == "testsuites":
        suite_elements = root.findall("testsuite")
        if not suite_elements:
            raise ValueError(f"Empty <testsuites> container with no <testsuite> children in {file_path}")
    else:
        raise ValueError(f"Unexpected XML root tag <{root.tag}> in {file_path}; expected <testsuite> or <testsuites>")

    parsed: List[SuiteStats] = []
    for suite_el in suite_elements:
        suite_name = (suite_el.attrib.get("name") or os.path.basename(file_path)).strip()
        tests = _parse_non_negative_int(suite_el, "tests", file_path, required=True)
        failures = _parse_non_negative_int(suite_el, "failures", file_path, required=False)
        errors = _parse_non_negative_int(suite_el, "errors", file_path, required=False)
        skipped = _parse_non_negative_int(suite_el, "skipped", file_path, required=False)

        failed_cases: List[str] = []
        for tc in suite_el.findall("testcase"):
            tc_class = (tc.attrib.get("classname") or suite_name).strip()
            tc_name = (tc.attrib.get("name") or "<unnamed>").strip()
            if tc.find("failure") is not None or tc.find("error") is not None:
                failed_cases.append(f"{tc_class}#{tc_name}")

        parsed.append(
            SuiteStats(
                file_path=file_path,
                suite_name=suite_name,
                tests=tests,
                failures=failures,
                errors=errors,
                skipped=skipped,
                failed_cases=failed_cases,
            )
        )
    return parsed


def verify_directories(
    directories: List[str],
    github_env_path: str | None = None,
    github_output_path: str | None = None,
) -> int:
    if not directories:
        print("ERROR: [TEST_REPORT_ERROR] At least one test results directory must be provided.", file=sys.stderr)
        return EXIT_MISSING_OR_EMPTY

    seen_dirs: Set[str] = set()
    seen_suite_keys: Set[Tuple[str, str]] = set()
    all_suites: List[SuiteStats] = []

    for raw_dir in directories:
        real_dir = os.path.realpath(raw_dir)
        if real_dir in seen_dirs:
            print(f"INFO: Skipping duplicate directory argument: {raw_dir}", file=sys.stderr)
            continue
        seen_dirs.add(real_dir)

        if not os.path.exists(raw_dir):
            print(f"ERROR: [TEST_REPORT_ERROR] Test results directory does not exist: {raw_dir}", file=sys.stderr)
            return EXIT_MISSING_OR_EMPTY
        if not os.path.isdir(raw_dir):
            print(f"ERROR: [TEST_REPORT_ERROR] Test results path is not a directory: {raw_dir}", file=sys.stderr)
            return EXIT_MISSING_OR_EMPTY

        xml_files = sorted(glob.glob(os.path.join(raw_dir, "TEST-*.xml")))
        if not xml_files:
            print(f"ERROR: [TEST_REPORT_ERROR] No Gradle JUnit XML reports (TEST-*.xml) found in: {raw_dir}", file=sys.stderr)
            return EXIT_MISSING_OR_EMPTY

        for xml_file in xml_files:
            try:
                suites = _extract_suites_from_xml(xml_file)
            except ValueError as exc:
                print(f"ERROR: [TEST_REPORT_PARSE_FAILURE] {exc}", file=sys.stderr)
                return EXIT_MALFORMED_XML

            for st in suites:
                suite_key = (real_dir, st.suite_name)
                if suite_key in seen_suite_keys:
                    print(
                        f"WARNING: Duplicate suite '{st.suite_name}' in {raw_dir} ({xml_file}); skipping duplicate entry.",
                        file=sys.stderr,
                    )
                    continue
                seen_suite_keys.add(suite_key)
                all_suites.append(st)

    total_suites = len(all_suites)
    total_tests = sum(s.tests for s in all_suites)
    total_failures = sum(s.failures for s in all_suites)
    total_errors = sum(s.errors for s in all_suites)
    total_skipped = sum(s.skipped for s in all_suites)
    failed_case_names = [case for s in all_suites for case in s.failed_cases]

    if total_suites == 0 or total_tests == 0:
        print(
            f"ERROR: [TEST_REPORT_ERROR] Zero unit tests recorded across {total_suites} suite(s).",
            file=sys.stderr,
        )
        return EXIT_MISSING_OR_EMPTY

    if github_env_path:
        with open(github_env_path, "a", encoding="utf-8") as env_file:
            env_file.write(f"UNIT_TEST_SUITES={total_suites}\n")
            env_file.write(f"UNIT_TEST_CASES={total_tests}\n")
            env_file.write(f"UNIT_TEST_FAILURES={total_failures}\n")
            env_file.write(f"UNIT_TEST_ERRORS={total_errors}\n")
            env_file.write(f"UNIT_TEST_SKIPPED={total_skipped}\n")

    if github_output_path:
        with open(github_output_path, "a", encoding="utf-8") as out_file:
            out_file.write(f"unit_test_suites={total_suites}\n")
            out_file.write(f"unit_test_cases={total_tests}\n")
            out_file.write(f"unit_test_failures={total_failures}\n")
            out_file.write(f"unit_test_errors={total_errors}\n")
            out_file.write(f"unit_test_skipped={total_skipped}\n")

    print(
        f"UNIT_TEST_SUITES={total_suites} UNIT_TEST_CASES={total_tests} "
        f"UNIT_TEST_FAILURES={total_failures} UNIT_TEST_ERRORS={total_errors} UNIT_TEST_SKIPPED={total_skipped}"
    )

    if total_failures > 0 or total_errors > 0:
        print(
            f"ERROR: [UNIT_TEST_FAILURE] Unit test verification failed: "
            f"failures={total_failures}, errors={total_errors}, tests={total_tests}, suites={total_suites}.",
            file=sys.stderr,
        )
        if failed_case_names:
            print("Failing test cases:", file=sys.stderr)
            for case in failed_case_names[:50]:
                print(f"  - {case}", file=sys.stderr)
        return EXIT_TEST_FAILURE

    print(
        f"SUCCESS: Executed {total_tests} unit tests across {total_suites} suites "
        f"(skipped={total_skipped}, failures=0, errors=0)."
    )
    return EXIT_OK


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Gradle JUnit XML test reports.")
    parser.add_argument("directories", nargs="+", help="One or more Gradle test-results XML directories.")
    parser.add_argument("--github-env", default=os.environ.get("GITHUB_ENV") or None)
    parser.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT") or None)
    args = parser.parse_args()
    return verify_directories(
        directories=args.directories,
        github_env_path=args.github_env,
        github_output_path=args.github_output,
    )


if __name__ == "__main__":
    sys.exit(main())
