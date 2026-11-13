#!/usr/bin/env python3
"""生成 Blowfish 初始 P 数组与 S 盒（π 的十六进制小数位）。

Blowfish 的 P/S 初值就是 π 小数部分的前 1042 个 32-bit 字。这里用
Chudnovsky 公式算到足够精度，校验前 18 个字与公开 P 数组一致后，
输出 Kotlin 常量——避免手抄 1042 个魔数。

用法：python tools/gen_blowfish_tables.py
"""
from decimal import Decimal, getcontext
from pathlib import Path

HEX_DIGITS = 18 * 8 + 4 * 256 * 8  # P:18 words + S:4*256 words
PRECISION = int(HEX_DIGITS * 1.21) + 100

EXPECTED_P18 = [
    0x243F6A88, 0x85A308D3, 0x13198A2E, 0x03707344, 0xA4093822, 0x299F31D0,
    0x082EFA98, 0xEC4E6C89, 0x452821E6, 0x38D01377, 0xBE5466CF, 0x34E90C6C,
    0xC0AC29B7, 0xC97C50DD, 0x3F84D5B5, 0xB5470917, 0x9216D5D9, 0x8979FB1B,
]


def pi_hex_fraction(n_hex: int) -> str:
    getcontext().prec = PRECISION
    c = 426880 * Decimal(10005).sqrt()
    m, l, x, k = 1, 13591409, 1, 6
    s = Decimal(l)
    terms = PRECISION // 14 + 10
    for i in range(1, terms):
        m = (k**3 - 16 * k) * m // i**3
        l += 545140134
        x *= -262537412640768000
        s += Decimal(m * l) / x
        k += 12
    pi = c / s
    scaled = int(pi * (16**n_hex))
    return format(scaled, "x").rjust(n_hex, "0")[-n_hex:]


def main() -> None:
    h = pi_hex_fraction(HEX_DIGITS)
    words = [int(h[i:i + 8], 16) for i in range(0, HEX_DIGITS, 8)]
    assert len(words) == 18 + 4 * 256, len(words)
    assert words[:18] == EXPECTED_P18, [hex(w) for w in words[:18]]

    lines = []
    lines.append("package com.lumina.ble\n")
    lines.append("/**")
    lines.append(" * Blowfish 初始 P 数组与 S 盒（π 的十六进制小数位，公开算法常量）。")
    lines.append(" * 由 `tools/gen_blowfish_tables.py` 生成并校验前 18 字与公开 P 数组一致；勿手改。")
    lines.append(" */")
    lines.append("internal object BlowfishTables {\n")
    lines.append("    val P: IntArray = intArrayOf(")
    lines.extend(_emit(words[:18]))
    lines.append("    )\n")
    for box in range(4):
        lines.append(f"    val S{box}: IntArray = intArrayOf(")
        lines.extend(_emit(words[18 + box * 256:18 + (box + 1) * 256]))
        lines.append("    )\n")
    lines.append("}")
    out = Path(__file__).resolve().parent.parent / "app/src/main/java/com/lumina/ble/BlowfishTables.kt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size} bytes)")


def _emit(values):
    out = []
    for i in range(0, len(values), 6):
        chunk = values[i:i + 6]
        out.append("        " + ", ".join(str(_to_u32(v)) for v in chunk) + ",")
    out[-1] = out[-1].rstrip(",")
    return out


def _to_u32(v: int) -> int:
    # Kotlin Int 字面量超过 0x7FFFFFFF 会溢出；输出有符号等价写法。
    return v if v <= 0x7FFFFFFF else v - 0x100000000


if __name__ == "__main__":
    main()
