#!/usr/bin/env python3
"""Renders the Photoliz app icon (same geometry as the Android adaptive icon vector).

Usage: render_icon.py <out.png> <size> [--padless]
The 108-unit adaptive canvas is cropped to its central 72 units for iOS (full-bleed, no alpha).
"""
import sys
from PIL import Image, ImageDraw

BG = (17, 18, 20)
COL = {
    'plate': (138, 141, 145), 'button': (208, 210, 213), 'body': (179, 181, 184),
    'band': (138, 141, 145), 'ring': (38, 40, 43), 'glass': (17, 18, 20),
    'reflect': (28, 97, 178), 'lamp': (211, 47, 47),
}

def render(size, crop=(18, 18, 90, 90)):
    ss = 4
    S = size * ss
    x0, y0, x1, y1 = crop
    k = S / (x1 - x0)
    def P(x, y):
        return ((x - x0) * k, (y - y0) * k)
    im = Image.new('RGB', (S, S), BG)
    d = ImageDraw.Draw(im)
    def rrect(x, y, w, h, r, c):
        (a, b), (c2, d2) = P(x, y), P(x + w, y + h)
        d.rounded_rectangle([a, b, c2, d2], radius=r * k, fill=c)
    def circle(cx, cy, r, c):
        (a, b), (c2, d2) = P(cx - r, cy - r), P(cx + r, cy + r)
        d.ellipse([a, b, c2, d2], fill=c)
    rrect(35, 33, 18, 7, 2, COL['plate'])
    rrect(63, 34.5, 11, 5, 1, COL['button'])
    rrect(27, 38, 54, 34, 4, COL['body'])
    rrect(27, 47, 54, 5, 0, COL['band'])
    circle(54, 56, 14, COL['ring'])
    circle(54, 56, 10, COL['glass'])
    circle(50, 52, 3, COL['reflect'])
    circle(74.5, 44, 2.5, COL['lamp'])
    return im.resize((size, size), Image.LANCZOS)

if __name__ == '__main__':
    out, size = sys.argv[1], int(sys.argv[2])
    render(size).save(out)
