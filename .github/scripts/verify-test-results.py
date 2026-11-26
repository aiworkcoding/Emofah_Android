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
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import List, Set, Tuple


EXIT_OK = 0
EXIT_TEST_FAILURE = 1
EXIT_MISSING_OR_EMPTY = 2
EXIT_MALFORMED_XML = 3
MAX_STACK_LINES = 40


@dataclass
class FailedCaseDetail:
    full_name: str
    kind: str
    exc_type: str
    message: str
    stacktrace: str
    expected: str | None = None
    actual: str | None = None


@dataclass
class SuiteStats:
    file_path: str
    suite_name: str
    tests: int
    failures: int
    errors: int
    skipped: int
    failed_cases: List[str]
    failed_details: List[FailedCaseDetail] = field(default_factory=list)


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
        failed_details: List[FailedCaseDetail] = []
        for tc in suite_el.findall("testcase"):
            tc_class = (tc.attrib.get("classname") or suite_name).strip()
            tc_name = (tc.attrib.get("name") or "<unnamed>").strip()
            full_name = f"{tc_class}#{tc_name}"
            issue_nodes = [(kind, el) for kind in ("failure", "error") for el in tc.findall(kind)]
            if issue_nodes:
                failed_cases.append(full_name)
                for kind, el in issue_nodes:
                    exc_type = (el.attrib.get("type") or kind).strip()
                    message = (el.attrib.get("message") or "").strip()
                    stacktrace = (el.text or "").strip()
                    exp, act = _extract_expected_actual(message, stacktrace)
                    failed_details.append(
                        FailedCaseDetail(
                            full_name=full_name,
                            kind=kind,
                            exc_type=exc_type,
                            message=message,
                            stacktrace=stacktrace,
                            expected=exp,
                            actual=act,
                        )
                    )

        parsed.append(
            SuiteStats(
                file_path=file_path,
                suite_name=suite_name,
                tests=tests,
                failures=failures,
                errors=errors,
                skipped=skipped,
                failed_cases=failed_cases,
                failed_details=failed_details,
            )
        )
    return parsed


def _extract_expected_actual(message: str, stacktrace: str) -> Tuple[str | None, str | None]:
    candidates = [message]
    if stacktrace:
        candidates.extend(stacktrace.splitlines()[:8])
    for text in candidates:
        if not text:
            continue
        m = re.search(r"expected:\s*<([^>]*)>\s*but\s*was:\s*<([^>]*)>", text, re.I)
        if m:
            return m.group(1), m.group(2)
        m = re.search(r"expected:\s*\[([^\]]*)\]\s*but\s*was:\s*\[([^\]]*)\]", text, re.I)
        if m:
            return m.group(1), m.group(2)
        m = re.search(r'expected:\s*"([^"]*)"\s*but\s*was:\s*"([^"]*)"', text, re.I)
        if m:
            return m.group(1), m.group(2)
        m = re.search(r"expected\s+same:\s*<([^>]*)>\s*was\s+not:\s*<([^>]*)>", text, re.I)
        if m:
            return m.group(1), m.group(2)
    return None, None


def _truncate_stack_lines(stacktrace: str) -> List[str]:
    if not stacktrace:
        return []
    lines = [line.rstrip() for line in stacktrace.splitlines() if line.strip()]
    if len(lines) <= MAX_STACK_LINES:
        return lines
    return lines[:MAX_STACK_LINES] + [f"... ({len(lines) - MAX_STACK_LINES} more stack trace line(s) truncated)"]


