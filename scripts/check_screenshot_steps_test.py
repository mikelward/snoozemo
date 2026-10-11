#!/usr/bin/env python3
"""Tests scripts/check_screenshot_steps.py against throwaway trees and this one.

Its failure mode is a false pass, so each case asserts the exit status and
that the offending class is named, and one case pins that the real
workflow parses to something.

Standard library only. Run it directly:
    python3 scripts/check_screenshot_steps_test.py
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts" / "check_screenshot_steps.py"

STEP = '      - name: Run {name} tests\n        run: ./gradlew :app:testDebugUnitTest --tests "app.example.{name}" -Proborazzi.test.record=true\n'


def run(repo):
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--repo", str(repo)],
        capture_output=True,
        text=True,
        check=False,
    )


class CheckScreenshotStepsTest(unittest.TestCase):

    def tree(self, steps, classes, extra_workflow="", other_jobs=""):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        workflow = root / ".github/workflows/ci.yml"
        workflow.parent.mkdir(parents=True)
        workflow.write_text(
            "jobs:\n"
            "  screenshot-tests:\n"
            "    steps:\n"
            + "".join(STEP.format(name=n) for n in steps)
            + extra_workflow
            + other_jobs
        )
        tests = root / "app/src/test/kotlin/app/example"
        tests.mkdir(parents=True)
        for name in classes:
            (tests / f"{name}.kt").write_text(f"package app.example\n\nclass {name} {{\n}}\n")
        return root

    def test_a_listed_class_that_exists_passes(self):
        result = run(self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"]))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_a_step_naming_a_missing_class_fails_and_names_it(self):
        root = self.tree(["MainScreenScreenshotTest", "RenamedScreenshotTest"], ["MainScreenScreenshotTest"])

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.RenamedScreenshotTest is named by a screenshot step", result.stdout)

    def test_a_file_without_the_class_is_still_missing(self):
        root = self.tree(["MainScreenScreenshotTest"], [])
        (root / "app/src/test/kotlin/app/example/MainScreenScreenshotTest.kt").write_text(
            "package app.example\n\nclass Other\n"
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("app.example.MainScreenScreenshotTest", result.stdout)

    def test_a_screenshot_class_with_no_step_fails_and_names_it(self):
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest", "NewScreenshotTest"])

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_a_commented_example_is_not_a_step(self):
        commented = '        #     run: ./gradlew :app:test --tests "app.example.GoneScreenshotTest"\n'
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"], commented)

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_a_workflow_with_no_steps_fails_rather_than_passing_vacuously(self):
        result = run(self.tree([], []))

        self.assertEqual(1, result.returncode)
        self.assertIn("no recording `--tests` steps found", result.stdout)

    def test_a_step_written_as_a_block_scalar_is_still_read(self):
        # The filter on a continuation line, not on the `run:` line itself.
        block = (
            "      - name: Run gone tests\n"
            "        run: |\n"
            "          ./gradlew :app:testDebugUnitTest \\\n"
            '            --tests "app.example.GoneScreenshotTest"\n'
        )
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"], block)

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("app.example.GoneScreenshotTest is named", result.stdout)

    def test_a_class_is_found_whatever_its_file_is_called(self):
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"])
        (root / "app/src/test/kotlin/app/example/MoreTests.kt").write_text(
            "package app.example\n\nclass NewScreenScreenshotTest\n\nclass Helper\n"
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenScreenshotTest has no recording step", result.stdout)

    def test_a_listed_class_in_a_differently_named_file_passes(self):
        root = self.tree(["SharedScreenshotTest"], [])
        (root / "app/src/test/kotlin/app/example/Shared.kt").write_text(
            "package app.example\n\nclass SharedScreenshotTest\n"
        )

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_a_commented_out_declaration_is_not_a_class(self):
        root = self.tree(["GoneScreenshotTest"], [])
        (root / "app/src/test/kotlin/app/example/Gone.kt").write_text(
            "package app.example\n\n// class GoneScreenshotTest\n/* class GoneScreenshotTest { /* nested */ } */\n"
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("app.example.GoneScreenshotTest is named", result.stdout)

    def test_a_mention_in_a_kdoc_or_string_is_not_a_class(self):
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"])
        (root / "app/src/test/kotlin/app/example/Docs.kt").write_text(
            "package app.example\n\n"
            "/** Like class ExampleScreenshotTest. */\n"
            "class Docs {\n"
            '    val a = "class OtherScreenshotTest"\n'
            "    val q = '\"'\n"
            '    val b = """\n        class ThirdScreenshotTest\n    """\n'
            "}\n"
        )

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_a_filter_in_another_job_does_not_count_as_a_step(self):
        # A targeted run elsewhere records nothing (Codex, PR #322).
        build = (
            "  build:\n"
            "    steps:\n"
            '      - run: ./gradlew test --tests "app.example.NewScreenshotTest"\n'
        )
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "NewScreenshotTest"],
            other_jobs=build,
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_a_workflow_without_the_job_fails(self):
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"])
        workflow = root / ".github/workflows/ci.yml"
        workflow.write_text(workflow.read_text().replace("screenshot-tests:", "screenshots:"))

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("no `screenshot-tests` job found", result.stdout)

    def test_single_quoted_bare_and_equals_filters_are_read(self):
        steps = (
            "      - run: ./gradlew test --tests 'app.example.SingleScreenshotTest' -Proborazzi.test.record=true\n"
            "      - run: ./gradlew test --tests app.example.BareScreenshotTest -Proborazzi.test.record=true\n"
            "      - run: ./gradlew test --tests=app.example.GoneScreenshotTest -Proborazzi.test.record=true\n"
        )
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "SingleScreenshotTest", "BareScreenshotTest"],
            steps,
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("app.example.GoneScreenshotTest is named", result.stdout)
        self.assertNotIn("SingleScreenshotTest", result.stdout)
        self.assertNotIn("BareScreenshotTest", result.stdout)

    def test_a_filter_it_cannot_read_fails_rather_than_being_skipped(self):
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest"],
            '      - run: ./gradlew test --tests "app.example.*ScreenshotTest"\n',
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("cannot read a `--tests` filter", result.stdout)

    def test_a_step_that_does_not_record_does_not_count(self):
        # Running a screenshot class records nothing without the flag
        # (Codex, PR #322); the class must still exist, though.
        smoke = '      - run: ./gradlew test --tests "app.example.NewScreenshotTest"\n'
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "NewScreenshotTest"],
            smoke,
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_a_suite_in_the_debug_source_set_is_seen(self):
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"])
        debug = root / "app/src/testDebug/kotlin/app/example"
        debug.mkdir(parents=True)
        (debug / "DebugOnlyScreenshotTest.kt").write_text(
            "package app.example\n\nclass DebugOnlyScreenshotTest\n"
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.DebugOnlyScreenshotTest has no recording step", result.stdout)

    def test_a_record_flag_commented_out_inline_does_not_count(self):
        step = (
            '      - run: ./gradlew test --tests "app.example.NewScreenshotTest"'
            " # -Proborazzi.test.record=true\n"
        )
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "NewScreenshotTest"],
            step,
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_a_suite_in_another_variants_source_set_is_not_required(self):
        # The job's Play debug task cannot record it (Codex, PR #322).
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"])
        release = root / "app/src/testRelease/kotlin/app/example"
        release.mkdir(parents=True)
        (release / "ReleaseOnlyScreenshotTest.kt").write_text(
            "package app.example\n\nclass ReleaseOnlyScreenshotTest\n"
        )

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_a_java_suite_is_seen(self):
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"])
        java = root / "app/src/test/java/app/example"
        java.mkdir(parents=True)
        (java / "JavaScreenshotTest.java").write_text(
            "package app.example;\n\npublic class JavaScreenshotTest {}\n"
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.JavaScreenshotTest has no recording step", result.stdout)

    def test_a_flag_on_one_command_does_not_record_another_in_the_same_step(self):
        block = (
            "      - run: |\n"
            '          ./gradlew test --tests "app.example.MainScreenScreenshotTest" -Proborazzi.test.record=true\n'
            '          ./gradlew test --tests "app.example.NewScreenshotTest"\n'
        )
        root = self.tree([], ["MainScreenScreenshotTest", "NewScreenshotTest"], block)

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)
        self.assertNotIn("MainScreenScreenshotTest", result.stdout)

    def test_a_flag_in_the_step_name_does_not_record(self):
        step = (
            "      - name: Record with -Proborazzi.test.record=true\n"
            '        run: ./gradlew test --tests "app.example.NewScreenshotTest"\n'
        )
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "NewScreenshotTest"],
            step,
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_a_multi_line_command_still_records(self):
        block = (
            "      - run: |\n"
            "          ./gradlew test \\\n"
            '            --tests "app.example.NewScreenshotTest" \\\n'
            "            -Proborazzi.test.record=true\n"
        )
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "NewScreenshotTest"],
            block,
        )

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_two_commands_on_one_line_do_not_share_a_flag(self):
        for separator in (";", "&&", "||", "|"):
            with self.subTest(separator=separator):
                step = (
                    '      - run: ./gradlew test --tests "app.example.MainScreenScreenshotTest"'
                    f" -Proborazzi.test.record=true {separator}"
                    ' ./gradlew test --tests "app.example.NewScreenshotTest"\n'
                )
                root = self.tree([], ["MainScreenScreenshotTest", "NewScreenshotTest"], step)

                result = run(root)

                self.assertEqual(1, result.returncode)
                self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)
                self.assertNotIn("MainScreenScreenshotTest", result.stdout)

    def test_only_the_exact_record_property_counts(self):
        for flag in ("-Pnot.roborazzi.test.record=true", "-Proborazzi.test.recorder=true", "-Droborazzi.test.record=true"):
            with self.subTest(flag=flag):
                step = f'      - run: ./gradlew test --tests "app.example.NewScreenshotTest" {flag}\n'
                root = self.tree(
                    ["MainScreenScreenshotTest"],
                    ["MainScreenScreenshotTest", "NewScreenshotTest"],
                    step,
                )

                result = run(root)

                self.assertEqual(1, result.returncode)
                self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_every_form_of_the_record_property_counts(self):
        for flag in (
            "-Proborazzi.test.record=true",
            "-Proborazzi.test.record",
            "-P roborazzi.test.record=true",
            "--project-prop roborazzi.test.record=true",
            "--project-prop=roborazzi.test.record=true",
            "'-Proborazzi.test.record=true'",
        ):
            with self.subTest(flag=flag):
                step = f'      - run: ./gradlew test --tests "app.example.NewScreenshotTest" {flag}\n'
                root = self.tree(
                    ["MainScreenScreenshotTest"],
                    ["MainScreenScreenshotTest", "NewScreenshotTest"],
                    step,
                )

                result = run(root)

                self.assertEqual(0, result.returncode, result.stdout)

    def test_a_script_it_cannot_tokenize_fails(self):
        step = '      - run: ./gradlew test --tests "app.example.NewScreenshotTest -Proborazzi.test.record=true\n'
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest", "NewScreenshotTest"], step)

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("cannot read a `--tests` filter", result.stdout)

    def test_a_folded_block_is_one_command(self):
        block = (
            "      - run: >\n"
            "          ./gradlew test\n"
            '          --tests "app.example.NewScreenshotTest"\n'
            "          -Proborazzi.test.record=true\n"
            "\n"
            '          ./gradlew test --tests "app.example.MainScreenScreenshotTest"\n'
        )
        root = self.tree([], ["MainScreenScreenshotTest", "NewScreenshotTest"], block)

        result = run(root)

        # The fold joins the first command; the blank line still ends it.
        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.MainScreenScreenshotTest has no recording step", result.stdout)
        self.assertNotIn("NewScreenshotTest", result.stdout)

    def test_gradle_must_be_the_command_that_runs(self):
        step = '      - run: echo ./gradlew test --tests "app.example.NewScreenshotTest" -Proborazzi.test.record=true\n'
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest", "NewScreenshotTest"], step)

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("::error::app.example.NewScreenshotTest has no recording step", result.stdout)

    def test_an_assignment_before_gradle_still_records(self):
        step = '      - run: CI=1 ./gradlew test --tests "app.example.NewScreenshotTest" -Proborazzi.test.record=true\n'
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest", "NewScreenshotTest"], step)

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_block_indicators_in_either_order_are_read(self):
        for header in ("|2-", "|-2", ">2-", "|+"):
            with self.subTest(header=header):
                block = (
                    f"      - run: {header}\n"
                    '          ./gradlew test --tests "app.example.GoneScreenshotTest"\n'
                )
                root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"], block)

                result = run(root)

                self.assertEqual(1, result.returncode)
                self.assertIn("app.example.GoneScreenshotTest is named", result.stdout)

    def test_a_quoted_yaml_run_value_is_decoded(self):
        steps = (
            "      - run: './gradlew test --tests \"app.example.SingleScreenshotTest\" -Proborazzi.test.record=true'\n"
            '      - run: "./gradlew test --tests app.example.DoubleScreenshotTest -Proborazzi.test.record=true"\n'
            "      - run: './gradlew test --tests app.example.GoneScreenshotTest'\n"
        )
        root = self.tree(
            ["MainScreenScreenshotTest"],
            ["MainScreenScreenshotTest", "SingleScreenshotTest", "DoubleScreenshotTest"],
            steps,
        )

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("app.example.GoneScreenshotTest is named", result.stdout)
        self.assertNotIn("SingleScreenshotTest", result.stdout)
        self.assertNotIn("DoubleScreenshotTest", result.stdout)

    def test_a_filter_hidden_in_one_shell_word_fails(self):
        step = """      - run: bash -c './gradlew test --tests app.example.GoneScreenshotTest'\n"""
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest"], step)

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("cannot read a `--tests` filter", result.stdout)

    def test_the_workflow_can_come_from_elsewhere(self):
        # CI reads the merge revision's workflow against the head branch's
        # classes (Codex, PR #322).
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest", "NewScreenshotTest"])
        other = self.tree(["MainScreenScreenshotTest", "NewScreenshotTest"], [])
        workflow = other / ".github/workflows/ci.yml"

        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--repo", str(root), "--workflow", str(workflow)],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(1, run(root).returncode)

    def test_a_hash_inside_a_word_is_not_a_comment(self):
        step = (
            "      - run: ./gradlew test -Proborazzi.test.record=true"
            " --tests app.example.MainScreenScreenshotTest#suffix\n"
        )
        root = self.tree([], ["MainScreenScreenshotTest"], step)

        result = run(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("cannot read a `--tests` filter", result.stdout)

    def test_a_hash_in_quotes_is_not_a_comment_either(self):
        step = (
            "      - run: ./gradlew test -Dnote='a # b' --tests app.example.NewScreenshotTest"
            " -Proborazzi.test.record=true\n"
        )
        root = self.tree(["MainScreenScreenshotTest"], ["MainScreenScreenshotTest", "NewScreenshotTest"], step)

        result = run(root)

        self.assertEqual(0, result.returncode, result.stdout)

    def test_this_repository_passes(self):
        result = run(REPO)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
