#!/usr/bin/env python3
"""Render the mockups to PNG with headless Edge (or Chrome). Standard library only.

    python _src/render.py                 # every job
    python _src/render.py board manager   # only jobs whose name contains one of these words
Each job: (png name, html file, query string, width, height or 'auto').
'auto' measures the page height first (the page writes it into <title>)."""
import re, subprocess, sys, tempfile, shutil
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent
BROWSERS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
]

# (png, html, query, width, height)
JOBS = [
    ("orb-faces", "orb-faces.html", "still", 1260, "auto"),
    ("orb-faces-light", "orb-faces.html", "still&theme=light", 1260, "auto"),
]
try:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from jobs import JOBS as _J  # type: ignore
    JOBS = _J
except ImportError:
    pass


def browser():
    for b in BROWSERS:
        if Path(b).exists():
            return b
    raise SystemExit("no Edge or Chrome found")


def run(args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)


def render(job, exe, profile):
    name, html, query, w, h = job
    url = (OUT / html).resolve().as_uri() + ("?" + query if query else "")
    common = [exe, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
              "--force-device-scale-factor=2", "--run-all-compositor-stages-before-draw", "--virtual-time-budget=3000"]
    if h == "auto":
        dom = run(common[:-1] + [f"--window-size={w},915", "--dump-dom", url]).stdout
        m = re.search(r"<title>(\d+)</title>", dom)
        h = int(m.group(1)) if m else 915
    png = OUT / f"{name}.png"
    r = run(common + [f"--window-size={w},{h}", f"--screenshot={png}", url])
    ok = png.exists()
    print(("ok   " if ok else "FAIL ") + name, f"{w}x{h}")
    if not ok:
        print(r.stderr[-400:])


def main():
    words = sys.argv[1:]
    exe = browser()
    profile = tempfile.mkdtemp(prefix="mike-render-")
    try:
        for job in JOBS:
            if words and not any(w in job[0] for w in words):
                continue
            render(job, exe, profile)
    finally:
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    main()
