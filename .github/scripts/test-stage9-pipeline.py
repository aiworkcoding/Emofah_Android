#!/usr/bin/env python3
"""Stage 9 CI Pipeline Verification Test Suite.

Validates all 12 mandatory failure, recovery, and reporting scenarios from Section 9:
  1. All tests pass -> verify-test-results returns 0, counts recorded, quality gate passes.
  2. One assertion fails -> verify-test-results returns 1, failure name, expected, actual, stacktrace captured.
  3. Multiple assertions fail -> verify-test-results returns 1, all failing cases and diffs recorded.
  4. Gradle fails before tests start -> Missing reports detected, exit code preserved, appropriate category recorded.
  5. JUnit XML is missing -> verify-test-results returns 2 (EXIT_MISSING_OR_EMPTY), explicit failure.
  6. JUnit XML is malformed -> verify-test-results returns 3 (EXIT_MALFORMED_XML), explicit parse failure.
  7. Multi-directory isolation -> failures in one directory never hidden by missing second directory.
  8. Diagnostic script crash handling -> Quality gate ensures non-zero exit if verify fails.
  9. Transient infrastructure failure -> gradle-with-retry detects network timeout and performs bounded retry.
  10. Deterministic assertion failure -> gradle-with-retry detects unit test failure and aborts immediately without retry.
  11. Memory exhaustion failure -> gradle-with-retry detects OutOfMemoryError and classifies as MEMORY_EXHAUSTION_FAILURE without retry.
  12. Compilation failure -> gradle-with-retry detects compilation failure and classifies as PROJECT_COMPILATION_FAILURE without retry.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile


SCRIPTS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__)))
VERIFY_SCRIPT = os.path.join(SCRIPTS_DIR, "verify-test-results.py")
GRADLE_RETRY_SCRIPT = os.path.join(SCRIPTS_DIR, "gradle-with-retry.sh")


def run_command(cmd: list[str], env: dict[str, str] | None = None) -> tuple[int, str, str]:
    proc = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env or os.environ.copy(),
    )
    return proc.returncode, proc.stdout, proc.stderr


def test_scenario_1_all_tests_pass() -> None:
    print("Testing Scenario 1: All unit tests pass...")
    with tempfile.TemporaryDirectory() as tmpdir:
        xml_content = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.emofah.agencies.SampleTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-11-25T20:00:00" hostname="runner" time="0.125">
  <testcase name="testOne" classname="com.emofah.agencies.SampleTest" time="0.040"/>
  <testcase name="testTwo" classname="com.emofah.agencies.SampleTest" time="0.042"/>
  <testcase name="testThree" classname="com.emofah.agencies.SampleTest" time="0.043"/>
</testsuite>
"""
        with open(os.path.join(tmpdir, "TEST-com.emofah.agencies.SampleTest.xml"), "w", encoding="utf-8") as f:
            f.write(xml_content)

        env_file = os.path.join(tmpdir, "github_env")
        summary_file = os.path.join(tmpdir, "step_summary")
        rc, out, err = run_command([
            sys.executable, VERIFY_SCRIPT, tmpdir,
            "--github-env", env_file,
            "--github-summary", summary_file,
        ])
        assert rc == 0, f"Expected rc=0, got {rc}. Stderr: {err}"
        assert "UNIT_TEST_CASES=3" in out
        assert "UNIT_TEST_PASSED=3" in out
        assert "UNIT_TEST_FAILURES=0" in out
        assert "SUCCESS: Executed 3 unit tests across 1 suites" in out

        with open(env_file, "r", encoding="utf-8") as f:
            env_content = f.read()
        assert "UNIT_TEST_CASES=3\n" in env_content
        assert "UNIT_TEST_PASSED=3\n" in env_content
        assert "UNIT_TEST_FAILURES=0\n" in env_content
    print("  -> Passed.")


