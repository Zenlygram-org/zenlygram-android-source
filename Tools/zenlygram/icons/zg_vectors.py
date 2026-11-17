#!/usr/bin/env python3
"""Zenlygram vector resources from the iOS Z glyph.

Source: Swiftgram/SGSettingsUI/Images.xcassets/SwiftgramContextMenu.imageset/swiftgram_context_menu.pdf in the
iOS repo (a 24x24 pt PDF whose content stream fills the Z silhouette with two polygon subpaths: top bar + diagonal,
and the bottom bar). PDF space has y pointing up, Android vector space has y pointing down, so every point is
converted with y' = 24 - y.

Writes into <res>/drawable/:
  zg_glyph.xml                24dp white Z (generic glyph, same path as the notification icon)
  notification.xml            status-bar small icon used by NotificationsController / FilesMigrationService
  zg_launcher_monochrome.xml  themed-icon layer (Android 13+) of the adaptive launcher icon, 108dp canvas
  icon_plane.xml              old name of Telegram's monochrome plane; still referenced by
                              TMessagesProj_AppStandalone/.../ic_launcher_sa.xml, so it now holds the Z too
  tg_splash_320.xml           Android 12+ splash icon (values-v31 / values-night styles): black disc + Z
  zg_glyph_pro.xml            24dp light-blue Z of the "Zenlygram Pro" row in Settings (navy tile)
and into <res>/raw/:
  qr_logo.svg                 centre logo of the QR codes (TelegramQRCodeWriter): black disc + white Z (was the plane)
  calls_log.json              Lottie of the login call-log permission dialog: its round badge cuts out the Z instead
                              of Telegram's plane. Patched in place (the Telegram file is the input), so re-running is
                              safe: the badge's cut-out paths are replaced every time.

Usage: python3 -I zg_vectors.py <swiftgram_context_menu.pdf> <TMessagesProj/src/main/res>
"""
import json
import re
import sys
import zlib

GLYPH_SIZE = 24.0

# Brand colours sampled from Telegram/Telegram-iOS/Zenlygram.icon/Assets/signature_1024.png
CYAN_BRIGHT = "#FF29ECF2"   # lower-left end of the diagonal
AZURE = "#FF0592F3"         # middle of the ribbon
CYAN_LIGHT = "#FF4ADAF4"    # right end of the top bar
ROYAL_LEFT = "#FF0140E4"    # bottom bar, left
ROYAL_RIGHT = "#FF0060EE"   # bottom bar, right
PRO_BLUE = "#FF4AA3FF"      # light-blue Z of the Pro row (ZgConfig.COLOR_BRAND_BLUE_LIGHT)


def read_subpaths(pdf_path):
    data = open(pdf_path, "rb").read()
    streams = re.findall(rb"stream\r?\n(.*?)\r?\nendstream", data, re.S)
    if not streams:
        raise SystemExit("no content stream in " + pdf_path)
    content = None
    for raw in streams:
        try:
            content = zlib.decompress(raw).decode("latin1")
        except zlib.error:
            content = raw.decode("latin1")
        if " m" in content or "\nm" in content:
            break
    tokens = content.split()
    subpaths, current, stack = [], [], []
    for tok in tokens:
        if tok in ("m", "l", "c", "h", "f", "f*", "rg", "re"):
            if tok == "m":
                if current:
                    subpaths.append(current)
                current = [("M", [float(stack[0]), float(stack[1])])]
            elif tok == "l":
                current.append(("L", [float(stack[0]), float(stack[1])]))
            elif tok == "c":
                current.append(("C", [float(v) for v in stack[:6]]))
            elif tok == "h":
                current.append(("Z", []))
            elif tok == "re":
                raise SystemExit("unexpected rectangle operator in glyph PDF")
            stack = []
        else:
            stack.append(tok)
    if current:
        subpaths.append(current)
    return subpaths


