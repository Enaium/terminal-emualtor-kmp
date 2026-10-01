#!/usr/bin/env python3
"""Generate WidthTables.kt for the terminal-unicode module.

Downloads the official Unicode Character Database files and emits compact,
sorted, binary-searchable range tables used by Unicode.kt / Graphemes.kt.

Usage:
    python3 tools/generate_tables.py                 # print Kotlin to stdout
    python3 tools/generate_tables.py --output PATH   # write Kotlin to PATH
    python3 tools/generate_tables.py --ucd-dir DIR   # use already-downloaded files
                                                     # (EastAsianWidth.txt,
                                                     #  UnicodeData.txt,
                                                     #  emoji-data.txt)

Only the Python standard library is used.
"""

from __future__ import annotations

import argparse
import os
import sys
import urllib.request

BASE_URL = "https://www.unicode.org/Public/UCD/latest/ucd/"
FILES = {
    "EastAsianWidth.txt": "EastAsianWidth.txt",
    "UnicodeData.txt": "UnicodeData.txt",
    "emoji-data.txt": "emoji/emoji-data.txt",
}

# Zero-width cases that are not Mn/Me/Cf in UnicodeData.txt (or that we want
# to be explicit about). Kept in sync with the module contract.
EXTRA_ZERO_WIDTH = [
    (0x200B, 0x200F),
    (0x2028, 0x202E),
    (0x2060, 0x2064),
]


def fetch(name: str, ucd_dir: str | None) -> str:
    if ucd_dir:
        local = os.path.join(ucd_dir, name)
        if not os.path.exists(local):
            local = os.path.join(ucd_dir, os.path.basename(name))
        with open(local, "r", encoding="utf-8") as fh:
            return fh.read()
    url = BASE_URL + name
    with urllib.request.urlopen(url, timeout=60) as resp:
        return resp.read().decode("utf-8")


def parse_unicode_version(text: str, fallback: str) -> str:
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#") and ".txt" in line:
            # e.g. "# EastAsianWidth-16.0.0.txt"
            token = line.split()[1]
            if "-" in token and token.endswith(".txt"):
                return token.rsplit("-", 1)[1][: -len(".txt")]
    return fallback


def parse_code_point(spec: str) -> int:
    return int(spec, 16)


def parse_range_spec(spec: str) -> tuple[int, int]:
    spec = spec.strip()
    if ".." in spec:
        a, b = spec.split("..", 1)
        return parse_code_point(a), parse_code_point(b)
    v = parse_code_point(spec)
    return v, v


def merge(ranges: list[tuple[int, int]]) -> list[tuple[int, int]]:
    if not ranges:
        return []
    ranges = sorted(ranges)
    out = [list(ranges[0])]
    for start, end in ranges[1:]:
        if start <= out[-1][1] + 1:
            if end > out[-1][1]:
                out[-1][1] = end
        else:
            out.append([start, end])
    return [(a, b) for a, b in out]


def parse_east_asian_width(text: str) -> list[tuple[int, int]]:
    ranges = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        spec, _, width = line.partition(";")
        width = width.strip()
        if width in ("W", "F"):
            ranges.append(parse_range_spec(spec))
    return merge(ranges)


def parse_categories(text: str, categories: set[str]) -> list[tuple[int, int]]:
    ranges = []
    pending_first = None
    for line in text.splitlines():
        if not line:
            continue
        fields = line.split(";")
        if len(fields) < 3:
            continue
        cp = parse_code_point(fields[0])
        name = fields[1]
        category = fields[2]
        if name.endswith(", First>"):
            pending_first = cp
            continue
        if name.endswith(", Last>"):
            if pending_first is not None:
                if category in categories:
                    ranges.append((pending_first, cp))
                pending_first = None
            continue
        if category in categories:
            ranges.append((cp, cp))
    return merge(ranges)


def parse_zero_width(text: str) -> list[tuple[int, int]]:
    return merge(list(EXTRA_ZERO_WIDTH) + parse_categories(text, {"Mn", "Me", "Cf"}))


