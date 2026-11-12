"""Builds app/src/main/assets/index.html from the shared chart page.

The chart page is the same file that is published to claude.ai. Two lines in it
are platform-only and are stripped here: the Google Fonts stylesheet (the app
carries no network permission) and the viewport-fit hint the artifact host
consumes. Everything else, including the Sync panel, is shared verbatim.

shim.js is NOT inlined -- it is a sibling asset, so it can be edited on its own.
"""

import io
import re
import sys

SRC = "/home/claude/routine/ward-chart.html"
OUT = "/home/claude/android/WardChart/app/src/main/assets/index.html"

# Mirrors the reset the artifact host injects, so the page sees the same
# baseline in both places.
SKELETON_CSS = """
:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}
html,body{margin:0}
body{font:14px system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}
img{max-width:100%}
[hidden]{display:none!important}
"""

page = io.open(SRC, encoding="utf-8").read()

stripped = 0
for pattern in (
    r'^<link rel="stylesheet" href="https://fonts\.googleapis\.com[^\n]*\n',
    r'^<meta name="claude:viewport-fit-sides"[^\n]*\n',
):
    page, n = re.subn(pattern, "", page, flags=re.M)
    stripped += n
if stripped != 2:
    print("WARN: stripped %d of 2 platform-only lines" % stripped, file=sys.stderr)

doc = (
    '<!doctype html>\n<html lang="en">\n<head>\n'
    '<meta charset="utf-8">\n'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
    "<style>" + SKELETON_CSS + "</style>\n"
    '<script src="shim.js"></script>\n'
    "</head>\n<body>\n" + page + "\n</body>\n</html>\n"
)

io.open(OUT, "w", encoding="utf-8").write(doc)
print("wrote %s (%d bytes)" % (OUT, len(doc.encode("utf-8"))))
