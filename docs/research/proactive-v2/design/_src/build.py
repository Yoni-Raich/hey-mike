#!/usr/bin/env python3
"""Assemble the self-contained mockups: every page in _src/pages is copied to the design
folder with its `parts/*` includes inlined. Standard library only.

    python _src/build.py           # build all
Includes:  <!--@include parts/x.html-->   or   /*@include parts/x.css*/
Pages ending in .tpl.html are templates; see TEMPLATES below."""
import re, sys
from pathlib import Path

SRC = Path(__file__).resolve().parent
OUT = SRC.parent
INC = re.compile(r"(?:<!--|/\*)@include\s+(\S+?)\s*(?:-->|\*/)")


def inline(text: str) -> str:
    def repl(m):
        return (SRC / m.group(1)).read_text(encoding="utf-8")
    prev = None
    while prev != text:          # includes may nest
        prev, text = text, INC.sub(repl, text)
    return text


# name -> list of (output file, {placeholder: value}); filled by members.py when present
TEMPLATES = {}
try:
    sys.path.insert(0, str(SRC))
    from members import TEMPLATES as _T  # type: ignore
    TEMPLATES.update(_T)
except ImportError:
    pass


def main():
    built = []
    for page in sorted((SRC / "pages").glob("*.html")):
        if page.name.endswith(".tpl.html"):
            for out_name, values in TEMPLATES.get(page.name, []):
                text = inline(page.read_text(encoding="utf-8"))
                for key, val in values.items():
                    text = text.replace("{{" + key + "}}", val)
                (OUT / out_name).write_text(text, encoding="utf-8")
                built.append(out_name)
            continue
        (OUT / page.name).write_text(inline(page.read_text(encoding="utf-8")), encoding="utf-8")
        built.append(page.name)
    print("built:", ", ".join(built))


if __name__ == "__main__":
    main()
