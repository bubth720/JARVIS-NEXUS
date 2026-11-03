"""M50-S1 scope: immutable LOT3 bytes, reviewed CI route and exact additions only."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = "c5dee7ab0344a23bfd80b13cd52d09e8b26241dd"
MISSION = "c02a00e8d6829de48a212cc03ac392e7ec73c82c"
HEAD_BRANCH = "codex/m50-s1-nexus-ci-20261029"
BASE_BRANCH = "mission/m50-integrated-beta-20261029"
WORKFLOW = ".github/workflows/nex-07.yml"
ADDED = {
    "tools/check-m50-s1.py", "tests/m50_s1/test_scope.py",
    "docs/m50/M50-S1-CI.md", "missions/M50-INTEGRATION-2026-10-29.md",
}
M50_CONDITION = (
    "github.event_name == 'pull_request' && "
    "github.base_ref == 'mission/m50-integrated-beta-20261029' && "
    "github.head_ref == 'codex/m50-s1-nexus-ci-20261029' && "
    "github.event.pull_request.head.repo.full_name == github.repository"
)

def eligible(event, base, head, repository, head_repository):
    return (event == "pull_request" and base == BASE_BRANCH and
            head == HEAD_BRANCH and repository == head_repository)

def expected_workflow(original):
    env_marker = b"        env:\n          LOT3_REVIEW:"
    if original.count(env_marker) != 2:
        raise ValueError("Unexpected historical workflow env")
    checkout_marker = b"          fetch-depth: 0\n"
    if original.count(checkout_marker) != 2:
        raise ValueError("Unexpected historical checkout")
    actual = original.replace(
        checkout_marker,
        ("          ref: ${{ " + M50_CONDITION +
         " && github.event.pull_request.head.sha || github.ref }}\n").encode() + checkout_marker,
    )
    actual = actual.replace(
        env_marker,
        ("        env:\n          M50_REVIEW: ${{ " + M50_CONDITION + " }}\n"
         "          LOT3_REVIEW:").encode(),
    )
    marker = b'          if [ "$LOT3_REVIEW" = "true" ]; then\n'
    if actual.count(marker) != 2:
        raise ValueError("Unexpected historical workflow guard")
    return actual.replace(
        marker,
        b'          if [ "$M50_REVIEW" = "true" ]; then\n'
        b'            python3 tools/check-m50-s1.py\n'
        b'            python3 -m unittest discover -s tests/m50_s1 -v\n'
        b'          elif [ "$LOT3_REVIEW" = "true" ]; then\n',
    )

def verify_workflow(original, actual):
    if actual != expected_workflow(original):
        raise ValueError("Unreviewed workflow delta")

def verify_files(root, frozen, actual, workflow_before, mission_bytes):
    names = set(actual)
    if names - set(frozen) - ADDED:
        raise ValueError("Undeclared addition")
    if set(frozen) - names:
        raise ValueError("Deletion")
    if not ADDED <= names:
        raise ValueError("Required M50 evidence missing")
    for name in names:
        path = root / name
        ancestors = [root.joinpath(*Path(name).parts[:i]) for i in range(len(Path(name).parts))]
        if (Path(name).is_absolute() or ".." in Path(name).parts or
                any(parent.is_symlink() for parent in ancestors) or
                path.is_symlink() or not path.is_file()):
            raise ValueError("Missing/non-regular file: " + name)
        if name == WORKFLOW:
            verify_workflow(workflow_before, path.read_bytes())
        elif name in frozen and path.read_bytes() != frozen[name]:
            raise ValueError("Frozen source changed: " + name)
        elif name == "missions/M50-INTEGRATION-2026-10-29.md" and path.read_bytes() != mission_bytes:
            raise ValueError("Published mission changed")

def verify_modes(root, baseline_modes, modes, actual):
    for name in actual:
        expected = baseline_modes.get(name, "100644")
        if modes.get(name) != expected:
            raise ValueError("Unreviewed index mode: " + name)
        if bool((root / name).stat().st_mode & 0o111) != (expected == "100755"):
            raise ValueError("Unreviewed worktree executable mode: " + name)

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def main():
    for sha in (BASE, MISSION):
        subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"], cwd=ROOT, check=True)
    frozen = {
        p: git("cat-file", "--filters", BASE + ":" + p)
        for p in git("ls-tree", "-r", "--name-only", BASE).decode().splitlines()
    }
    actual = git("ls-files", "--cached", "--others", "--exclude-standard").decode().splitlines()
    verify_files(ROOT, frozen, actual, frozen[WORKFLOW],
                 git("cat-file", "--filters", MISSION + ":missions/M50-INTEGRATION-2026-10-29.md"))
    baseline_modes = {line.split()[3]: line.split()[0] for line in git("ls-tree", "-r", BASE).decode().splitlines()}
    modes = {line.split()[3]: line.split()[0] for line in git("ls-files", "--stage").decode().splitlines()}
    verify_modes(ROOT, baseline_modes, modes, actual)
    # Original source-sensitive tests stay at their own SHA. The LOT3 guard
    # recursively checks check-prefusion and check-nex07 in their original trees.
    with tempfile.TemporaryDirectory(prefix="nexus-m50-historical-") as temp:
        subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", str(ROOT), temp], check=True)
        subprocess.run(["git", "checkout", "--quiet", "--detach", BASE], cwd=temp, check=True)
        for command in (
            ["python3", "tools/check-prefusion-lot3.py"],
            ["python3", "-m", "unittest", "discover", "-s", "tests/prefusion_lot3", "-v"],
            ["python3", "-m", "unittest", "discover", "-s", "tests/prefusion", "-v"],
        ):
            subprocess.run(command, cwd=temp, check=True)
    print("PASS M50-S1 scope; LOT3 frozen at " + BASE + "; historical guards unchanged")
    print("Android instrumentation/physical NOT_RUN by this guard")

if __name__ == "__main__":
    main()