def fmt(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def to_path_data(subpath):
    out = []
    for cmd, vals in subpath:
        if cmd == "Z":
            out.append("Z")
            continue
        pts = []
        for i in range(0, len(vals), 2):
            pts.append(fmt(vals[i]) + "," + fmt(GLYPH_SIZE - vals[i + 1]))  # y-flip
        out.append(cmd + " ".join(pts))
    return "".join(out)


def bounds(subpaths):
    xs, ys = [], []
    for sp in subpaths:
        for cmd, vals in sp:
            xs += vals[0::2]
            ys += [GLYPH_SIZE - v for v in vals[1::2]]
    return min(xs), min(ys), max(xs), max(ys)


HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'


def glyph_vector(path_all, comment, color="#FFFFFFFF"):
    return (HEADER +
            "<!-- " + comment + " -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n'
            '    android:height="24dp"\n'
            '    android:viewportWidth="24"\n'
            '    android:viewportHeight="24">\n'
            "    <path\n"
            '        android:fillColor="' + color + '"\n'
            '        android:pathData="' + path_all + '" />\n'
            "</vector>\n")


def monochrome_vector(path_all, comment, scale):
    t = fmt((108.0 - GLYPH_SIZE * scale) / 2.0)
    return (HEADER +
            "<!-- " + comment + " -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n'
            "    <group\n"
            '        android:scaleX="' + fmt(scale) + '"\n'
            '        android:scaleY="' + fmt(scale) + '"\n'
            '        android:translateX="' + t + '"\n'
            '        android:translateY="' + t + '">\n'
            "        <path\n"
            '            android:fillColor="#FFFFFFFF"\n'
            '            android:pathData="' + path_all + '" />\n'
            "    </group>\n"
            "</vector>\n")


# Circle paths of the original splash (r=50 -> r=100 around 160,160); same command layout so they can morph.
CIRCLE_SMALL = ("M 160 110 C 146.744 110 134.018 115.271 124.645 124.645 C 115.271 134.018 110 146.744 110 160 "
                "C 110 173.256 115.271 185.982 124.645 195.355 C 134.018 204.729 146.744 210 160 210 "
                "C 173.256 210 185.982 204.729 195.355 195.355 C 204.729 185.982 210 173.256 210 160 "
                "C 210 146.744 204.729 134.018 195.355 124.645 C 185.982 115.271 173.256 110 160 110 Z")
CIRCLE_LARGE = ("M 160 60 C 133.489 60 108.036 70.543 89.289 89.289 C 70.543 108.036 60 133.489 60 160 "
                "C 60 186.511 70.543 211.964 89.289 230.711 C 108.036 249.457 133.489 260 160 260 "
                "C 186.511 260 211.964 249.457 230.711 230.711 C 249.457 211.964 260 186.511 260 160 "
                "C 260 133.489 249.457 108.036 230.711 89.289 C 211.964 70.543 186.511 60 160 60 Z")


def gradient(start, end, stops):
    items = "".join('                                    <item android:offset="%s" android:color="%s" />\n'
                    % (fmt(o), c) for o, c in stops)
    return ('                            <aapt:attr name="android:fillColor">\n'
            '                                <gradient\n'
            '                                    android:type="linear"\n'
            '                                    android:startX="%s"\n'
            '                                    android:startY="%s"\n'
            '                                    android:endX="%s"\n'
            '                                    android:endY="%s">\n'
            % (fmt(start[0]), fmt(start[1]), fmt(end[0]), fmt(end[1])) +
            items +
            "                                </gradient>\n"
            "                            </aapt:attr>\n")


def alpha_anim(target):
    return ('    <target android:name="' + target + '">\n'
            '        <aapt:attr name="android:animation">\n'
            "            <objectAnimator\n"
            '                android:propertyName="fillAlpha"\n'
            '                android:startOffset="200"\n'
            '                android:duration="150"\n'
            '                android:valueFrom="0"\n'
            '                android:valueTo="1"\n'
            '                android:valueType="floatType"\n'
            '                android:interpolator="@android:interpolator/fast_out_slow_in" />\n'
            "        </aapt:attr>\n"
            "    </target>\n")


def splash(path_top, path_bottom, scale):
    t = fmt(160.0 - 12.0 * scale)
    return (HEADER +
            "<!-- ZENLYGRAM: Android 12+ splash icon (was Telegram's blue disc + plane). Generated by\n"
            "     Tools/zenlygram/icons/zg_vectors.py: a black disc grows like the original and the Zenlygram Z scales in.\n"
            "     Gradients are fine here: windowSplashScreenAnimatedIcon is only read on API 31+. -->\n"
            "<animated-vector\n"
            '    xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt">\n'
            '    <aapt:attr name="android:drawable">\n'
            "        <vector\n"
            '            android:name="splash"\n'
            '            android:width="320dp"\n'
            '            android:height="320dp"\n'
            '            android:viewportWidth="320"\n'
            '            android:viewportHeight="320">\n'
            "            <group\n"
            '                android:name="bg">\n'
            "                <path\n"
            '                    android:name="circle"\n'
            '                    android:pathData="' + CIRCLE_SMALL + '"\n'
            '                    android:fillColor="#FF000000"\n'
            '                    android:fillAlpha="0" />\n'
            "            </group>\n"
            "            <group\n"
            '                android:name="fg"\n'
            '                android:pivotX="160"\n'
            '                android:pivotY="160"\n'
            '                android:scaleX="0.5"\n'
            '                android:scaleY="0.5">\n'
            "                <group\n"
            '                    android:name="z"\n'
            '                    android:scaleX="' + fmt(scale) + '"\n'
            '                    android:scaleY="' + fmt(scale) + '"\n'
            '                    android:translateX="' + t + '"\n'
            '                    android:translateY="' + t + '">\n'
            "                    <path\n"
            '                        android:name="z_top"\n'
            '                        android:fillAlpha="0"\n'
            '                        android:pathData="' + path_top + '">\n' +
            gradient((4, 20), (20, 4), [(0, CYAN_BRIGHT), (0.5, AZURE), (1, CYAN_LIGHT)]) +
            "                    </path>\n"
            "                    <path\n"
            '                        android:name="z_bottom"\n'
            '                        android:fillAlpha="0"\n'
            '                        android:pathData="' + path_bottom + '">\n' +
            gradient((8, 18.5), (20.8, 18.5), [(0, ROYAL_LEFT), (1, ROYAL_RIGHT)]) +
            "                    </path>\n"
            "                </group>\n"
            "            </group>\n"
            "        </vector>\n"
            "    </aapt:attr>\n"
            '    <target android:name="circle">\n'
            '        <aapt:attr name="android:animation">\n'
            "            <set>\n"
            "                <objectAnimator\n"
            '                    android:propertyName="pathData"\n'
            '                    android:startOffset="200"\n'
            '                    android:duration="300"\n'
            '                    android:valueFrom="' + CIRCLE_SMALL + '"\n'
            '                    android:valueTo="' + CIRCLE_LARGE + '"\n'
            '                    android:valueType="pathType"\n'
            '                    android:interpolator="@android:anim/overshoot_interpolator" />\n'
            "                <objectAnimator\n"
            '                    android:propertyName="fillAlpha"\n'
            '                    android:startOffset="200"\n'
            '                    android:duration="100"\n'
            '                    android:valueFrom="0"\n'
            '                    android:valueTo="1"\n'
            '                    android:valueType="floatType"\n'
            '                    android:interpolator="@android:interpolator/fast_out_slow_in" />\n'
            "            </set>\n"
            "        </aapt:attr>\n"
            "    </target>\n" +
            alpha_anim("z_top") +
            alpha_anim("z_bottom") +
            '    <target android:name="fg">\n'
            '        <aapt:attr name="android:animation">\n'
            "            <set>\n"
            "                <objectAnimator\n"
            '                    android:propertyName="scaleX"\n'
            '                    android:startOffset="200"\n'
            '                    android:duration="300"\n'
            '                    android:valueFrom="0.5"\n'
            '                    android:valueTo="1"\n'
            '                    android:valueType="floatType"\n'
            '                    android:interpolator="@android:anim/overshoot_interpolator" />\n'
            "                <objectAnimator\n"
            '                    android:propertyName="scaleY"\n'
            '                    android:startOffset="200"\n'
            '                    android:duration="300"\n'
            '                    android:valueFrom="0.5"\n'
            '                    android:valueTo="1"\n'
            '                    android:valueType="floatType"\n'
            '                    android:interpolator="@android:anim/overshoot_interpolator" />\n'
            "            </set>\n"
            "        </aapt:attr>\n"
            "    </target>\n"
            "</animated-vector>\n")


def glyph_points(subpath, scale, cx, cy):
    """Contour of one subpath in y-down space, mapped with (x - 12, y - 12) * scale + (cx, cy).
    Returns [(vertex, in_tangent, out_tangent)] with tangents relative to the vertex (Lottie style)."""
    def map_pt(x, y):
        return ((x - 12.0) * scale + cx, ((GLYPH_SIZE - y) - 12.0) * scale + cy)
    pts = []
    for cmd, vals in subpath:
        if cmd in ("M", "L"):
            pts.append([map_pt(vals[0], vals[1]), (0.0, 0.0), (0.0, 0.0)])
        elif cmd == "C":
            c1, c2, end = map_pt(vals[0], vals[1]), map_pt(vals[2], vals[3]), map_pt(vals[4], vals[5])
            prev = pts[-1]
            prev[2] = (c1[0] - prev[0][0], c1[1] - prev[0][1])
            pts.append([end, (c2[0] - end[0], c2[1] - end[1]), (0.0, 0.0)])
    if len(pts) > 1 and abs(pts[0][0][0] - pts[-1][0][0]) < 1e-6 and abs(pts[0][0][1] - pts[-1][0][1]) < 1e-6:
        pts[0][1] = pts[-1][1]  # closing point repeats the first one
        pts.pop()
    return pts


def signed_area(pts):
    a = 0.0
    for i in range(len(pts)):
        (x1, y1), (x2, y2) = pts[i][0], pts[(i + 1) % len(pts)][0]
        a += x1 * y2 - x2 * y1
    return a / 2.0


def svg_path(pts):
    out = "M%s,%s" % (fmt(pts[0][0][0]), fmt(pts[0][0][1]))
    for i in range(1, len(pts) + 1):
        a, b = pts[i - 1], pts[i % len(pts)]
        if a[2] == (0.0, 0.0) and b[1] == (0.0, 0.0):
            if i < len(pts):
                out += "L%s,%s" % (fmt(b[0][0]), fmt(b[0][1]))
        else:
            out += "C%s,%s %s,%s %s,%s" % (fmt(a[0][0] + a[2][0]), fmt(a[0][1] + a[2][1]),
                                         fmt(b[0][0] + b[1][0]), fmt(b[0][1] + b[1][1]), fmt(b[0][0]), fmt(b[0][1]))
    return out + "Z"


def qr_logo(subpaths):
    # 150x150 like Telegram's logo; the Z is about 71 px wide (Telegram's plane was about 81 x 67)
    paths = "".join('<path d="%s" fill="#FFFFFF" />' % svg_path(glyph_points(sp, 4.0, 75.0, 75.0)) for sp in subpaths)
    return ('<?xml version="1.0" encoding="UTF-8"?><!-- ZENLYGRAM: QR code centre logo, black disc + Zenlygram Z (was '
            "Telegram's plane). Generated by Tools/zenlygram/icons/zg_vectors.py -->"
            '<svg width="150px" height="150px" viewBox="0 0 150 150" version="1.1" xmlns="http://www.w3.org/2000/svg">'
            '<circle fill="#000000" cx="75" cy="75" r="75" />' + paths + "</svg>\n")


def lottie_shape(pts, factor=1.0):
    r = lambda v: round(v * factor, 3)
    return [{"i": [[r(p[1][0]), r(p[1][1])] for p in pts],
             "o": [[r(p[2][0]), r(p[2][1])] for p in pts],
             "v": [[r(p[0][0]), r(p[0][1])] for p in pts],
             "c": True}]


def patch_calls_log(path, subpaths):
    """The badge of calls_log.json is one group: a circle (r=24, "Path 1") and the plane ("Path 2", grows from a dot
    between frames 15 and 36) filled together with opposite winding, so the plane is a hole. Every path of that group
    except the circle is replaced by the Z contours, wound against the circle and animated on the same keyframes."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    group = None
    for layer in data["layers"]:
        for shape in layer.get("shapes", []):
            items = shape.get("it", [])
            circles = [it for it in items if it.get("ty") == "sh" and not it["ks"].get("a")
                       and len(it["ks"]["k"]["v"]) == 4 and [24, 0] in it["ks"]["k"]["v"]]
            if circles and any(it.get("ty") == "sh" and it["ks"].get("a") for it in items):
                group, circle = shape, circles[0]
    if group is None:
        raise SystemExit("calls_log.json: badge group (circle + animated cut-out) not found")
    cutouts = [it for it in group["it"] if it.get("ty") == "sh" and it is not circle]
    first_kf, last_kf = cutouts[0]["ks"]["k"][0], cutouts[0]["ks"]["k"][-1]
    circle_area = signed_area([[tuple(v)] for v in circle["ks"]["k"]["v"]])
    new_items = []
    for n, sp in enumerate(subpaths):
        pts = glyph_points(sp, 1.35, 0.0, 0.0)  # about 24 units on the 48-unit badge (the plane was about 28 x 23)
        if (signed_area(pts) > 0) == (circle_area > 0):
            pts = [[p[0], p[2], p[1]] for p in reversed(pts)]  # opposite winding: a hole with the nonzero fill
        new_items.append({
            "ind": len(new_items) + 1, "ty": "sh", "ix": len(new_items) + 2,
            "ks": {"a": 1, "k": [
                {"i": first_kf["i"], "o": first_kf["o"], "t": first_kf["t"], "s": lottie_shape(pts, 0.04)},
                {"t": last_kf["t"], "s": lottie_shape(pts)}], "ix": 2},
            "nm": "ZENLYGRAM Z %d" % (n + 1), "mn": "ADBE Vector Shape - Group", "hd": False})
    pos = group["it"].index(cutouts[0])
    rest = [it for it in group["it"] if it not in cutouts]
    group["it"] = rest[:pos] + new_items + rest[pos:]
    data["nm"] = "permissions calls (ZENLYGRAM: Z badge, generated by Tools/zenlygram/icons/zg_vectors.py)"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"), ensure_ascii=False)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    pdf, res = sys.argv[1], sys.argv[2].rstrip("/")
    subpaths = read_subpaths(pdf)
    if len(subpaths) != 2:
        raise SystemExit("expected 2 subpaths (top bar + diagonal, bottom bar), got %d" % len(subpaths))
    path_top, path_bottom = to_path_data(subpaths[0]), to_path_data(subpaths[1])
    path_all = path_top + path_bottom
    x0, y0, x1, y1 = bounds(subpaths)
    print("glyph bounds (y down): x %.3f..%.3f  y %.3f..%.3f  size %.3f x %.3f" % (x0, x1, y0, y1, x1 - x0, y1 - y0))

    files = {
        "zg_glyph.xml": glyph_vector(path_all, "ZENLYGRAM: Zenlygram Z glyph, 24dp, white (from the iOS swiftgram_context_menu.pdf; "
                                     "generated by Tools/zenlygram/icons/zg_vectors.py)"),
        "notification.xml": glyph_vector(path_all, "ZENLYGRAM: status-bar small icon (was Telegram's plane, notification.webp). "
                                         "Generated by Tools/zenlygram/icons/zg_vectors.py"),
        # scale 2.25: the Z is about 40dp wide on the 108dp canvas, well inside the 66dp safe zone
        "zg_launcher_monochrome.xml": monochrome_vector(path_all, "ZENLYGRAM: themed (monochrome) layer of the adaptive launcher icon. "
                                                        "Generated by Tools/zenlygram/icons/zg_vectors.py", 2.25),
        "icon_plane.xml": monochrome_vector(path_all, "ZENLYGRAM: was Telegram's monochrome plane. Kept under the old name because "
                                            "TMessagesProj_AppStandalone/src/main/res/mipmap-anydpi-v26/ic_launcher_sa.xml still "
                                            "references it; the app's own icon uses zg_launcher_monochrome. "
                                            "Generated by Tools/zenlygram/icons/zg_vectors.py", 2.25),
        "tg_splash_320.xml": splash(path_top, path_bottom, 6.25),
        "zg_glyph_pro.xml": glyph_vector(path_all, "ZENLYGRAM: light-blue Zenlygram Z of the \"Zenlygram Pro\" row in Settings "
                                         "(navy tile). Generated by Tools/zenlygram/icons/zg_vectors.py", PRO_BLUE),
    }
    for name, text in files.items():
        with open(res + "/drawable/" + name, "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote drawable/" + name)
    with open(res + "/raw/qr_logo.svg", "w", encoding="utf-8") as f:
        f.write(qr_logo(subpaths))
    print("wrote raw/qr_logo.svg")
    patch_calls_log(res + "/raw/calls_log.json", subpaths)
    print("patched raw/calls_log.json")


if __name__ == "__main__":
    main()
