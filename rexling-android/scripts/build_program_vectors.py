#!/usr/bin/env python3
"""Compile the retained catalog SVG subset to native Canvas path commands.

No rasterization: SVG masks are monochrome alpha logos and can be drawn as tinted
paths. Unsupported SVG features fail generation instead of silently losing art.
"""
import base64
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork/program-icons/superlogical"
OUTPUT = ROOT / "app/src/main/assets/program_tiles.json"


def tag(node):
    return node.tag.rsplit("}", 1)[-1]


def number(value):
    return float(str(value).removesuffix("px"))


def shapes(node):
    kind = tag(node)
    if kind == "path":
        return node.attrib["d"]
    if kind == "rect":
        x, y, w, h = [number(node.get(k, 0)) for k in ("x", "y", "width", "height")]
        rx = number(node.get("rx", node.get("ry", 0)))
        ry = number(node.get("ry", rx))
        if rx > 0 or ry > 0:
            rx = min(rx, w / 2)
            ry = min(ry, h / 2)
            return f"M{x+rx},{y}h{w-2*rx}a{rx},{ry} 0 0 1 {rx},{ry}v{h-2*ry}a{rx},{ry} 0 0 1 {-rx},{ry}h{-(w-2*rx)}a{rx},{ry} 0 0 1 {-rx},{-ry}v{-(h-2*ry)}a{rx},{ry} 0 0 1 {rx},{-ry}Z"
        return f"M{x},{y}h{w}v{h}h{-w}Z"
    if kind in ("polygon", "polyline"):
        points = re.findall(r"[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", node.attrib["points"])
        assert len(points) % 2 == 0
        return "M" + "L".join(",".join(points[i:i+2]) for i in range(0, len(points), 2)) + ("Z" if kind == "polygon" else "")
    if kind == "circle":
        cx, cy, r = [number(node.get(k, 0)) for k in ("cx", "cy", "r")]
        return f"M{cx-r},{cy}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0Z"
    if kind == "ellipse":
        cx, cy, rx, ry = [number(node.get(k, 0)) for k in ("cx", "cy", "rx", "ry")]
        return f"M{cx-rx},{cy}a{rx},{ry} 0 1,0 {2*rx},0a{rx},{ry} 0 1,0 {-2*rx},0Z"
    if kind == "line":
        x1, y1, x2, y2 = [number(node.get(k, 0)) for k in ("x1", "y1", "x2", "y2")]
        return f"M{x1},{y1}L{x2},{y2}"
    raise ValueError(f"Unsupported shape: {kind}")


def reference(value):
    assert value.startswith("url(#") and value.endswith(")"), value
    return value[5:-1]


