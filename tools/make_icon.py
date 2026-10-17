"""Builds the launcher icons for one app from its picture in icons/<id>.png|jpg.

Makes an adaptive icon (Android 8+: the picture on a matching background, shaped by the launcher)
and classic square/round icons for older phones. Without a picture, draws the app's label on its colour.

Usage: python3 tools/make_icon.py <id> <label> <#RRGGBB> <res dir>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SCALE = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
FONTS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"]


def find_picture(app_id):
    for ext in ("png", "jpg", "jpeg", "webp"):
        p = os.path.join("icons", f"{app_id}.{ext}")
        if os.path.exists(p):
            return Image.open(p).convert("RGB")
    return None


def label_picture(label, color, size=512):
    img = Image.new("RGB", (size, size), color)
    d = ImageDraw.Draw(img)
    text = label[:3]
    px = int(size * (0.46 if len(text) <= 1 else 0.36 if len(text) == 2 else 0.28))
    font = next((ImageFont.truetype(f, px) for f in FONTS if os.path.exists(f)), None) or ImageFont.load_default(size=px)
    box = d.textbbox((0, 0), text, font=font)
    d.text(((size - box[2] + box[0]) / 2 - box[0], (size - box[3] + box[1]) / 2 - box[1]), text, font=font, fill="white")
    return img


def square(img):
    w, h = img.size
    s = min(w, h)
    return img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))


def edge_color(img):
    """The picture's own background colour, sampled along its border, so the adaptive background matches."""
    px = img.load()
    w, h = img.size
    pts = [(x, 0) for x in range(0, w, 4)] + [(x, h - 1) for x in range(0, w, 4)] + [(0, y) for y in range(0, h, 4)] + [(w - 1, y) for y in range(0, h, 4)]
    r = sorted(px[p][0] for p in pts)[len(pts) // 2]
    g = sorted(px[p][1] for p in pts)[len(pts) // 2]
    b = sorted(px[p][2] for p in pts)[len(pts) // 2]
    return "#%02X%02X%02X" % (r, g, b)


def masked(img, size, shape):
    big = size * 4
    pic = img.resize((big, big), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    if shape == "round":
        d.ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    pic.putalpha(mask)
    return pic.resize((size, size), Image.LANCZOS)


def main():
    app_id, label, color, res = sys.argv[1:5]
    pic = find_picture(app_id)
    pic = square(pic) if pic is not None else label_picture(label, color)
    bg = edge_color(pic)

    for density, size in LEGACY.items():
        folder = os.path.join(res, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        masked(pic, size, "square").save(os.path.join(folder, "ic_launcher.png"))
        masked(pic, size, "round").save(os.path.join(folder, "ic_launcher_round.png"))
        # Adaptive foreground: 108dp canvas, picture at 80dp in the middle; the launcher shows the
        # centre 72dp in its own shape, and the matching background fills any gap seamlessly.
        canvas = round(108 * SCALE[density])
        inner = round(80 * SCALE[density])
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        fg.paste(pic.resize((inner, inner), Image.LANCZOS), ((canvas - inner) // 2, (canvas - inner) // 2))
        fg.save(os.path.join(folder, "ic_launcher_foreground.png"))

    v26 = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(v26, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background" />\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(v26, n), "w").write(xml)
    values = os.path.join(res, "values")
    os.makedirs(values, exist_ok=True)
    open(os.path.join(values, "ic_launcher_background.xml"), "w").write(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{bg}</color>\n</resources>\n')
    print(f"icons for {app_id}: {'picture' if find_picture(app_id) else 'label'}, background {bg}")


if __name__ == "__main__":
    main()
