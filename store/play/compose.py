"""Compose Google Play listing images from real captures of the app.
Usage: uv run --with pillow python compose.py [shots.json]
Phone screenshots 1080x1920 (9:16), feature graphic 1024x500, icon 512x512. All 24-bit PNG, no alpha
(the icon is written as 32-bit as Play asks). Same palette and fonts as the README video."""
import json, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
V = Path(__file__).resolve().parent; A = V / "assets"; RAW = V / "raw"; O = V / "out"; O.mkdir(exist_ok=True)
BG, TEXT, MUTED, ACC, OUTL = "#0F0E0D", "#F2EEE8", "#ABA397", "#FFB547", "#3A3631"
CROP_TOP = 100                                   # status bar: carrier, clock, notification icons
def font(name, size): return ImageFont.truetype(str(A / f"{name}.ttf"), size)
def wrap(d, text, f, width):
    lines = []
    for para in text.split("\n"):
        cur = ""
        for w in para.split():
            t = (cur + " " + w).strip()
            if d.textlength(t, font=f) <= width: cur = t
            else: lines.append(cur); cur = w
        lines.append(cur)
    return lines
def glow(img, cx, cy, r, alpha):
    g = Image.new("RGBA", img.size, (0, 0, 0, 0)); ImageDraw.Draw(g).ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 181, 71, alpha))
    out = Image.alpha_composite(img, g.filter(ImageFilter.GaussianBlur(r // 2))).convert("RGB")
    return ImageChops.add(out, Image.effect_noise(out.size, 3).convert("RGB"), 1, -128).convert("RGBA")
def capture(name, crop_bottom=0):
    im = Image.open(RAW / name).convert("RGB")
    return im.crop((0, CROP_TOP, im.width, im.height - crop_bottom))
def phone(img, cap, x, y, w, bez=16, r_out=70, r_in=56):
    """Paste a capture scaled to width w inside a bezel at (x, y); it may run off the canvas."""
    h = round(cap.height * w / cap.width); cap = cap.resize((w, h), Image.LANCZOS)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([x - bez, y - bez, x + w + bez, y + h + bez], r_out, fill="#1B1917", outline=OUTL, width=2)
    mask = Image.new("L", (w, h), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, w, h], r_in, fill=255)
    img.paste(cap, (x, y), mask)
def screenshot(s, n):
    W, H = 1080, 1920; img = Image.new("RGBA", (W, H), BG)
    pw = s.get("phone_w", 800); px = (W - pw) // 2
    d = ImageDraw.Draw(img); fl, fh, fs = font("inter_semibold", 30), font("inter_bold", s.get("head_size", 78)), font("inter_regular", 38)
    width = W - 2 * 90; hl = wrap(d, s["head"], fh, width); sl = wrap(d, s.get("sub", ""), fs, width) if s.get("sub") else []
    lh, ls = round(fh.size * 1.16), 52
    text_h = (56 if s.get("label") else 0) + len(hl) * lh + (22 + len(sl) * ls if sl else 0)
    py = 110 + text_h + 80
    img = glow(img, W // 2, py + 620, 560, 46); d = ImageDraw.Draw(img)
    x, y = 90, 110
    if s.get("label"):
        d.rounded_rectangle([x, y + 4, x + 7, y + 34], 3, fill=ACC); d.text((x + 24, y), s["label"].upper(), font=fl, fill=ACC, features=["-kern"]); y += 56
    for l in hl: d.text((x, y), l, font=fh, fill=TEXT); y += lh
    y += 22
    for l in sl: d.text((x, y), l, font=fs, fill=MUTED); y += ls
    phone(img, capture(s["img"], s.get("crop_bottom", 0)), px, py, pw)
    out = O / f"phone-{n}-{s['id']}.png"; img.convert("RGB").save(out, optimize=True); return out
def feature(f):
    W, H = 1024, 500; img = glow(Image.new("RGBA", (W, H), BG), 800, 260, 300, 46); d = ImageDraw.Draw(img)
    mark = Image.open(A / "mark.png").convert("RGBA"); mh = 84; mark = mark.resize((round(mark.width * mh / mark.height), mh), Image.LANCZOS)
    img.alpha_composite(mark, (64, 92)); d = ImageDraw.Draw(img)
    d.text((64 + mark.width + 22, 100), "DistillPod", font=font("inter_bold", 60), fill=TEXT)
    fh, fs = font("inter_bold", 44), font("inter_regular", 25); y = 214
    for l in wrap(d, f["head"], fh, 560): d.text((64, y), l, font=fh, fill=TEXT); y += 54
    y += 14
    for l in wrap(d, f.get("sub", ""), fs, 540): d.text((64, y), l, font=fs, fill=MUTED); y += 35
    phone(img, capture(f["img"]), 700, 56, 250, bez=8, r_out=30, r_in=22)
    out = O / "feature-graphic-1024x500.png"; img.convert("RGB").save(out, optimize=True); return out
def icon(src):
    im = Image.open(src).convert("RGBA").resize((512, 512), Image.LANCZOS); out = O / "icon-512.png"; im.save(out, optimize=True); return out
if __name__ == "__main__":
    spec = json.loads((V / (sys.argv[1] if len(sys.argv) > 1 else "shots.json")).read_text())
    for i, s in enumerate(spec["screenshots"], 1): print(screenshot(s, i))
    if spec.get("feature"): print(feature(spec["feature"]))
    if spec.get("icon"): print(icon(spec["icon"]))
