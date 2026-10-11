#!/usr/bin/env python3
"""Checks the screenshot job's allow-list against the test tree.

The screenshot job runs one Gradle step per class, filtered with `--tests`.
Two mistakes fail badly there:

- A step naming a class that does not exist (a rename, a branch behind
  main, a step added before its class). Gradle fails the step, but its
  "No tests found for given includes" line is buried under a page of
  unrelated NO-SOURCE output (TODO.md, maintainer 2026-08-23).
- A `*ScreenshotTest` class no step names. It passes everywhere and
  records nothing in CI, so its snapshots silently never exist.

This reads every `--tests` filter in the screenshot job and every class the test
sources declare, needs nothing built, and
names the offending class on the first line of its output.

Standard library only. Run it directly:
    python3 scripts/check_screenshot_steps.py [--repo DIR]
"""

import argparse
import json
import re
import shlex
import sys
from pathlib import Path

# One class name, as a `--tests` filter must give it to be checked.
CLASS_NAME = re.compile(r"[A-Za-z_][\w.]*")

PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
CLASS = re.compile(r"\bclass\s+([A-Za-z_]\w*)")

# The source sets that feed the job's `testDebugUnitTest` task, and only
# those: a suite in another variant's set (`testRelease`, say)
# cannot be recorded by that task, so demanding a step for it would make the
# check impossible to satisfy (Codex, PR #322).
# Java roots too: Gradle runs a suite from either, and the `package`/`class`
# scan reads both languages the same way (Codex, PR #322).
TEST_ROOTS = [
    Path("app/src") / name / language
    for name in ("test", "testDebug")
    for language in ("kotlin", "java")
]

# The project property that makes a Gradle run record rather than just run.
# `app/build.gradle.kts` tests `hasProperty`, so any value counts.
RECORD_PROPERTY = "roborazzi.test.record"
WORKFLOW = Path(".github/workflows/ci.yml")


JOB = "screenshot-tests"

# A job key: two-space indent under `jobs:`, nothing after the colon.
JOB_KEY = re.compile(r"^  ([A-Za-z_][\w-]*):\s*$")


def job_lines(workflow_text, job):
    """The lines of one job's block, or None when the workflow has no such job.

    Only the recording job's filters count: a `--tests` filter naming a
    screenshot class anywhere else, a targeted run in `build` say, records
    nothing and must not satisfy the allow-list (Codex, PR #322).
    """
    lines = workflow_text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.rstrip() == f"  {job}:"), None)
    if start is None:
        return None
    end = next(
        (i for i in range(start + 1, len(lines)) if JOB_KEY.match(lines[i])),
        len(lines),
    )
    return lines[start + 1:end]


# A step's `run:` key, with the step's `- ` or without it.
RUN_KEY = re.compile(r"^(\s*)(?:-\s+)?run:\s?(.*)$")
# A block scalar header: `|` or `>`, with optional chomping and indent
# indicators in either order (Codex, PR #322).
BLOCK_HEADER = re.compile(r"^[|>](?:[+-]?\d?|\d[+-])\s*(#.*)?$")
# A leading `NAME=value` assignment, which the shell runs no command for.
ASSIGNMENT = re.compile(r"^[A-Za-z_]\w*=")


def run_scripts(lines):
    """The shell script of every `run:` in the job, as the runner sees it.

    Only the script counts: a step's `name:`, its `env`, or a YAML comment
    between steps is never read for a flag or a filter (Codex, PR #322).
    """
    scripts = []
    i = 0
    while i < len(lines):
        match = RUN_KEY.match(lines[i])
        i += 1
        if not match:
            continue
        key_indent, value = len(match.group(1)), match.group(2).strip()
        if not BLOCK_HEADER.match(value):
            scripts.append(plain_scalar(value))
            continue
        body = []
        while i < len(lines) and (
            not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > key_indent
        ):
            body.append(lines[i].strip())
            i += 1
        if value.startswith(">"):
            # A folded scalar joins its lines with spaces, a blank line
            # standing for a newline, so one command can span several
            # lines (Codex, PR #322).
            scripts.append("\n".join(" ".join(part.split("\n")) for part in "\n".join(body).split("\n\n")))
        else:
            scripts.append("\n".join(body))
    return scripts


