"""List state-machine candidates in tracked production sources; read-only, no dependencies.

This is a discovery aid, not proof that every implicit machine has been found.
Line numbers point to state storage, gates, lifecycle jobs, or explicit state types.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess


SIGNALS = {
    "state_type": r"\b(?:enum\s+class|sealed\s+(?:class|interface))\b",
    "observable_state": r"\b(?:MutableStateFlow|mutableStateOf|mutableStateMapOf|mutableStateListOf)\b",
    "async_lifecycle": r"\b(?:CompletableDeferred|AtomicBoolean|AtomicLong)\b|(?:var|val)\s+\w+\s*:\s*Job\??",
    "mutable_flags": r"@Volatile|\b(?:private\s+)?var\s+\w+\s*(?::\s*Boolean|=\s*(?:true|false))\b",
    "script_state": r"\b(?:self\.\w+\s*=|threading\.Event|asyncio\.(?:Event|Lock|Future)|let\s+\w+\s*=)" ,
}


def inventory(root):
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
    files = []
    scanned = 0
    patterns = {key: re.compile(value) for key, value in SIGNALS.items()}
    for name in sorted(filter(None, paths)):
        if "/src/main/" not in name or name.startswith(("tools/", "samples/")):
            continue
        if Path(name).suffix not in {".kt", ".java", ".py", ".js"}:
            continue
        scanned += 1
        hits = []
        for number, line in enumerate((root / name).read_text(encoding="utf-8").splitlines(), 1):
            kinds = [kind for kind, pattern in patterns.items() if pattern.search(line)]
            if kinds:
                hits.append({"line": number, "signals": kinds})
        if hits:
            files.append({"path": name, "evidence": hits})
    return {"scannedFiles": scanned, "candidateFiles": len(files), "files": files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", action="store_true", help="Only show source and candidate counts")
    args = parser.parse_args()
    result = inventory(Path(__file__).resolve().parents[1])
    if args.summary:
        result.pop("files")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
