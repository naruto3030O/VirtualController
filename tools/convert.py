"""
Converts the Figma button SVGs into Android VectorDrawables, generating both
the default and active state for each.

Handles the two things Vector Asset Studio gets wrong on these files:
  * radial gradients declared via gradientTransform (Android has no transform
    attr, only centerX/centerY/gradientRadius). Safe here because every
    transform is a uniform scale + rotation, and rotating a radial gradient
    about its own centre is a no-op.
  * <circle> elements, which VectorDrawable has no equivalent for; they are
    emitted as two-arc paths instead.
"""

import re
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"

ACTIVE_ICON = "#D9D9D9"     # icon colour when the button is active
IDLE_ICON = "#6D6D6D"
LED_CYAN = "#10E3FF"
LED_OFF = "#1F1F1F"


def rect_path(x, y, w, h, rx):
    """VectorDrawable has no <rect>; emit a rounded-rect path."""
    if rx <= 0:
        return f"M{x},{y} H{x + w} V{y + h} H{x} Z"

    return (
        f"M{x + rx},{y} H{x + w - rx} "
        f"A{rx},{rx} 0 0 1 {x + w},{y + rx} "
        f"V{y + h - rx} "
        f"A{rx},{rx} 0 0 1 {x + w - rx},{y + h} "
        f"H{x + rx} "
        f"A{rx},{rx} 0 0 1 {x},{y + h - rx} "
        f"V{y + rx} "
        f"A{rx},{rx} 0 0 1 {x + rx},{y} Z"
    )


def circle_path(cx, cy, r):
    """VectorDrawable has no <circle>; two 180-degree arcs make one."""
    return (f"M{cx},{cy - r} A{r},{r} 0 1,0 {cx},{cy + r} "
            f"A{r},{r} 0 1,0 {cx},{cy - r} Z")


def parse_gradients(root):
    """id -> gradient descriptor tuple, tagged 'radial' or 'linear'."""
    out = {}

    for grad in root.iter(f"{NS}linearGradient"):
        stops = []
        for i, stop in enumerate(grad.iter(f"{NS}stop")):
            offset = stop.get("offset")
            offset = float(offset) if offset is not None else float(i)
            stops.append((offset, stop.get("stop-color", "#000000")))

        out[grad.get("id")] = (
            "linear",
            float(grad.get("x1", 0)), float(grad.get("y1", 0)),
            float(grad.get("x2", 0)), float(grad.get("y2", 0)),
            stops,
        )

    for grad in root.iter(f"{NS}radialGradient"):
        transform = grad.get("gradientTransform", "")

        tx = ty = 0.0
        scale = 1.0

        m = re.search(r"translate\(([-\d.eE]+)[ ,]+([-\d.eE]+)\)", transform)
        if m:
            tx, ty = float(m.group(1)), float(m.group(2))

        m = re.search(r"scale\(([-\d.eE]+)", transform)
        if m:
            scale = float(m.group(1))

        stops = []
        for i, stop in enumerate(grad.iter(f"{NS}stop")):
            offset = stop.get("offset")
            offset = float(offset) if offset is not None else float(i)
            stops.append((offset, stop.get("stop-color", "#000000")))

        out[grad.get("id")] = ("radial", tx, ty, scale, stops)

    return out


def gradient_xml(attr, grad, indent="        "):
    if grad[0] == "linear":
        _, x1, y1, x2, y2, stops = grad

        lines = [f'{indent}<aapt:attr name="android:{attr}">',
                 f'{indent}    <gradient',
                 f'{indent}        android:type="linear"',
                 f'{indent}        android:startX="{x1}"',
                 f'{indent}        android:startY="{y1}"',
                 f'{indent}        android:endX="{x2}"',
                 f'{indent}        android:endY="{y2}">']
    else:
        _, cx, cy, r, stops = grad

        lines = [f'{indent}<aapt:attr name="android:{attr}">',
                 f'{indent}    <gradient',
                 f'{indent}        android:type="radial"',
                 f'{indent}        android:centerX="{cx}"',
                 f'{indent}        android:centerY="{cy}"',
                 f'{indent}        android:gradientRadius="{r}">']

    for offset, colour in stops:
        lines.append(
            f'{indent}        <item android:offset="{offset}" '
            f'android:color="{colour}" />'
        )

    lines.append(f"{indent}    </gradient>")
    lines.append(f"{indent}</aapt:attr>")

    return "\n".join(lines)


def resolve(value, grads):
    """Returns ('grad', data) or ('plain', '#RRGGBB') or None."""
    if not value or value == "none":
        return None

    m = re.match(r"url\(#(.+)\)", value)

    if m:
        g = grads.get(m.group(1))
        return ("grad", g) if g else None

    return ("plain", value)