def plain_scalar(value):
    """A one-line YAML value as the runner gets it, outer quotes decoded.

    `run: './gradlew …'` reaches the shell without its YAML quotes, so they
    must not reach the tokenizer either (Codex, PR #322). A value this cannot
    decode is passed through as is, and a filter hidden in it is then caught
    as unreadable rather than skipped.
    """
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == value[-1] == '"':
        try:
            # YAML's double-quoted escapes are a superset of JSON's.
            return json.loads(value)
        except ValueError:
            return value
    return value


def without_comment(line):
    """The line up to its shell comment, if it has one.

    A `#` starts a comment only at the start of a word, outside quotes, so
    `--tests a.B#x` keeps its `#x` as the shell does (Codex, PR #322).
    """
    quote = None
    i = 0
    while i < len(line):
        char = line[i]
        if quote:
            if char == "\\" and quote == '"':
                i += 1
            elif char == quote:
                quote = None
        elif char == "\\":
            i += 1
        elif char in "\"'":
            quote = char
        elif char == "#" and (i == 0 or line[i - 1].isspace() or line[i - 1] in ";&|()"):
            return line[:i]
        i += 1
    return line


def shell_commands(script):
    """The script split into simple commands, each a list of shell words.

    Tokenized the way the shell does it — quotes, comments, backslash
    continuations, and `;`, `&&`, `||`, `|` or a newline between commands —
    so a flag counts only for the command that passes it, however the
    commands are laid out (Codex, PR #322, twice). Raises ValueError on a
    script it cannot tokenize, an unclosed quote say.
    """
    commands = []
    for line in script.replace("\\\n", " ").splitlines():
        lexer = shlex.shlex(without_comment(line), posix=True, punctuation_chars=";&|()")
        lexer.whitespace_split = True
        # shlex would cut at any `#`, even mid-word; the shell does not.
        lexer.commenters = ""
        current = []
        for token in lexer:
            if token and set(token) <= set(";&|()"):
                if current:
                    commands.append(current)
                current = []
            else:
                current.append(token)
        if current:
            commands.append(current)
    return commands


def option_values(words, option):
    """Every value `words` gives `option`, as `option value` or `option=value`."""
    values = []
    for i, word in enumerate(words):
        if word == option and i + 1 < len(words):
            values.append(words[i + 1])
        elif word.startswith(option + "="):
            values.append(word[len(option) + 1:])
    return values


def records(words):
    """Whether this Gradle command sets the record property, in any form.

    `-Pname=value`, `-P name=value`, `--project-prop name=value` and
    `--project-prop=name=value`, matched on the exact name so
    `-Pnot.roborazzi.test.record=true` does not count (Codex, PR #322).
    """
    properties = option_values(words, "--project-prop") + option_values(words, "-P")
    properties += [word[2:] for word in words if word.startswith("-P") and len(word) > 2]
    return any(p.split("=", 1)[0] == RECORD_PROPERTY for p in properties)


def invokes_gradle(words):
    """Whether the command the shell runs is Gradle, not `echo ./gradlew …`.

    The executed word is the first one after any `NAME=value` assignments
    (Codex, PR #322).
    """
    command = next((word for word in words if not ASSIGNMENT.match(word)), "")
    return Path(command).name == "gradlew"


def listed_classes(lines):
    """What the job's `--tests` filters name: `(recorded, named, unreadable)`.

    `named` is every class any filter names, which must all exist. Only a
    filter in a Gradle command that sets the record property makes its class
    `recorded`, since a run without it records nothing (Codex, PR #322). A
    filter this cannot read as one class name (a wildcard, a variable), or a
    script it cannot tokenize, is `unreadable` and reported rather than
    skipped, since a skipped filter is how a missing class would get past the
    check.
    """
    recorded, named, unreadable = [], [], []
    for script in run_scripts(lines):
        try:
            commands = shell_commands(script)
        except ValueError as error:
            unreadable.append(f"{script.strip()} ({error})")
            continue
        for words in commands:
            gradle = invokes_gradle(words)
            # A `--tests` inside some other word, a quoted whole command say,
            # is a filter this cannot see: fail closed rather than skip it.
            for word in words:
                if "--tests" in word and word != "--tests" and not word.startswith("--tests="):
                    unreadable.append(" ".join(words))
            for value in option_values(words, "--tests"):
                if not CLASS_NAME.fullmatch(value):
                    unreadable.append(" ".join(words))
                    continue
                named.append(value)
                if gradle and records(words):
                    recorded.append(value)
    return recorded, named, unreadable