def verify_directories(
    directories: List[str],
    github_env_path: str | None = None,
    github_output_path: str | None = None,
    github_summary_path: str | None = None,
) -> int:
    if not directories:
        print("ERROR: [TEST_REPORT_ERROR] At least one test results directory must be provided.", file=sys.stderr)
        return EXIT_MISSING_OR_EMPTY

    seen_dirs: Set[str] = set()
    seen_suite_keys: Set[Tuple[str, str]] = set()
    all_suites: List[SuiteStats] = []
    missing_dirs: List[str] = []
    empty_dirs: List[str] = []
    malformed_files: List[Tuple[str, str]] = []

    for raw_dir in directories:
        real_dir = os.path.realpath(raw_dir)
        if real_dir in seen_dirs:
            print(f"INFO: Skipping duplicate directory argument: {raw_dir}", file=sys.stderr)
            continue
        seen_dirs.add(real_dir)

        if not os.path.exists(raw_dir) or not os.path.isdir(raw_dir):
            missing_dirs.append(raw_dir)
            continue

        xml_files = sorted(glob.glob(os.path.join(raw_dir, "TEST-*.xml")))
        if not xml_files:
            empty_dirs.append(raw_dir)
            continue

        for xml_file in xml_files:
            try:
                suites = _extract_suites_from_xml(xml_file)
            except ValueError as exc:
                malformed_files.append((xml_file, str(exc)))
                continue

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
    total_passed = max(0, total_tests - total_failures - total_errors - total_skipped)
    failed_case_details = [detail for s in all_suites for detail in s.failed_details]
    failed_case_names = [case for s in all_suites for case in s.failed_cases]

    if github_env_path:
        with open(github_env_path, "a", encoding="utf-8") as env_file:
            env_file.write(f"UNIT_TEST_SUITES={total_suites}\n")
            env_file.write(f"UNIT_TEST_CASES={total_tests}\n")
            env_file.write(f"UNIT_TEST_PASSED={total_passed}\n")
            env_file.write(f"UNIT_TEST_FAILURES={total_failures}\n")
            env_file.write(f"UNIT_TEST_ERRORS={total_errors}\n")
            env_file.write(f"UNIT_TEST_SKIPPED={total_skipped}\n")

    if github_output_path:
        with open(github_output_path, "a", encoding="utf-8") as out_file:
            out_file.write(f"unit_test_suites={total_suites}\n")
            out_file.write(f"unit_test_cases={total_tests}\n")
            out_file.write(f"unit_test_passed={total_passed}\n")
            out_file.write(f"unit_test_failures={total_failures}\n")
            out_file.write(f"unit_test_errors={total_errors}\n")
            out_file.write(f"unit_test_skipped={total_skipped}\n")

    print(
        f"UNIT_TEST_SUITES={total_suites} UNIT_TEST_CASES={total_tests} UNIT_TEST_PASSED={total_passed} "
        f"UNIT_TEST_FAILURES={total_failures} UNIT_TEST_ERRORS={total_errors} UNIT_TEST_SKIPPED={total_skipped}"
    )

    # 1. Genuine test failures must ALWAYS be reported and result in EXIT_TEST_FAILURE (1).
    #    A missing or malformed secondary directory must never conceal actual failing tests.
    if total_failures > 0 or total_errors > 0:
        print(
            f"ERROR: [UNIT_TEST_FAILURE] Unit test verification failed: "
            f"failures={total_failures}, errors={total_errors}, tests={total_tests}, suites={total_suites}.",
            file=sys.stderr,
        )
        if failed_case_details:
            print("Failing test cases:", file=sys.stderr)
            for detail in failed_case_details[:50]:
                header = f"  - {detail.full_name} [{detail.exc_type}]"
                if detail.message:
                    header = f"{header}: {detail.message}"
                print(header, file=sys.stderr)
                if detail.expected is not None or detail.actual is not None:
                    print(f"      Expected: <{detail.expected}>", file=sys.stderr)
                    print(f"      Actual:   <{detail.actual}>", file=sys.stderr)
                for st_line in _truncate_stack_lines(detail.stacktrace):
                    print(f"      {st_line}", file=sys.stderr)
        elif failed_case_names:
            print("Failing test cases:", file=sys.stderr)
            for case in failed_case_names[:50]:
                print(f"  - {case}", file=sys.stderr)

        if github_summary_path:
            with open(github_summary_path, "a", encoding="utf-8") as summary_file:
                summary_file.write("### Unit Test Failure Diagnostics\n\n")
                summary_file.write(
                    f"- **Tests Executed:** `{total_tests}` across `{total_suites}` suite(s) "
                    f"(`passed={total_passed}`, `failures={total_failures}`, `errors={total_errors}`, `skipped={total_skipped}`)\n\n"
                )
                for detail in failed_case_details[:20]:
                    summary_file.write(f"#### `{detail.full_name}`\n")
                    if detail.exc_type or detail.message:
                        summary_file.write(f"- **Error:** `{detail.exc_type}` — {detail.message or '(no message)'}\n")
                    if detail.expected is not None or detail.actual is not None:
                        summary_file.write(f"- **Expected:** `{detail.expected}`\n")
                        summary_file.write(f"- **Actual:** `{detail.actual}`\n")
                    stack_lines = _truncate_stack_lines(detail.stacktrace)
                    if stack_lines:
                        summary_file.write("\n```text\n")
                        summary_file.write("\n".join(stack_lines))
                        summary_file.write("\n```\n\n")

        if missing_dirs:
            for md in missing_dirs:
                print(f"WARNING: Test results directory was also missing or unreadable: {md}", file=sys.stderr)
        if malformed_files:
            for mf, exc in malformed_files:
                print(f"WARNING: Malformed XML file also encountered: {mf} ({exc})", file=sys.stderr)

        return EXIT_TEST_FAILURE

    # 2. Check for malformed XML files
    if malformed_files:
        for mf, exc in malformed_files:
            print(f"ERROR: [TEST_REPORT_PARSE_FAILURE] {exc}", file=sys.stderr)
        return EXIT_MALFORMED_XML

    # 3. Check for missing or empty report directories
    if missing_dirs:
        for md in missing_dirs:
            print(f"ERROR: [TEST_REPORT_ERROR] Test results directory does not exist or is not a directory: {md}", file=sys.stderr)
        return EXIT_MISSING_OR_EMPTY

    if empty_dirs:
        for ed in empty_dirs:
            print(f"ERROR: [TEST_REPORT_ERROR] No Gradle JUnit XML reports (TEST-*.xml) found in: {ed}", file=sys.stderr)
        return EXIT_MISSING_OR_EMPTY

    if total_suites == 0 or total_tests == 0:
        print(
            f"ERROR: [TEST_REPORT_ERROR] Zero unit tests recorded across {total_suites} suite(s).",
            file=sys.stderr,
        )
        return EXIT_MISSING_OR_EMPTY

    print(
        f"SUCCESS: Executed {total_tests} unit tests across {total_suites} suites "
        f"({total_passed} passed, skipped={total_skipped}, failures=0, errors=0)."
    )
    return EXIT_OK


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Gradle JUnit XML test reports.")
    parser.add_argument("directories", nargs="+", help="One or more Gradle test-results XML directories.")
    parser.add_argument("--github-env", default=os.environ.get("GITHUB_ENV") or None)
    parser.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT") or None)
    parser.add_argument("--github-summary", default=os.environ.get("GITHUB_STEP_SUMMARY") or None)
    args = parser.parse_args()
    return verify_directories(
        directories=args.directories,
        github_env_path=args.github_env,
        github_output_path=args.github_output,
        github_summary_path=args.github_summary,
    )


if __name__ == "__main__":
    sys.exit(main())
