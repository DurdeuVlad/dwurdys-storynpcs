#!/usr/bin/env python3
"""Annotate mct journey screenshots: numbered step chip, caption band below,
click markers at logical->pixel mapped positions.

Usage: python .devin/skills/mct-journey/scripts/annotate.py <manifest.json> <raw_dir> <out_dir>
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CAPTION_H = 96          # px band appended below the frame
STEP_PAD = 10


def font(size):
    for name in ("segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def wrap(draw, text, fnt, max_w):
    words, line, out = text.split(), "", []
    for w in words:
        t = (line + " " + w).strip()
        if draw.textlength(t, font=fnt) <= max_w:
            line = t
        else:
            out.append(line)
            line = w
    out.append(line)
    return out


def annotate(src, dst, step, caption, clicks, session, scale):
    img = Image.open(src).convert("RGB")
    w, h = img.size
    out = Image.new("RGB", (w, h + CAPTION_H), (16, 16, 20))
    out.paste(img, (0, 0))
    d = ImageDraw.Draw(out)

    # step chip, top-left
    chip = f" {step:02d} "
    f_chip = font(34)
    cw = int(d.textlength(chip, font=f_chip)) + STEP_PAD * 2
    d.rectangle((8, 8, 8 + cw, 8 + 44), fill=(24, 24, 28), outline=(255, 200, 60), width=2)
    d.text((8 + STEP_PAD, 13), chip, font=f_chip, fill=(255, 200, 60))

    # click markers (logical coords * scale -> px)
    for i, c in enumerate(clicks or []):
        cx, cy = int(c["x"] * scale), int(c["y"] * scale)
        r = 16
        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(80, 200, 255), width=4)
        d.ellipse((cx - 4, cy - 4, cx + 4, cy + 4), fill=(80, 200, 255))
        d.text((cx + r + 4, cy - 14), str(i + 1), font=font(28), fill=(80, 200, 255))
        if c.get("label"):
            d.text((cx + r + 4, cy + 6), c["label"], font=font(18), fill=(180, 225, 255))

    # caption band
    d.line((0, h, w, h), fill=(255, 200, 60), width=2)
    d.text((14, h + 10), f"[{step:02d}]", font=font(26), fill=(255, 200, 60))
    x0 = 14 + int(d.textlength("[00]", font=font(26))) + 14
    lines = wrap(d, caption, font(22), w - x0 - 16)
    for i, ln in enumerate(lines[:3]):
        d.text((x0, h + 8 + i * 26), ln, font=font(22), fill=(225, 225, 225))
    d.text((14, h + CAPTION_H - 24), session, font=font(15), fill=(120, 120, 130))

    out.save(dst)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip().splitlines()[-1])
    manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    raw = Path(sys.argv[2])
    outdir = Path(sys.argv[3])
    outdir.mkdir(parents=True, exist_ok=True)
    session = manifest.get("session", "")
    scale = manifest.get("gui_scale", 3)
    for i, shot in enumerate(manifest["shots"], 1):
        src = raw / shot["file"]
        if not src.exists():
            print(f"SKIP {shot['file']} (missing)")
            continue
        dst = outdir / f"{i:02d}-{Path(shot['file']).stem}.png"
        annotate(src, dst, i, shot["caption"], shot.get("clicks"), session, scale)
        print(f"{dst.name}")
    print("done")


if __name__ == "__main__":
    main()
