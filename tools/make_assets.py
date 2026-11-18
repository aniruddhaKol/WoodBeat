"""Builds app drawables (src/main/res/drawable-nodpi) from the art in images/.

Run:  python tools/make_assets.py      (needs Pillow + numpy; Edge renders the SVG glyphs)
"""
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
IMG = ROOT / "images"
OUT = ROOT / "src" / "main" / "res" / "drawable-nodpi"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
OUT.mkdir(parents=True, exist_ok=True)


def save(im, name):
    im.save(OUT / f"{name}.png", optimize=True)
    print(f"{name}.png {im.size}")


def fit_w(im, w):
    return im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)


def rounded_cut(im, box, radius):
    """Crop box and make everything outside a rounded rectangle transparent (removes baked backgrounds)."""
    c = im.convert("RGBA").crop(box)
    mask = Image.new("L", c.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, c.width - 1, c.height - 1), radius, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))
    a = np.minimum(np.array(c)[..., 3], np.array(mask))
    c.putalpha(Image.fromarray(a.astype(np.uint8)))
    return c


def key_black(im, lo=12, hi=48):
    """Black background -> transparent, with a soft ramp so the edge glow survives."""
    a = np.array(im.convert("RGBA")).astype(np.float32)
    v = a[..., :3].max(axis=2)
    alpha = np.clip((v - lo) / (hi - lo), 0, 1) * 255
    a[..., 3] = np.minimum(a[..., 3], alpha)
    return Image.fromarray(a.astype(np.uint8))


def feather(im, px):
    """Fades the outer px of an image to transparent."""
    w, h = im.size
    x = np.minimum(np.arange(w), np.arange(w)[::-1]) / px
    y = np.minimum(np.arange(h), np.arange(h)[::-1]) / px
    f = np.clip(np.minimum.outer(y, x), 0, 1)
    a = np.array(im.convert("RGBA")).astype(np.float32)
    a[..., 3] *= f
    return Image.fromarray(a.astype(np.uint8))


# Backgrounds / frames
save(Image.open(IMG / "1_new.png").convert("RGB").resize((512, 512), Image.LANCZOS), "wood_tile")
save(Image.open(IMG / "2.png").convert("RGBA").resize((512, 512), Image.LANCZOS), "frame_raised")
save(Image.open(IMG / "4_1.png").convert("RGBA").resize((512, 512), Image.LANCZOS), "frame_inset")

# Plaque: normal (top) and pressed (bottom) from the sheet
sheet = Image.open(IMG / "4_2.png")
save(fit_w(rounded_cut(sheet, (150, 58, 2024, 324), 62), 480), "plaque")
save(fit_w(rounded_cut(sheet, (150, 384, 2024, 652), 62), 480), "plaque_pressed")

# Square button: normal (left) and pressed (right)
sheet = Image.open(IMG / "5_1.png")
save(rounded_cut(sheet, (122, 80, 838, 790), 118).resize((256, 254), Image.LANCZOS), "button")
save(rounded_cut(sheet, (935, 80, 1651, 790), 118).resize((256, 254), Image.LANCZOS), "button_pressed")

# Slider thumb (vertical pill on black)
thumb = key_black(Image.open(IMG / "9.png").crop((320, 265, 704, 1235)))
save(thumb.resize((96, round(970 * 96 / 384)), Image.LANCZOS), "thumb")

save(Image.open(IMG / "WoodBeat_Slider_Track_256x32.png").convert("RGBA"), "track")
save(Image.open(IMG / "WoodBeat_EQ_Switch_ON_192x96.png").convert("RGBA"), "switch_on")
save(Image.open(IMG / "WoodBeat_EQ_Switch_OFF_192x96.png").convert("RGBA"), "switch_off")
save(Image.open(IMG / "WoodBeat_Header_Ornament_One_Side_384x96.png").convert("RGBA"), "ornament")
# Logo: trim the empty wood above/below the lettering (art sits in rows ~45-190) so it fills a short header row
save(feather(Image.open(IMG / "WoodBeat_Logo_1024x256.png").crop((0, 32, 1024, 200)), 22), "logo")
save(Image.open(IMG / "WoodBeat_Album_Placeholder_512x512.png").convert("RGB").resize((256, 256), Image.LANCZOS), "album_placeholder")
save(Image.open(IMG / "WoodBeat_Adaptive_Foreground_Corrected_432x432.png").convert("RGBA"), "launcher_fg")
save(Image.open(IMG / "WoodBeat_Adaptive_Background_Corrected_432x432.png").convert("RGB"), "launcher_bg")

# Maker's stamp: chalk-grey on white -> ivory ink on transparency (the app draws it at 15 % opacity)
g = np.array(Image.open(IMG / "stamp_original.jpg").convert("L")).astype(np.float32)
ink = np.clip((232 - g) / 70, 0, 1)                       # white paper -> 0, grey chalk -> solid (strong contrast)
ys, xs = np.where(ink > 0.05)
ink = ink[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
# Thicken the chalk strokes a little so the stamp stays readable at ~60 dp on a phone
ink = np.array(Image.fromarray((ink * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))).astype(np.float32) / 255
st = np.zeros(ink.shape + (4,), np.uint8)
st[..., 0], st[..., 1], st[..., 2] = 0xFF, 0xEC, 0xC8      # bright cream
st[..., 3] = (ink * 255).astype(np.uint8)
stamp = Image.fromarray(st)
save(stamp.resize((480, round(stamp.height * 480 / stamp.width)), Image.LANCZOS), "stamp")

# Glyph SVGs -> 192 px PNG (keeps the drop shadows that Android vectors would drop)
GLYPHS = {
    "previous": "Previous", "play": "Play", "pause": "Pause", "stop": "Stop", "next": "Next",
    "shuffle": "Shuffle", "repeat": "Repeat", "overflow": "Overflow", "chevron": "Chevron",
    "song": "Song", "album": "Album", "artist": "Artist_96x96_Fixed", "folder": "Folder",
    "playlist": "Playlist_96x96_Polished", "genre": "Genre", "radio": "Radio",
}
with tempfile.TemporaryDirectory() as tmp:
    for name, src in GLYPHS.items():
        svg = IMG / (f"WoodBeat_Glyph_{src}.svg" if src.endswith("Fixed") or src.endswith("Polished")
                     else f"WoodBeat_Glyph_{src}_96x96.svg")
        html = Path(tmp) / f"{name}.html"
        html.write_text(f'<html><body style="margin:0;background:transparent">'
                        f'<img src="{svg.as_uri()}" width="192" height="192"></body></html>')
        png = Path(tmp) / f"{name}.png"
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--default-background-color=00000000", "--window-size=192,192",
                        f"--screenshot={png}", html.as_uri()], capture_output=True, timeout=60)
        save(Image.open(png).convert("RGBA"), f"glyph_{name}")
