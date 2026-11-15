"""Rebuild the checked-in native VectorDrawable widget-picker artwork.

Run with Python 3.9+ and fonttools==4.60.1 in an external virtual environment.
The font is the existing OFL-licensed test fixture; no TTF is shipped in the APK.
Use --check to verify regeneration without changing files.
"""

from argparse import ArgumentParser
from pathlib import Path
from xml.etree import ElementTree

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "app/src/test/resources/clock-font-fixtures/Roboto-VF.ttf"
RES = ROOT / "app/src/main/res"
WIDTH, HEIGHT = 200, 112
CAPTIONS = {
    "de": ("Mo., 15. Juni", "Oslo", "Tokio", "Mo."),
    "en": ("Mon, 15 Jun", "Oslo", "Tokyo", "Mon"),
    "es": ("lun, 15 jun", "Oslo", "Tokio", "lun"),
    "fr": ("lun. 15 juin", "Oslo", "Tokyo", "lun."),
    "it": ("lun 15 giu", "Oslo", "Tokyo", "lun"),
    "pl": ("pon., 15 cze", "Oslo", "Tokio", "pon."),
    "pt": ("seg., 15 jun.", "Oslo", "Tóquio", "seg."),
    "ru": ("пн, 15 июн.", "Осло", "Токио", "пн"),
    "uk": ("пн, 15 черв.", "Осло", "Токіо", "пн"),
}


def number(value):
    return f"{value:.3f}".rstrip("0").rstrip(".") or "0"


class Outline:
    def __init__(self, weight):
        self.font = instantiateVariableFont(TTFont(FONT), {"wght": weight, "wdth": 100})
        self.glyphs = self.font.getGlyphSet()
        self.cmap = self.font.getBestCmap()
        self.units = self.font["head"].unitsPerEm

    def width(self, text, size):
        return sum(self.glyphs[self.cmap[ord(char)]].width for char in text) * size / self.units

    def fit(self, text, size, limit):
        if self.width(text, size) <= limit:
            return text
        while text and self.width(text + "\u2026", size) > limit:
            text = text[:-1]
        return text + "\u2026"

    def path(self, text, size, x, baseline):
        pen = SVGPathPen(self.glyphs, ntos=number)
        scale = size / self.units
        for char in text:
            name = self.cmap[ord(char)]
            self.glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
            x += self.glyphs[name].width * scale
        return pen.getCommands()


def rounded_rect(x, y, width, height, radius):
    a, b, c, d = x + width, y + height, x + radius, y + radius
    return (f"M{c},{y}H{a-radius}Q{a},{y} {a},{d}V{b-radius}"
            f"Q{a},{b} {a-radius},{b}H{c}Q{x},{b} {x},{b-radius}V{d}Q{x},{y} {c},{y}Z")


def path(color, commands):
    return f'    <path android:fillColor="{color}" android:pathData="{commands}" />'


def preview(kind, language, time_font, date_font, world_font):
    clock_date, first_city, second_city, weekday = CAPTIONS[language]
    parts = [path("#FF303037", rounded_rect(0, 0, WIDTH, HEIGHT, 18))]
    if kind == "clock":
        parts += [path("#FFFFFFFF", time_font.path("10:08", 56, 16, 64)),
                  path("#FFE2E2E6", date_font.path(clock_date, 16, 20, 86))]
    else:
        parts.append(path("#66000000", rounded_rect(10, 8, 180, 96, 18)))
        names = (first_city, second_city)
        dates = (f"{weekday}, +00", f"{weekday}, +07")
        for index, (name, date, time) in enumerate(zip(names, dates, ("00:08", "07:08"))):
            y = 14 + index * 44
            foreground = "#FFC6C6C6" if index == 0 else "#FF292929"
            parts.append(path("#FF111111" if index == 0 else "#FFFFFFFF",
                              rounded_rect(16, y, 168, 40, 13)))
            time_x = 176 - world_font.width(time, 28)
            city = world_font.fit(name, 12, time_x - 29)
            parts += [path(foreground, world_font.path(city, 12, 24, y + 17)),
                      path(foreground, world_font.path(date, 10, 24, y + 31)),
                      path(foreground, world_font.path(time, 28, time_x, y + 30))]
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="200dp" android:height="112dp"\n'
            '    android:viewportWidth="200" android:viewportHeight="112">\n'
            + "\n".join(parts) + '\n</vector>\n')


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    time_font, date_font, world_font = Outline(400), Outline(350), Outline(450)
    for language in CAPTIONS:
        directory = RES / ("drawable" if language == "en" else f"drawable-{language}")
        for kind in ("clock", "world"):
            target = directory / f"widget_picker_{kind}_preview.xml"
            data = preview(kind, language, time_font, date_font, world_font).encode("utf-8")
            if args.check:
                if not target.exists() or target.read_bytes() != data:
                    raise SystemExit(f"Stale picker artwork: {target.relative_to(ROOT)}")
            else:
                directory.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