def emit(path_data, fill, stroke, grads, extra=""):
    body = [f'    <path\n        android:pathData="{path_data}"']

    grad_blocks = []

    f = resolve(fill, grads)
    if f and f[0] == "plain":
        body.append(f'        android:fillColor="{f[1]}"')
    elif f:
        grad_blocks.append(gradient_xml("fillColor", f[1]))

    s = resolve(stroke, grads)
    if s and s[0] == "plain":
        body.append(f'        android:strokeColor="{s[1]}"')
    elif s:
        grad_blocks.append(gradient_xml("strokeColor", s[1]))

    if extra:
        body.append(extra)

    head = "\n".join(body)

    if grad_blocks:
        return head + ">\n" + "\n".join(grad_blocks) + "\n    </path>"

    return head + " />"


def convert(svg_path, active=False):
    tree = ET.parse(svg_path)
    root = tree.getroot()

    width = root.get("width")
    height = root.get("height")
    vb = root.get("viewBox").split()

    grads = parse_gradients(root)
    parts = []

    # Map child -> parent so <g opacity> can be folded into its children.
    parents = {c: p for p in root.iter() for c in p}

    def alpha_of(el):
        a = 1.0
        node = el
        while node is not None:
            o = node.get("opacity")
            if o:
                a *= float(o)
            node = parents.get(node)
        return a

    for el in root.iter():
        tag = el.tag.replace(NS, "")

        if tag == "rect":
            parts.append(emit(
                rect_path(
                    float(el.get("x", 0)), float(el.get("y", 0)),
                    float(el.get("width")), float(el.get("height")),
                    float(el.get("rx", 0)),
                ),
                el.get("fill"), el.get("stroke"), grads,
                f'        android:strokeWidth="{el.get("stroke-width", 1)}"'
                if el.get("stroke") else "",
            ))
            continue

        if tag == "circle":
            cx = float(el.get("cx"))
            cy = float(el.get("cy"))
            r = float(el.get("r"))

            fill = el.get("fill")

            # The small dark circle is the LED.
            if fill == LED_OFF:
                if active:
                    halo_r = r * 4.5
                    parts.append(
                        f'    <path android:pathData="{circle_path(cx, cy, halo_r)}">\n'
                        f'{gradient_xml("fillColor", ("radial", cx, cy, halo_r, [(0, "#8010E3FF"), (0.35, "#4010E3FF"), (1, "#0010E3FF")]))}\n'
                        f"    </path>"
                    )
                    parts.append(emit(circle_path(cx, cy, r * 1.2),
                                      LED_CYAN, None, grads))
                else:
                    parts.append(emit(circle_path(cx, cy, r), LED_OFF, None, grads))
                continue

            parts.append(emit(circle_path(cx, cy, r), fill, el.get("stroke"),
                              grads,
                              '        android:strokeWidth="1"'
                              if el.get("stroke") else ""))

        elif tag == "path":
            d = el.get("d")
            if not d:
                continue

            fill = el.get("fill")
            stroke = el.get("stroke")

            if active:
                if fill == IDLE_ICON:
                    fill = ACTIVE_ICON
                if stroke == IDLE_ICON:
                    stroke = ACTIVE_ICON

            extra = []

            a = alpha_of(el)
            if a < 0.999:
                extra.append(f'        android:fillAlpha="{round(a, 3)}"')

            if el.get("stroke-width"):
                extra.append(f'        android:strokeWidth="{el.get("stroke-width")}"')
            elif stroke:
                extra.append('        android:strokeWidth="1"')

            if el.get("stroke-linecap"):
                extra.append('        android:strokeLineCap="round"')
            if el.get("stroke-linejoin"):
                extra.append('        android:strokeLineJoin="round"')

            parts.append(emit(d, fill, stroke, grads, "\n".join(extra)))

    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{width}dp"\n'
        f'    android:height="{height}dp"\n'
        f'    android:viewportWidth="{vb[2]}"\n'
        f'    android:viewportHeight="{vb[3]}">\n\n'
        + "\n\n".join(parts)
        + "\n</vector>\n"
    )


if __name__ == "__main__":
    import sys
    import os

    jobs = [
        ("HORN.svg", "ic_horn"),
        ("headlight.svg", "ic_headlight"),
        ("Handbrake.svg", "ic_handbrake"),
        ("settings-svgrepo-com_1.svg", "ic_settings"),
        ("Xbox_OFF.svg", "ic_xbox_off"),
        ("Xbox_ON.svg", "ic_xbox_on"),
        ("Ellipse_4.svg", "ic_toggle_ring"),
    ]

    src = "/mnt/user-data/uploads"
    dst = sys.argv[1]

    os.makedirs(dst, exist_ok=True)

    for filename, out in jobs:
        path = os.path.join(src, filename)

        open(os.path.join(dst, out + ".xml"), "w").write(convert(path))

        # Two-state buttons also get an active variant.
        if out in ("ic_horn", "ic_headlight", "ic_handbrake"):
            open(os.path.join(dst, out + "_on.xml"), "w").write(
                convert(path, active=True)
            )

    print("converted", len(os.listdir(dst)), "drawables")