def compile_icon(root):
    commands = [{"op": "translate", "x": -6, "y": -6}]

    def draw(node, inherited, definitions, ink=None, opacity=1):
        kind = tag(node)
        assert node.get("filter") is None, "SVG filters are not supported"
        if kind in ("defs", "title", "desc"):
            return
        style = inherited.copy()
        if "style" in node.attrib:
            for item in node.attrib["style"].split(";"):
                if ":" in item:
                    k, v = item.split(":", 1)
                    k, v = k.strip(), v.strip()
                    if k in ("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin"):
                        style[k] = v
        style.update({k: node.attrib[k] for k in ("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin") if k in node.attrib})
        opacity *= float(node.get("opacity", 1))
        commands.append({"op": "save"})
        if node.get("transform"):
            commands.append({"op": "transform", "value": node.get("transform")})
        if node.get("clip-path"):
            clip = definitions[reference(node.get("clip-path"))]
            assert len(clip) == 1
            commands.append({"op": "clip", "path": shapes(clip[0])})
        if node.get("mask"):
            mask = definitions[reference(node.get("mask"))]
            image = next(x for x in mask if tag(x) == "image")
            href = image.get("href", "")
            assert href.startswith("data:image/svg+xml;base64,")
            logo = ET.fromstring(base64.b64decode(href.split(",", 1)[1]))
            color = node[0].attrib["fill"] if len(node) > 0 and "fill" in node[0].attrib else ink or "#ffffff"
            x, y, w, h = [number(image.get(k)) for k in ("x", "y", "width", "height")]
            vx, vy, vw, vh = map(float, logo.attrib["viewBox"].split())
            scale = min(w/vw, h/vh)
            commands.extend([{"op": "translate", "x": x+(w-vw*scale)/2, "y": y+(h-vh*scale)/2},
                             {"op": "scale", "x": scale, "y": scale},
                             {"op": "translate", "x": -vx, "y": -vy}])
            draw(logo, {}, {x.get("id"): x for x in logo.iter() if x.get("id")}, color, opacity)
        elif kind == "image":
            href = node.get("href", node.get("{http://www.w3.org/1999/xlink}href", ""))
            assert href.startswith("data:image/svg+xml;base64,"), "Only embedded vector images are supported"
            logo = ET.fromstring(base64.b64decode(href.split(",", 1)[1]))
            x, y, w, h = [number(node.get(k, 0)) for k in ("x", "y", "width", "height")]
            vx, vy, vw, vh = map(float, logo.attrib["viewBox"].split())
            scale = min(w/vw, h/vh)
            commands.extend([{"op": "translate", "x": x+(w-vw*scale)/2, "y": y+(h-vh*scale)/2},
                             {"op": "scale", "x": scale, "y": scale},
                             {"op": "translate", "x": -vx, "y": -vy}])
            draw(logo, {}, {x.get("id"): x for x in logo.iter() if x.get("id")}, ink, opacity)
        elif kind in ("defs", "title", "desc", "mask"):
            return
        elif kind == "use":
            href = node.attrib.get("{http://www.w3.org/1999/xlink}href") or node.attrib.get("href") or node.attrib.get("xlink:href", "")
            if href.startswith("#") and href[1:] in definitions:
                draw(definitions[href[1:]], style, definitions, ink, opacity)
        elif kind in ("svg", "g"):
            for child in node:
                draw(child, style, definitions, ink, opacity)
        elif kind in ("path", "rect", "polygon", "polyline", "circle", "ellipse", "line"):
            fill = style.get("fill", "black")
            stroke = style.get("stroke")
            if fill != "none":
                paint = {"color": ink or fill, "alpha": opacity*float(style.get("fill-opacity", 1))}
                if fill.startswith("url("):
                    gradient = definitions[reference(fill)]
                    assert tag(gradient) in ("linearGradient", "radialGradient")
                    paint = {"gradient": tag(gradient), "attributes": gradient.attrib,
                             "stops": [dict(s.attrib, **{"stop-color": ink or s.get("stop-color", "black")}) for s in gradient],
                             "alpha": paint["alpha"]}
                commands.append({"op": "path", "path": shapes(node), "evenOdd": style.get("fill-rule") == "evenodd", "paint": paint})
            if stroke and stroke not in ("none", "transparent"):
                paint = {"color": ink or stroke, "alpha": opacity*float(style.get("stroke-opacity", 1)),
                         "stroke": True, "strokeWidth": float(style.get("stroke-width", 1)),
                         "strokeCap": style.get("stroke-linecap", "butt"), "strokeJoin": style.get("stroke-linejoin", "miter")}
                commands.append({"op": "path", "path": shapes(node), "evenOdd": False, "paint": paint})
        else:
            raise ValueError(f"Unsupported SVG element: {kind}")
        commands.append({"op": "restore"})

    draw(root, {}, {x.get("id"): x for x in root.iter() if x.get("id")})
    return commands


def main():
    icons = {p.stem.replace("-", "_"): compile_icon(ET.parse(p).getroot()) for p in sorted(SOURCE.glob("*.svg"))}
    OUTPUT.write_text(json.dumps({"width": 61, "height": 48, "icons": icons}, separators=(",", ":")) + "\n")
    print(f"Compiled {len(icons)} vector tiles to {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
