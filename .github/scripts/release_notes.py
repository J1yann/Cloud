"""Append or refresh a linked commit list while preserving handwritten release notes."""
import argparse
import re
import subprocess
from pathlib import Path

START = "<!-- cloud-commits:start -->"
END = "<!-- cloud-commits:end -->"


def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.DEVNULL).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--existing", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        previous = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*",
                       "--exclude=" + args.tag, args.commit)
    except subprocess.CalledProcessError:
        previous = ""
    revision = previous + ".." + args.commit if previous else args.commit
    commits = git("log", "--reverse", "--format=%H%x00%s", revision).splitlines()
    section = [START, "## Commits", ""]
    for line in commits:
        sha, subject = line.split("\x00", 1)
        subject = re.sub(r"([\\`*_\[\]<>])", r"\\\1", subject)
        section.append(f"- {subject} ([{sha[:7]}](https://github.com/{args.repo}/commit/{sha}))")
    if not commits:
        section.append("No additional commits for this build.")
    if previous:
        section.extend(["", f"[Full changelog](https://github.com/{args.repo}/compare/{previous}...{args.tag})"])
    section.append(END)
    generated = "\n".join(section)
    existing = args.existing.read_text().strip() if args.existing.exists() else ""
    pattern = re.escape(START) + r".*?" + re.escape(END)
    if START in existing and END in existing:
        notes = re.sub(pattern, lambda _: generated, existing, count=1, flags=re.S)
    else:
        notes = existing + ("\n\n" if existing else "") + generated
    args.output.write_text(notes + "\n")


if __name__ == "__main__":
    main()