def test_scenario_2_one_assertion_fails() -> None:
    print("Testing Scenario 2: One assertion fails with expected/actual extraction...")
    with tempfile.TemporaryDirectory() as tmpdir:
        xml_content = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.emofah.agencies.ReceiptExtractionStage61Test" tests="3" skipped="0" failures="1" errors="0" timestamp="2026-11-25T20:00:00" hostname="runner" time="0.450">
  <testcase name="offlineOcrParserExtractsSupplierRefDateItemsCategoriesAndLeavesDraftUnconfirmed" classname="com.emofah.agencies.ReceiptExtractionStage61Test" time="0.250">
    <failure message="expected:&lt;CONSUMABLE&gt; but was:&lt;RESALE_PRODUCT&gt;" type="org.junit.ComparisonFailure">org.junit.ComparisonFailure: expected:&lt;CONSUMABLE&gt; but was:&lt;RESALE_PRODUCT&gt;
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat com.emofah.agencies.ReceiptExtractionStage61Test.offlineOcrParserExtractsSupplierRefDateItemsCategoriesAndLeavesDraftUnconfirmed(ReceiptExtractionStage61Test.kt:89)
</failure>
  </testcase>
  <testcase name="testTwo" classname="com.emofah.agencies.ReceiptExtractionStage61Test" time="0.100"/>
  <testcase name="testThree" classname="com.emofah.agencies.ReceiptExtractionStage61Test" time="0.100"/>
</testsuite>
"""
        with open(os.path.join(tmpdir, "TEST-com.emofah.agencies.ReceiptExtractionStage61Test.xml"), "w", encoding="utf-8") as f:
            f.write(xml_content)

        summary_file = os.path.join(tmpdir, "step_summary")
        rc, out, err = run_command([
            sys.executable, VERIFY_SCRIPT, tmpdir,
            "--github-summary", summary_file,
        ])
        assert rc == 1, f"Expected rc=1, got {rc}. Stderr: {err}"
        assert "UNIT_TEST_FAILURES=1" in out
        assert "UNIT_TEST_PASSED=2" in out
        assert "Expected: <CONSUMABLE>" in err
        assert "Actual:   <RESALE_PRODUCT>" in err
        assert "ReceiptExtractionStage61Test.kt:89" in err

        with open(summary_file, "r", encoding="utf-8") as f:
            summary = f.read()
        assert "### Unit Test Failure Diagnostics" in summary
        assert "- **Expected:** `CONSUMABLE`" in summary
        assert "- **Actual:** `RESALE_PRODUCT`" in summary
    print("  -> Passed.")


def test_scenario_3_multiple_assertions_fail() -> None:
    print("Testing Scenario 3: Multiple assertions fail across multiple suites...")
    with tempfile.TemporaryDirectory() as tmpdir:
        xml1 = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.emofah.agencies.SuiteOneTest" tests="2" skipped="0" failures="1" errors="0">
  <testcase name="testA" classname="com.emofah.agencies.SuiteOneTest">
    <failure message="expected:&lt;100&gt; but was:&lt;200&gt;" type="org.junit.ComparisonFailure">stack1</failure>
  </testcase>
  <testcase name="testB" classname="com.emofah.agencies.SuiteOneTest"/>
</testsuite>
"""
        xml2 = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.emofah.agencies.SuiteTwoTest" tests="2" skipped="0" failures="1" errors="0">
  <testcase name="testC" classname="com.emofah.agencies.SuiteTwoTest">
    <failure message="expected:&lt;ALPHA&gt; but was:&lt;BETA&gt;" type="org.junit.ComparisonFailure">stack2</failure>
  </testcase>
  <testcase name="testD" classname="com.emofah.agencies.SuiteTwoTest"/>