def code_only(text):
    """The text with comments and string literals blanked out.

    So a commented-out `class GoneScreenshotTest` after a rename, or a KDoc
    or string mentioning one, is not read as a declaration (Codex, PR #322).
    Handles `//` and nested `/* */` comments, `"…"` and `\"\"\"…\"\"\"`
    strings, and char literals, which is all the declaration scan needs: it
    only has to know which `class` keywords are code.
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            out.append(" ")
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end < 0 else end + 3
            out.append(" ")
        elif text[i] in "\"'":
            # A string or a char literal, so `'"'` cannot open a fake string.
            quote, i = text[i], i + 1
            while i < n and text[i] not in quote + "\n":
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append(" ")
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def declared_classes(test_roots):
    """Every class the Kotlin and Java test sources declare, fully qualified.

    Read from each file's `package` and `class` declarations rather than
    derived from file names: Kotlin does not tie a class to a file of the
    same name, and Gradle's `--tests` matches the class (Codex, PR #322).
    """
    declared = set()
    sources = (p for root in test_roots for p in root.rglob("*") if p.suffix in (".kt", ".java"))
    for path in sorted(sources):
        text = code_only(path.read_text(encoding="utf-8"))
        package = PACKAGE.search(text)
        prefix = package.group(1) + "." if package else ""
        declared.update(prefix + name for name in CLASS.findall(text))
    return declared


def check(repo, workflow=None):
    """Returns the problems found, one message per class.

    `workflow` defaults to the one in `repo`. CI passes the merge revision's
    instead: the steps that run come from there, while the classes that
    compile come from the head branch the job checks out (Codex, PR #322).
    """
    workflow = workflow or repo / WORKFLOW
    try:
        lines = job_lines(workflow.read_text(encoding="utf-8"), JOB)
    except OSError as error:
        return [f"cannot read {WORKFLOW}: {error}"]
    if lines is None:
        return [f"no `{JOB}` job found in {WORKFLOW}; was it renamed?"]
    recorded, named, unreadable = listed_classes(lines)
    if unreadable:
        return [
            f"cannot read a `--tests` filter in {WORKFLOW}'s `{JOB}` job as one class name: {line}"
            for line in unreadable
        ]
    if not recorded:
        # A parse that finds nothing would pass every check below.
        return [
            f"no recording `--tests` steps found in {WORKFLOW}'s `{JOB}` job; "
            "has the step format changed?"
        ]

    roots = [repo / root for root in TEST_ROOTS if (repo / root).is_dir()]
    if not roots:
        return [f"no test sources found under {TEST_ROOTS[0]}"]
    try:
        declared = declared_classes(roots)
    except OSError as error:
        return [f"cannot read the test sources: {error}"]

    problems = []
    for name in named:
        if name not in declared:
            problems.append(
                f"{name} is named by a screenshot step in {WORKFLOW} but no Play debug "
                "unit-test source declares it (renamed? branch behind main?)"
            )

    listed_set = set(recorded)
    for name in sorted(n for n in declared if n.endswith("ScreenshotTest")):
        if name not in listed_set:
            problems.append(
                f"{name} has no recording step in {WORKFLOW}'s screenshot job, so it "
                "records nothing in CI; add a `Run … screenshot tests` step for it"
            )
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=Path(__file__).resolve().parent.parent, type=Path)
    parser.add_argument("--workflow", type=Path, help=f"default: REPO/{WORKFLOW}")
    args = parser.parse_args(argv)
    problems = check(args.repo, args.workflow)
    for problem in problems:
        # The annotation puts it at the top of the run summary, not in the log.
        print(f"::error::{problem}")
    if not problems:
        print("Every screenshot step names a real class, and every screenshot class has a step.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