def parse_spacing_mark(text: str) -> list[tuple[int, int]]:
    return parse_categories(text, {"Mc"})


def parse_emoji(text: str, prop: str) -> list[tuple[int, int]]:
    ranges = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        spec, _, value = line.partition(";")
        if value.strip() == prop:
            ranges.append(parse_range_spec(spec))
    return merge(ranges)


def emit_table(name: str, ranges: list[tuple[int, int]], out: list[str]) -> None:
    out.append("    /** %d ranges. */" % len(ranges))
    out.append("    val %s: IntArray = intArrayOf(" % name)
    line = "       "
    for start, end in ranges:
        piece = " 0x%X, 0x%X," % (start, end)
        if len(line) + len(piece) > 116:
            out.append(line)
            line = "       "
        line += piece
    if line.strip():
        out.append(line)
    out.append("    )")
    out.append("")


def generate(eaw: str, ucd: str, emoji: str, ucd_dir: str | None) -> str:
    version = parse_unicode_version(eaw, "unknown")

    wide = parse_east_asian_width(eaw)
    zero = parse_zero_width(ucd)
    spacing = parse_spacing_mark(ucd)
    ext_pict = parse_emoji(emoji, "Extended_Pictographic")
    emoji_pres = parse_emoji(emoji, "Emoji_Presentation")

    out: list[str] = []
    out.append("// Generated by tools/generate_tables.py -- do not edit by hand.")
    out.append("//")
    out.append("// Unicode version: %s" % version)
    out.append("// Sources: EastAsianWidth.txt (W,F), UnicodeData.txt (Mn,Me,Cf,Mc),")
    out.append("//          emoji-data.txt (Extended_Pictographic, Emoji_Presentation).")
    out.append("// Regenerate:")
    out.append("//   python3 terminal-unicode/tools/generate_tables.py \\")
    out.append("//       --output terminal-unicode/src/commonMain/kotlin/cn/enaium/terminal/unicode/WidthTables.kt")
    out.append("")
    out.append("package cn.enaium.terminal.unicode")
    out.append("")
    out.append("/**")
    out.append(" * Compact sorted range tables (flattened as [start0, end0, start1, end1, ...])")
    out.append(" * backing the allocation-free lookups in [Unicode] and [Graphemes].")
    out.append(" */")
    out.append("internal object WidthTables {")
    out.append("")
    emit_table("WIDE", wide, out)
    emit_table("ZERO_WIDTH", zero, out)
    emit_table("SPACING_MARK", spacing, out)
    emit_table("EMOJI", ext_pict, out)
    emit_table("EMOJI_PRESENTATION", emoji_pres, out)
    out.append("}")
    out.append("")
    out.append("/** Binary search over a flattened, sorted, inclusive range table. */")
    out.append("internal fun rangeContains(ranges: IntArray, cp: Int): Boolean {")
    out.append("    var lo = 0")
    out.append("    var hi = ranges.size / 2 - 1")
    out.append("    while (lo <= hi) {")
    out.append("        val mid = (lo + hi) ushr 1")
    out.append("        val start = ranges[mid * 2]")
    out.append("        val end = ranges[mid * 2 + 1]")
    out.append("        when {")
    out.append("            cp < start -> hi = mid - 1")
    out.append("            cp > end -> lo = mid + 1")
    out.append("            else -> return true")
    out.append("        }")
    out.append("    }")
    out.append("    return false")
    out.append("}")
    out.append("")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", help="write the Kotlin file here (default: stdout)")
    parser.add_argument("--ucd-dir", help="directory containing already-downloaded UCD files")
    args = parser.parse_args()

    texts = {name: fetch(remote, args.ucd_dir) for name, remote in FILES.items()}
    kotlin = generate(
        texts["EastAsianWidth.txt"],
        texts["UnicodeData.txt"],
        texts["emoji-data.txt"],
        args.ucd_dir,
    )

    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(kotlin)
    else:
        sys.stdout.write(kotlin)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