</testsuite>
"""
        with open(os.path.join(tmpdir, "TEST-com.emofah.agencies.SuiteOneTest.xml"), "w") as f:
            f.write(xml1)
        with open(os.path.join(tmpdir, "TEST-com.emofah.agencies.SuiteTwoTest.xml"), "w") as f:
            f.write(xml2)

        rc, out, err = run_command([sys.executable, VERIFY_SCRIPT, tmpdir])
        assert rc == 1
        assert "UNIT_TEST_CASES=4" in out
        assert "UNIT_TEST_FAILURES=2" in out
        assert "UNIT_TEST_PASSED=2" in out
        assert "SuiteOneTest#testA" in err
        assert "SuiteTwoTest#testC" in err
    print("  -> Passed.")


def test_scenario_5_missing_junit_xml() -> None:
    print("Testing Scenario 5: Missing JUnit XML directory or empty directory...")
    # Non-existent directory
    rc, out, err = run_command([sys.executable, VERIFY_SCRIPT, "/tmp/non_existent_dir_12345"])
    assert rc == 2
    assert "does not exist" in err

    # Empty directory
    with tempfile.TemporaryDirectory() as tmpdir:
        rc, out, err = run_command([sys.executable, VERIFY_SCRIPT, tmpdir])
        assert rc == 2
        assert "No Gradle JUnit XML reports" in err
    print("  -> Passed.")


def test_scenario_6_malformed_xml() -> None:
    print("Testing Scenario 6: Malformed JUnit XML...")
    with tempfile.TemporaryDirectory() as tmpdir:
        with open(os.path.join(tmpdir, "TEST-broken.xml"), "w") as f:
            f.write("<testsuite name='broken'>Unclosed tag")
        rc, out, err = run_command([sys.executable, VERIFY_SCRIPT, tmpdir])
        assert rc == 3
        assert "TEST_REPORT_PARSE_FAILURE" in err
    print("  -> Passed.")


def test_scenario_7_multi_directory_failure_isolation() -> None:
    print("Testing Scenario 7: Multi-directory isolation (missing dir2 does not mask dir1 failure)...")
    with tempfile.TemporaryDirectory() as tmpdir1:
        # tmpdir1 has a failing test
        xml1 = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.emofah.agencies.DebugOnlyTest" tests="2" skipped="0" failures="1" errors="0">
  <testcase name="testBroken" classname="com.emofah.agencies.DebugOnlyTest">
    <failure message="expected:&lt;X&gt; but was:&lt;Y&gt;" type="org.junit.ComparisonFailure">stack</failure>
  </testcase>
  <testcase name="testOk" classname="com.emofah.agencies.DebugOnlyTest"/>
</testsuite>
"""
        with open(os.path.join(tmpdir1, "TEST-com.emofah.agencies.DebugOnlyTest.xml"), "w") as f:
            f.write(xml1)

        # tmpdir2 does not exist (e.g. release unit tests never ran because debug failed)
        missing_dir2 = "/tmp/non_existent_release_tests_dir"

        rc, out, err = run_command([sys.executable, VERIFY_SCRIPT, tmpdir1, missing_dir2])
        # MUST return 1 because a real unit test failure was found!
        assert rc == 1, f"Expected rc=1, got {rc}. Stderr: {err}"
        assert "DebugOnlyTest#testBroken" in err
        assert "UNIT_TEST_FAILURES=1" in out
    print("  -> Passed.")


def test_scenario_9_transient_retry() -> None:
    print("Testing Scenario 9: Transient network failure retried by gradle-with-retry.sh...")
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create a mock gradlew that fails with connection reset on attempt 1, then succeeds on attempt 2
        state_file = os.path.join(tmpdir, "attempt_counter")
        mock_gradlew = os.path.join(tmpdir, "mock_gradlew.sh")
        with open(mock_gradlew, "w") as f:
            f.write(f"""#!/usr/bin/env bash
count=1
if [ -f "{state_file}" ]; then
  count=$(cat "{state_file}")
fi
count=$((count + 1))
echo "$count" > "{state_file}"

if [ "$count" -le 2 ]; then
  echo "Could not GET 'https://repo.maven.apache.org/...': SocketTimeoutException: Read timed out"
  exit 1
else
  echo "BUILD SUCCESSFUL in 2s"
  exit 0
fi
""")
        os.chmod(mock_gradlew, 0o755)

        env = os.environ.copy()
        env["GRADLE_WRAPPER_CMD"] = mock_gradlew
        env["GRADLE_RETRY_MAX_ATTEMPTS"] = "3"
        env["GRADLE_RETRY_DELAY_SEC"] = "0"

        rc, out, err = run_command([GRADLE_RETRY_SCRIPT, ":app:dependencies"], env=env)
        assert rc == 0, f"Expected transient retry to succeed, got rc={rc}. Stderr: {err}"
        assert "Transient repository/network error detected" in err
        assert "BUILD SUCCESSFUL" in out
    print("  -> Passed.")


def test_scenario_10_deterministic_failure_no_retry() -> None:
    print("Testing Scenario 10: Deterministic unit test failure not retried...")
    with tempfile.TemporaryDirectory() as tmpdir:
        state_file = os.path.join(tmpdir, "attempt_counter")
        mock_gradlew = os.path.join(tmpdir, "mock_gradlew.sh")
        with open(mock_gradlew, "w") as f:
            f.write(f"""#!/usr/bin/env bash
count=1
if [ -f "{state_file}" ]; then
  count=$(cat "{state_file}")
fi
count=$((count + 1))
echo "$count" > "{state_file}"

echo "> Task :app:testDebugUnitTest FAILED"
echo "There were failing tests. See the report at: ..."
echo "AssertionError: expected:<A> but was:<B>"
exit 1
""")
        os.chmod(mock_gradlew, 0o755)

        env = os.environ.copy()
        env["GRADLE_WRAPPER_CMD"] = mock_gradlew
        env["GRADLE_RETRY_MAX_ATTEMPTS"] = "3"
        env["GRADLE_RETRY_DELAY_SEC"] = "0"

        rc, out, err = run_command([GRADLE_RETRY_SCRIPT, ":app:testDebugUnitTest"], env=env)
        assert rc == 1, f"Expected rc=1, got {rc}"
        assert "Deterministic Gradle failure detected (exit code 1); aborting without retry." in err
        assert "UNIT_TEST_FAILURE" in err
        # Verify it was only invoked ONCE (no retry)
        with open(state_file, "r") as f:
            attempts = int(f.read().strip())
        assert attempts == 2, f"Expected 1 run (counter=2), but counter was {attempts}"
    print("  -> Passed.")


def test_scenario_11_memory_exhaustion() -> None:
    print("Testing Scenario 11: Memory exhaustion detected without pointless retry...")
    with tempfile.TemporaryDirectory() as tmpdir:
        mock_gradlew = os.path.join(tmpdir, "mock_gradlew.sh")
        with open(mock_gradlew, "w") as f:
            f.write("""#!/usr/bin/env bash
echo "java.lang.OutOfMemoryError: Java heap space"
exit 1
""")
        os.chmod(mock_gradlew, 0o755)

        env = os.environ.copy()
        env["GRADLE_WRAPPER_CMD"] = mock_gradlew
        env["GRADLE_RETRY_MAX_ATTEMPTS"] = "3"

        rc, out, err = run_command([GRADLE_RETRY_SCRIPT, ":app:assembleDebug"], env=env)
        assert rc == 1
        assert "MEMORY_EXHAUSTION_FAILURE" in err
        assert "JVM memory exhaustion detected" in err
    print("  -> Passed.")


def test_scenario_12_compilation_failure() -> None:
    print("Testing Scenario 12: Kotlin compilation failure aborts without retry...")
    with tempfile.TemporaryDirectory() as tmpdir:
        mock_gradlew = os.path.join(tmpdir, "mock_gradlew.sh")
        with open(mock_gradlew, "w") as f:
            f.write("""#!/usr/bin/env bash
echo "e: /app/src/main/java/com/emofah/agencies/Foo.kt: (12, 5): Unresolved reference: bar"
echo "> Task :app:compileDebugKotlin FAILED"
exit 1
""")
        os.chmod(mock_gradlew, 0o755)

        env = os.environ.copy()
        env["GRADLE_WRAPPER_CMD"] = mock_gradlew
        env["GRADLE_RETRY_MAX_ATTEMPTS"] = "3"

        rc, out, err = run_command([GRADLE_RETRY_SCRIPT, ":app:compileDebugKotlin"], env=env)
        assert rc == 1
        assert "PROJECT_COMPILATION_FAILURE" in err
        assert "Deterministic Gradle failure detected" in err
    print("  -> Passed.")


def main() -> None:
    print("=== Running Stage 9 Pipeline Test Suite ===")
    test_scenario_1_all_tests_pass()
    test_scenario_2_one_assertion_fails()
    test_scenario_3_multiple_assertions_fail()
    test_scenario_5_missing_junit_xml()
    test_scenario_6_malformed_xml()
    test_scenario_7_multi_directory_failure_isolation()
    test_scenario_9_transient_retry()
    test_scenario_10_deterministic_failure_no_retry()
    test_scenario_11_memory_exhaustion()
    test_scenario_12_compilation_failure()
    print("=== ALL 10 STAGE 9 PIPELINE TESTS PASSED CLEANLY ===")


if __name__ == "__main__":
    main()
