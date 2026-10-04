"""
Ship model + texture generator for Ports & Routes.

Ships are described here as parts made of boxes, in units of 1/32 block (the renderer scales by 0.5).
Coordinates: +Z is the bow, Y points DOWN (entity model convention), the deck surface is at y = 2.
For every box the script packs its UV footprint into one texture and paints each face with a painter
chosen by the box's material, using absolute model coordinates so patterns continue across boxes.

Output (run from the project root):  python tools/ships/gen_ships.py
  src/main/resources/assets/minecraftportsmod/ship_models/<ship>.json
  src/main/resources/assets/minecraftportsmod/textures/entity/ship/<ship>.png
"""
import json
import math
import os
import random
import zlib

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MODELS = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraftportsmod", "ship_models")
TEXTURES = os.path.join(ROOT, "src", "main", "resources", "assets", "minecraftportsmod", "textures", "entity", "ship")


# ============================================================================ model description

class Box:
    def __init__(self, mat, x, y, z, w, h, d, deco=None):
        self.mat, self.deco = mat, deco or {}
        self.x, self.y, self.z = int(round(x)), int(round(y)), int(round(z))
        self.w, self.h, self.d = max(1, int(round(w))), max(1, int(round(h))), max(1, int(round(d)))
        self.u = self.v = 0
        # how far the box is grown all round (model units) so its faces don't share a plane with another box's
        self.grow = 0.0


class Part:
    def __init__(self, name, pivot=(0, 0, 0), rot=(0, 0, 0), anim=None):
        self.name, self.pivot, self.rot, self.anim = name, pivot, rot, anim
        self.boxes, self.children = [], []

    def box(self, mat, x, y, z, w, h, d, **deco):
        b = Box(mat, x, y, z, w, h, d, deco)
        self.boxes.append(b)
        return b

    def child(self, name, pivot=(0, 0, 0), rot=(0, 0, 0), anim=None):
        c = Part(name, pivot, rot, anim)
        self.children.append(c)
        return c

    def all_boxes(self):
        for b in self.boxes:
            yield b, self
        for c in self.children:
            yield from c.all_boxes()


def rope(parent, name, a, b, mat="rope", thick=2):
    """A straight rope/spar from point a to point b (absolute coords), as a rotated part."""
    dx, dy, dz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
    length = math.sqrt(dx * dx + dy * dy + dz * dz)
    ux, uy, uz = dx / length, dy / length, dz / length
    # the box runs along -Y from the pivot; rotate (0,-1,0) onto the direction (see ShipModel docs)
    xrot = math.asin(max(-1.0, min(1.0, -uz)))
    zrot = math.atan2(ux, -uy)
    p = parent.child(name, pivot=a, rot=(xrot, 0, zrot))
    p.box(mat, -thick / 2, -length, -thick / 2, thick, length, thick)
    return p


def bands(poly, step):
    """Horizontal slices of a polygon in the (z, y) plane: [(y0, h, zmin, zmax)]."""
    ys = [p[1] for p in poly]
    y_top, y_bot = min(ys), max(ys)
    out = []
    y = y_top
    while y < y_bot:
        h = min(step, y_bot - y)
        mid = y + h / 2
        xs = []
        for i in range(len(poly)):
            (z1, y1), (z2, y2) = poly[i], poly[(i + 1) % len(poly)]
            if (y1 <= mid < y2) or (y2 <= mid < y1):
                t = (mid - y1) / (y2 - y1)
                xs.append(z1 + t * (z2 - z1))
        if len(xs) >= 2:
            out.append((y, h, min(xs), max(xs)))
        y += h
    return out


def hull(root, half_beam, half_len, height, wall, bow_fracs, sheer, stripe_mat, ports=None, rows=None, wl=12):
    """Flat bottom, straight sides with a wale, a stepped bow that narrows and rises, a transom stern.
    ports: the gun ports' middles along the side (z), rows: their (top, bottom) y, wl: the waterline's y."""
    h = root.child("hull")
    side = dict(side=True, ports=ports or [], rows=rows or [], wl=wl)
    bottom = height - 4                 # sides run from y=-2 (gunwale) to 'bottom'; keel below that
    L = half_len * 2
    inner = half_beam - wall
    h.box("keel", -inner, bottom, -half_len, inner * 2, 4, L)
    h.box("hull", -half_beam, -2, -half_len, wall, bottom + 2, L, **side)
    h.box("hull", half_beam - wall, -2, -half_len, wall, bottom + 2, L, **side)
    h.box("deck", -inner, 2, -half_len, inner * 2, 2, L)
    # wale (a protruding painted band) and bulwark with rail posts and a cap rail
    h.box(stripe_mat, -half_beam - 1, 2, -half_len, 1, 4, L)
    h.box(stripe_mat, half_beam, 2, -half_len, 1, 4, L)
    h.box("dark", -half_beam, -8, -half_len, wall, 6, L)
    h.box("dark", half_beam - wall, -8, -half_len, wall, 6, L)
    for z in range(-half_len + 6, half_len, 12):
        h.box("dark", -half_beam, -14, z, 2, 6, 2)
        h.box("dark", half_beam - 2, -14, z, 2, 6, 2)
    h.box("cap", -half_beam - 1, -16, -half_len, wall + 2, 2, L)
    h.box("cap", half_beam - wall - 1, -16, -half_len, wall + 2, 2, L)
    # bow: narrowing steps, each a little higher (sheer) and shallower (raked stem)
    z = half_len
    n = len(bow_fracs)
    for i, f in enumerate(bow_fracs):
        hb = max(2, half_beam * f)
        rise = int(round(sheer * (i + 1) / n))
        top = -2 - rise
        bot = bottom - i * 2
        length = 5
        h.box("hull", -hb, top, z, hb * 2, bot - top, length, side=True, wl=wl)
        h.box("deck", -hb + wall, top + 4, z, max(1, hb * 2 - wall * 2), 2, length)
        h.box("cap", -hb - 1, top - 8, z, hb * 2 + 2, 2, length)
        h.box("dark", -hb, top - 6, z, hb * 2, 6, length)
        z += length
    h.box("dark", -2, -4 - sheer, z, 4, bottom + sheer - 4, 4)     # stem post
    # stern: transom a little higher than the sides
    h.box("hull", -half_beam, -10, -half_len - 6, half_beam * 2, bottom + 10, 6, side=True, wl=wl)
    h.box("cap", -half_beam - 1, -16, -half_len - 6, half_beam * 2 + 2, 2, 6)
    return h, z


def gun(h, side, hb, y, z):
    """A gun in its port at (y, z): a slim barrel out over the side, the port's lid swung up above it."""
    x_out = hb if side > 0 else -hb - 8
    h.box("iron", x_out, y + 1, z - 2, 8, 4, 4)
    h.box("iron", (hb + 7) if side > 0 else (-hb - 9), y + 0, z - 3, 2, 6, 6)       # the muzzle's swell
    h.box("dark", hb if side > 0 else -hb - 1, y - 8, z - 6, 1, 7, 12)               # the lid, up


# ---------------------------------------------------------------------------- sloop

def build_sloop():
    root = Part("root")
    hb, hl, height = 30, 68, 24
    _, bow_end = hull(root, hb, hl, height, 4, [0.9, 0.78, 0.64, 0.48, 0.32, 0.16], 8, "wale_blue")
    h = root.children[0]
    h.box("dark", -2, 0, -hl - 12, 4, height + 2, 6)                     # rudder
    h.box("dark", -1, -6, -hl - 4, 3, 3, 32)                              # tiller
    h.box("hatch", -10, 1, -30, 20, 1, 22)                                # deck grating
    h.box("barrel", 14, -12, -48, 10, 14, 10)
    h.box("barrel", 14, -12, -36, 10, 14, 10)
    h.box("crate", -24, -10, -52, 12, 12, 12)
    h.box("crate", -24, -18, -52, 10, 8, 10)
    h.box("rope_coil", -8, 0, 30, 14, 2, 14)
    h.box("dark", -24, -26, -hl - 3, 2, 28, 2)                            # lantern post
    h.box("lantern", -26, -34, -hl - 5, 6, 8, 6)

    mz = 10
    rig = root.child("rig")
    rig.box("mast", -3, -172, mz - 3, 6, 174, 6)
    rig.box("dark", -4, -176, mz - 4, 8, 4, 8)                            # masthead cap
    rig.box("dark", -24, -122, mz - 2, 48, 3, 4)                          # crosstrees
    rig.box("dark", -5, -40, mz - 5, 10, 4, 10)                           # boom gooseneck
    boom = root.child("boom", pivot=(0, -30, mz))
    boom.box("spar", -2, -2, -74, 4, 4, 74)
    gaff = root.child("gaff", pivot=(0, -140, mz), rot=(-0.4, 0, 0))
    gaff.box("spar", -2, -2, -60, 4, 4, 60)
    bowsprit = root.child("bowsprit", pivot=(0, -12, bow_end - 6), rot=(0.25, 0, 0))
    bowsprit.box("spar", -2, -2, 0, 4, 4, 50)
    tip = (0, -12 - 50 * math.sin(0.25), bow_end - 6 + 50 * math.cos(0.25))

    # mainsail: gaff-rigged quadrilateral, furls down onto the boom
    peak = (mz - 60 * math.cos(0.4), -140 - 60 * math.sin(0.4))
    poly = [(mz - 4, -34), (mz - 72, -34), (peak[0], peak[1] + 4), (mz - 4, -138)]
    sail = root.child("sail_main", pivot=(0, -32, 0), anim="fore_aft")
    fore_and_aft(sail, poly, 8, 32, 6)
    # jib: triangle from the bowsprit to the crosstrees
    jib_poly = [(tip[2] - 8, tip[1] - 6), (mz + 16, -118), (mz + 26, -16)]
    jib = root.child("sail_jib", pivot=(0, -16, 0), anim="fore_aft")
    fore_and_aft(jib, jib_poly, 8, 16, 4)

    # standing rigging
    top = (0, -166, mz)
    rope(root, "forestay", tip, top)
    rope(root, "backstay", (0, -16, -hl - 4), top)
    for side in (-1, 1):
        for i, dz in enumerate((-14, 0, 14)):
            rope(root, f"shroud_{side}_{i}", (side * (hb - 2), -16, mz + dz), (side * 22, -120, mz))
    flag = root.child("flag", pivot=(0, -178, mz), anim="flag")
    flag.box("flag", -1, 0, -26, 2, 16, 26)
    return root


# ---------------------------------------------------------------------------- brig

SAILS = []


def square_sail(root, name, x_top, x_bot, y_top, height, z):
    """A square sail hanging from its yard at (y_top, z), slightly wider at the foot, full of wind: its belly bulges
    forward, most in the middle and low down; furls up to the yard. Drawn by the game as smooth cloth (its corners
    and belly are exported, see SAILS)."""
    p = root.child(name, pivot=(0, y_top, z), anim="square")
    SAILS.append({"pts": [[-x_top, y_top, z], [x_top, y_top, z], [x_bot, y_top + height, z], [-x_bot, y_top + height, z]],
                  "belly": [0, 0, max(3.0, height * 0.24)], "furl": "top", "emblem": name.endswith("course")})
    return p


def fore_and_aft(part, poly, step, y_shift, belly=5.0):
    """A fore-and-aft sail (a gaff sail, a jib, a lateen) full of wind: bellied out to one side (to leeward), most in
    the middle; furls down. Drawn by the game as smooth cloth: its corners (the highest at the head) are exported."""
    px, py, pz = part.pivot
    pts = [[px, py + y + y_shift, pz + z] for (z, y) in poly]
    pts.sort(key=lambda q: q[1])
    if len(pts) == 3:
        top = [pts[0], pts[0]]
        bot = sorted(pts[1:], key=lambda q: q[2])
    else:
        top = sorted(pts[:2], key=lambda q: q[2])
        bot = sorted(pts[2:], key=lambda q: q[2])
    SAILS.append({"pts": [top[0], top[1], bot[1], bot[0]], "belly": [belly * 2.0, 0, 0], "furl": "bottom", "emblem": False})


def build_brig():
    root = Part("root")
    hb, hl, height = 40, 96, 26
    _, bow_end = hull(root, hb, hl, height, 5, [0.92, 0.82, 0.7, 0.56, 0.42, 0.28, 0.14], 10, "wale_black",
                      ports=[-50, -18, 14, 46], rows=[(-2, 9)])
    h = root.children[0]
    # quarterdeck / stern castle with a cabin
    h.box("hull", -hb, -26, -hl - 6, hb * 2, 24, 34, side=True, castle=True)
    h.box("deck", -hb + 5, -28, -hl - 6, hb * 2 - 10, 2, 34)
    h.box("cap", -hb - 1, -40, -hl - 6, 6, 2, 34)
    h.box("cap", hb - 5, -40, -hl - 6, 6, 2, 34)
    h.box("cap", -hb - 1, -40, -hl - 6, hb * 2 + 2, 2, 5)
    for z in range(-hl - 4, -hl + 28, 8):
        h.box("dark", -hb, -38, z, 2, 10, 2)
        h.box("dark", hb - 2, -38, z, 2, 10, 2)
    for x in range(-hb + 6, hb - 4, 10):
        h.box("dark", x, -38, -hl - 6, 2, 10, 2)
    h.box("dark", -8, -24, -hl + 27, 16, 22, 2)                          # cabin door frame
    h.box("door", -6, -22, -hl + 28, 12, 20, 1)
    for x in (-30, -14, 6, 22):                                           # stern windows
        h.box("glass", x, -22, -hl - 7, 8, 10, 1)
    for side in (-1, 1):
        h.box("glass", side * (hb + 0.5) - (1 if side > 0 else 0), -20, -hl + 4, 1, 8, 10)
        h.box("lantern", side * 30 - 4, -52, -hl - 10, 8, 10, 8)          # stern lanterns
        h.box("dark", side * 30 - 1, -42, -hl - 8, 2, 4, 4)
    h.box("dark", -2, 0, -hl - 14, 4, height + 4, 8)                     # rudder
    # guns in their ports, anchors, figurehead
    for side in (-1, 1):
        for z in (-50, -18, 14, 46):
            gun(h, side, hb, -1, z)
        h.box("iron", side * (hb + 1) - 1, -6, hl - 10, 3, 26, 3)          # anchor shank
        h.box("iron", side * (hb + 1) - 2, 18, hl - 16, 5, 3, 15)          # anchor arms
        h.box("dark", side * (hb + 1) - 2, -8, hl - 18, 5, 3, 19)          # anchor stock
    h.box("gold", -4, -12, bow_end + 2, 8, 14, 8)                         # figurehead
    # deck fittings
    h.box("capstan", -7, -14, 8, 14, 16, 14)
    h.box("hatch", -14, 1, -40, 28, 1, 30)
    h.box("hatch", -12, 1, 60, 24, 1, 20)
    h.box("barrel", 22, -12, -60, 10, 14, 10)
    h.box("barrel", 22, -12, -48, 10, 14, 10)
    h.box("crate", -32, -12, -62, 14, 14, 14)
    wheel = root.child("wheel", pivot=(0, -44, -hl + 18))
    wheel.box("dark", -2, 0, -1, 4, 16, 2)
    wheel.box("wheel", -10, -10, -2, 20, 20, 2)

    rig = root.child("rig")
    masts = {"fore": 44, "main": -22}
    for name, mz in masts.items():
        s = 0.92 if name == "fore" else 1.0
        rig.box("mast", -4, -130, mz - 4, 8, 132, 8)                      # lower mast
        rig.box("dark", -16, -134, mz - 13, 32, 4, 26)                    # top (platform)
        rig.box("mast", -3, -206, mz - 3, 6, 76, 6)                       # topmast
        rig.box("dark", -10, -208, mz - 5, 20, 3, 10)                     # crosstrees
        rig.box("mast", -2, -252, mz - 2, 4, 46, 4)                       # topgallant
        rig.box("dark", -3, -256, mz - 3, 6, 4, 6)
        for y, half in ((-122, 64 * s), (-196, 50 * s), (-242, 36 * s)):
            rig.box("spar", -half, y - 2, mz + 3, half * 2, 4, 4)         # yards
        square_sail(root, f"sail_{name}_course", 60 * s, 64 * s, -120, 78, mz + 5)
        square_sail(root, f"sail_{name}_top", 46 * s, 58 * s, -194, 58, mz + 5)
        square_sail(root, f"sail_{name}_topgallant", 32 * s, 44 * s, -240, 40, mz + 5)
        for side in (-1, 1):
            for i, dz in enumerate((-18, -6, 6, 18)):
                rope(root, f"shroud_{name}_{side}_{i}", (side * (hb - 2), -16, mz + dz), (side * 15, -132, mz))
            for i, dz in enumerate((-8, 8)):
                rope(root, f"topshroud_{name}_{side}_{i}", (side * 15, -134, mz + dz), (side * 5, -204, mz))
        pennant = root.child(f"flag_{name}", pivot=(0, -258, mz), anim="flag")
        pennant.box("pennant", -1, 0, -40, 2, 6, 40)

    bs_len, bs_rot = 70, 0.28
    bowsprit = root.child("bowsprit", pivot=(0, -16, bow_end - 8), rot=(bs_rot, 0, 0))
    bowsprit.box("spar", -3, -3, 0, 6, 6, bs_len)
    bowsprit.box("spar", -2, -2, bs_len - 6, 4, 4, 40)                    # jib boom
    tip = (0, -16 - (bs_len + 34) * math.sin(bs_rot), bow_end - 8 + (bs_len + 34) * math.cos(bs_rot))
    jib = root.child("sail_jib", pivot=(0, -20, 0), anim="fore_aft")
    fore_and_aft(jib, [(tip[2] - 10, tip[1] - 4), (masts["fore"] + 14, -196), (masts["fore"] + 30, -24)], 10, 20, 6)
    rope(root, "forestay", tip, (0, -250, masts["fore"]))
    rope(root, "stay_main", (0, -130, masts["fore"]), (0, -250, masts["main"]))
    rope(root, "stay_lower", (0, -24, masts["fore"] + 30), (0, -128, masts["main"]))
    # ensign on a flagstaff at the stern
    root.child("flagstaff").box("dark", -1, -84, -hl - 8, 2, 46, 2)
    ensign = root.child("flag_ensign", pivot=(0, -84, -hl - 8), anim="flag")
    ensign.box("flag", -1, 0, -34, 2, 22, 34)
    return root


# ---------------------------------------------------------------------------- galleon

def lateen(root, name, mz, y_top, y_bot, z_fore, z_aft):
    """A fore-and-aft (lateen) sail on the mizzen: a tall triangle abaft the mast."""
    p = root.child(name, pivot=(0, y_bot, 0), anim="fore_aft")
    fore_and_aft(p, [(z_fore, y_bot), (z_aft, y_bot), (mz - 2, y_top)], 10, -y_bot, 6)
    return p


def square_rig(root, rig, name, mz, hb, levels, s=1.0):
    """A mast with its tops and yards and square sails: levels of (yard y, half width at the head, at the foot, depth)."""
    top_y = levels[-1][0] - 14
    rig.box("mast", -4, -130, mz - 4, 8, 132, 8)
    rig.box("dark", -16, -134, mz - 13, 32, 4, 26)
    rig.box("mast", -3, top_y, mz - 3, 6, -130 - top_y + 4, 6)
    rig.box("dark", -10, -208, mz - 5, 20, 3, 10)
    names = ["course", "top", "topgallant"]
    for i, (y, h_top, h_bot, depth) in enumerate(levels):
        rig.box("spar", -h_bot * s, y - 2, mz + 3, h_bot * 2 * s, 4, 4)
        square_sail(root, f"sail_{name}_{names[i]}", h_top * s, h_bot * s, y + 2, depth, mz + 5)
    for side in (-1, 1):
        for i, dz in enumerate((-18, -6, 6, 18)):
            rope(root, f"shroud_{name}_{side}_{i}", (side * (hb - 2), -16, mz + dz), (side * 15, -132, mz))
        for i, dz in enumerate((-8, 8)):
            rope(root, f"topshroud_{name}_{side}_{i}", (side * 15, -134, mz + dz), (side * 5, -204, mz))
    pennant = root.child(f"flag_{name}", pivot=(0, top_y - 4, mz), anim="flag")
    pennant.box("pennant", -1, 0, -40, 2, 6, 40)


def castle(h, hb, z0, z1, y_floor, y_top, wall=5):
    """A raised castle (quarterdeck, poop, forecastle): sides, a deck on top and a rail with posts."""
    h.box("hull", -hb, y_top, z0, hb * 2, y_floor - y_top, z1 - z0, side=True, castle=True)
    h.box("deck", -hb + wall, y_top - 2, z0, hb * 2 - wall * 2, 2, z1 - z0)
    h.box("cap", -hb - 1, y_top - 14, z0, 6, 2, z1 - z0)
    h.box("cap", hb - 5, y_top - 14, z0, 6, 2, z1 - z0)
    for z in range(z0 + 2, z1 - 1, 8):
        h.box("dark", -hb, y_top - 12, z, 2, 10, 2)
        h.box("dark", hb - 2, y_top - 12, z, 2, 10, 2)


def build_galleon():
    root = Part("root")
    hb, hl, height = 46, 116, 30
    ports = [-64, -32, 0, 32, 64]
    _, bow_end = hull(root, hb, hl, height, 5, [0.94, 0.86, 0.76, 0.64, 0.5, 0.36, 0.22], 14, "wale_red",
                      ports=ports, rows=[(-2, 9)], wl=14)
    h = root.children[0]
    # the stern: two tiers of castle, the upper one shorter, galleries of windows and gilding at the transom
    castle(h, hb, -hl - 6, -hl + 46, -2, -26)
    castle(h, hb - 4, -hl - 6, -hl + 18, -26, -48)
    h.box("cap", -hb + 3, -62, -hl - 6, hb * 2 - 6, 2, 5)
    for x in range(-hb + 8, hb - 8, 12):
        h.box("glass", x, -22, -hl - 7, 8, 10, 1)
        h.box("glass", x, -44, -hl - 7, 8, 10, 1)
    h.box("gold", -hb + 2, -12, -hl - 8, hb * 2 - 4, 3, 2)
    h.box("gold", -hb + 2, -34, -hl - 8, hb * 2 - 4, 3, 2)
    h.box("gold", -6, -60, -hl - 9, 12, 12, 3)                             # the arms on the transom
    for side in (-1, 1):
        h.box("lantern", side * 32 - 4, -74, -hl - 10, 8, 10, 8)
        h.box("dark", side * 32 - 1, -64, -hl - 8, 2, 4, 4)
        h.box("glass", side * (hb + 0.5) - (1 if side > 0 else 0), -42, -hl + 2, 1, 8, 10)
    h.box("dark", -8, -24, -hl + 45, 16, 22, 2)
    h.box("door", -6, -22, -hl + 46, 12, 20, 1)
    # the forecastle at the bow
    castle(h, hb - 2, hl - 34, hl, -2, -20)
    h.box("dark", -2, 0, -hl - 14, 4, height + 4, 8)                       # rudder
    for side in (-1, 1):
        for z in ports:
            gun(h, side, hb, -1, z)
        h.box("iron", side * (hb + 1) - 1, -6, hl - 12, 3, 26, 3)
        h.box("iron", side * (hb + 1) - 2, 18, hl - 18, 5, 3, 15)
        h.box("dark", side * (hb + 1) - 2, -8, hl - 20, 5, 3, 19)
    h.box("gold", -4, -16, bow_end + 2, 8, 16, 8)                           # figurehead
    h.box("capstan", -7, -14, 10, 14, 16, 14)
    h.box("hatch", -16, 1, -30, 32, 1, 34)
    h.box("hatch", -12, 1, 40, 24, 1, 22)
    h.box("barrel", 26, -12, 16, 10, 14, 10)
    h.box("barrel", 26, -12, 28, 10, 14, 10)
    h.box("crate", -38, -12, 18, 14, 14, 14)
    wheel = root.child("wheel", pivot=(0, -66, -hl + 30))
    wheel.box("dark", -2, 0, -1, 4, 16, 2)
    wheel.box("wheel", -10, -10, -2, 20, 20, 2)

    rig = root.child("rig")
    masts = {"fore": 60, "main": -4, "mizzen": -66}
    square_rig(root, rig, "fore", masts["fore"], hb, [(-124, 60, 66, 76), (-198, 44, 56, 58)], 0.92)
    square_rig(root, rig, "main", masts["main"], hb, [(-124, 64, 70, 80), (-198, 48, 60, 60), (-244, 32, 44, 40)])
    mz = masts["mizzen"]
    rig.box("mast", -3, -190, mz - 3, 6, 160, 6)
    rig.box("dark", -12, -130, mz - 10, 24, 4, 20)
    # the lateen yard: along the sail's leading edge, from its tack to its head at the masthead
    ya, yb = (mz + 28, -62), (mz - 2, -180)
    ylen = math.hypot(yb[0] - ya[0], yb[1] - ya[1])
    lat = root.child("lateen_yard", pivot=(0, ya[1], ya[0]), rot=(math.atan2(-(yb[1] - ya[1]), -(yb[0] - ya[0])) * -1, 0, 0))
    lat.box("spar", -2, -2, -ylen, 4, 4, ylen)
    lateen(root, "sail_mizzen", mz, -176, -66, mz + 24, mz - 72)
    for side in (-1, 1):
        for i, dz in enumerate((-10, 6)):
            rope(root, f"shroud_mizzen_{side}_{i}", (side * (hb - 6), -40, mz + dz), (side * 10, -130, mz))
    rope(root, "stay_mizzen", (0, -130, masts["main"]), (0, -186, mz))
    pennant = root.child("flag_mizzen", pivot=(0, -194, mz), anim="flag")
    pennant.box("pennant", -1, 0, -40, 2, 6, 40)

    bs_len, bs_rot = 80, 0.3
    bowsprit = root.child("bowsprit", pivot=(0, -18, bow_end - 8), rot=(bs_rot, 0, 0))
    bowsprit.box("spar", -3, -3, 0, 6, 6, bs_len)
    tip = (0, -18 - bs_len * math.sin(bs_rot), bow_end - 8 + bs_len * math.cos(bs_rot))
    square_sail(root, "sail_sprit", 26, 22, tip[1] + 6, 30, tip[2] - 20)
    rope(root, "forestay", tip, (0, -196, masts["fore"]))
    rope(root, "stay_main", (0, -130, masts["fore"]), (0, -242, masts["main"]))
    root.child("flagstaff").box("dark", -1, -106, -hl - 8, 2, 46, 2)
    ensign = root.child("flag_ensign", pivot=(0, -106, -hl - 8), anim="flag")
    ensign.box("flag", -1, 0, -40, 2, 26, 40)
    return root


# ---------------------------------------------------------------------------- ship of the line

def build_line():
    root = Part("root")
    hb, hl, height = 52, 150, 46
    ports = [-112, -80, -48, -16, 16, 48, 80]
    rows = [(-2, 8), (14, 24)]
    _, bow_end = hull(root, hb, hl, height, 6, [0.95, 0.88, 0.78, 0.66, 0.52, 0.38, 0.24], 12, "wale_black",
                      ports=ports, rows=rows, wl=32)
    h = root.children[0]
    castle(h, hb, -hl - 6, -hl + 60, -2, -24, 6)
    for x in range(-hb + 8, hb - 8, 11):
        h.box("glass", x, -20, -hl - 7, 7, 10, 1)
        h.box("glass", x, -2, -hl - 7, 7, 10, 1)
    h.box("gold", -hb + 2, -10, -hl - 8, hb * 2 - 4, 3, 2)
    h.box("gold", -hb + 2, 10, -hl - 8, hb * 2 - 4, 3, 2)
    h.box("gold", -8, -40, -hl - 9, 16, 14, 3)
    for side in (-1, 1):
        h.box("lantern", side * 36 - 4, -48, -hl - 10, 8, 10, 8)
        h.box("dark", side * 36 - 1, -38, -hl - 8, 2, 4, 4)
    h.box("dark", -8, -22, -hl + 59, 16, 22, 2)
    h.box("door", -6, -20, -hl + 60, 12, 20, 1)
    castle(h, hb - 2, hl - 40, hl, -2, -18, 6)
    h.box("dark", -2, 0, -hl - 14, 4, height + 4, 8)                       # rudder
    for side in (-1, 1):
        for y0, y1 in rows:
            for z in ports:
                gun(h, side, hb, y0 + 1, z)
        h.box("iron", side * (hb + 1) - 1, -6, hl - 14, 3, 30, 3)
        h.box("iron", side * (hb + 1) - 2, 22, hl - 20, 5, 3, 15)
        h.box("dark", side * (hb + 1) - 2, -8, hl - 22, 5, 3, 19)
    h.box("gold", -5, -14, bow_end + 2, 10, 18, 10)                          # figurehead
    h.box("capstan", -8, -14, 4, 16, 16, 16)
    for z in (-60, -14, 40, 90):
        h.box("hatch", -16, 1, z, 32, 1, 24)
    h.box("barrel", 30, -12, 60, 10, 14, 10)
    h.box("barrel", 30, -12, 72, 10, 14, 10)
    h.box("crate", -42, -12, 62, 14, 14, 14)
    wheel = root.child("wheel", pivot=(0, -42, -hl + 46))
    wheel.box("dark", -2, 0, -1, 4, 16, 2)
    wheel.box("wheel", -10, -10, -2, 20, 20, 2)

    rig = root.child("rig")
    masts = {"fore": 86, "main": 4, "mizzen": -80}
    full = [(-124, 66, 72, 80), (-198, 50, 62, 60), (-244, 34, 46, 40)]
    square_rig(root, rig, "fore", masts["fore"], hb, full, 0.94)
    square_rig(root, rig, "main", masts["main"], hb, full, 1.04)
    square_rig(root, rig, "mizzen", masts["mizzen"], hb, [(-124, 50, 56, 70), (-198, 38, 46, 56)], 0.86)
    bs_len, bs_rot = 90, 0.28
    bowsprit = root.child("bowsprit", pivot=(0, -16, bow_end - 8), rot=(bs_rot, 0, 0))
    bowsprit.box("spar", -3, -3, 0, 6, 6, bs_len)
    bowsprit.box("spar", -2, -2, bs_len - 6, 4, 4, 50)
    tip = (0, -16 - (bs_len + 44) * math.sin(bs_rot), bow_end - 8 + (bs_len + 44) * math.cos(bs_rot))
    jib = root.child("sail_jib", pivot=(0, -20, 0), anim="fore_aft")
    fore_and_aft(jib, [(tip[2] - 10, tip[1] - 4), (masts["fore"] + 14, -196), (masts["fore"] + 30, -24)], 10, 20, 6)
    rope(root, "forestay", tip, (0, -250, masts["fore"]))
    rope(root, "stay_main", (0, -130, masts["fore"]), (0, -250, masts["main"]))
    rope(root, "stay_mizzen", (0, -130, masts["main"]), (0, -204, masts["mizzen"]))
    root.child("flagstaff").box("dark", -1, -80, -hl - 8, 2, 46, 2)
    ensign = root.child("flag_ensign", pivot=(0, -80, -hl - 8), anim="flag")
    ensign.box("flag", -1, 0, -44, 2, 28, 44)
    return root


# ============================================================================ painting

def rnd(*k):
    # (a stable hash: the same texture every run, unlike Python's own salted hash of strings)
    return random.Random(zlib.crc32(repr(k).encode())).random()


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, d):
    return tuple(max(0, min(255, int(v + d))) for v in c[:3]) + (c[3],)


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)


class Palette:
    def __init__(self, **kw):
        self.__dict__.update(kw)


SLOOP = Palette(hull=hexc("#9a6d42"), dark=hexc("#4a3222"), deck=hexc("#c49a66"), cap=hexc("#6e4a2c"),
                below=hexc("#6b3326"), wale=hexc("#2f5d8c"), wale_edge=hexc("#e8e0c8"), sail=hexc("#f1ead7"),
                stripe=hexc("#2f5d8c"), flag1=hexc("#2f5d8c"), flag2=hexc("#f1e9d4"), mast=hexc("#8a6238"))
BRIG = Palette(hull=hexc("#6a3a24"), dark=hexc("#33211a"), deck=hexc("#b58a58"), cap=hexc("#4a2c1c"),
               below=hexc("#1f1a1a"), wale=hexc("#1c1817"), wale_edge=hexc("#d3a33e"), sail=hexc("#ece1c4"),
               stripe=hexc("#9c2f24"), flag1=hexc("#9c2f24"), flag2=hexc("#e2b94e"), mast=hexc("#7a5532"))


GALLEON = Palette(hull=hexc("#7a4426"), dark=hexc("#3a2418"), deck=hexc("#b88c58"), cap=hexc("#5a321e"),
                  below=hexc("#22201e"), wale=hexc("#8c1f1a"), wale_edge=hexc("#e0b54a"), sail=hexc("#efe4c4"),
                  stripe=hexc("#b8292c"), flag1=hexc("#b8292c"), flag2=hexc("#e8c040"), mast=hexc("#7a5532"))
LINE = Palette(hull=hexc("#1e1c1c"), dark=hexc("#2a2420"), deck=hexc("#b48a56"), cap=hexc("#2e2622"),
               below=hexc("#1a1716"), wale=hexc("#141212"), wale_edge=hexc("#d8b04a"), sail=hexc("#f0e8d2"),
               stripe=hexc("#2a3f78"), flag1=hexc("#2a3f78"), flag2=hexc("#e8e2d0"), mast=hexc("#7a5532"),
               band=hexc("#d2a248"))


def wood(pal_col, ax, ay, strake=4, along=None, vertical=False):
    """Planks: 'along' is the coordinate running along the plank, 'ay' across (strakes)."""
    a, b = (ay, along) if not vertical else (along, ay)
    row = b // strake
    c = shade(pal_col, (rnd("s", row) - 0.5) * 16)
    c = shade(c, (rnd("g", a // 3, row) - 0.5) * 8)                # grain streaks
    if b % strake == strake - 1:
        return shade(c, -38)                                         # caulked seam
    joint = (a + row * 17) % 48
    if joint == 0:
        return shade(c, -30)                                         # butt joint
    if joint in (2, 45) and b % strake == 1:
        return shade(c, -55)                                         # treenail
    return c


def paint_pixel(mat, face, ax, ay, az, box, pal):
    """Colour of one texel at absolute model coordinates (ax, ay, az) on the given face of the box."""
    side_face = face in ("east", "west")
    endface = face in ("north", "south")
    top = face in ("up", "down")
    if mat == "hull":
        if top:
            return wood(pal.dark, ax, az, 4, along=az)
        along = az if side_face else ax
        c = wood(pal.hull, along, ay, 4, along=along)
        if ay > 0:
            c = shade(c, -ay * 0.5)                                  # darker down towards the water (shadow, wet)
        if box.deco.get("side") and side_face and not box.deco.get("castle"):
            wl = box.deco.get("wl", 12)
            for y0, y1 in box.deco.get("rows", []):
                if getattr(pal, "band", None) and y0 - 3 <= ay <= y1 + 3:
                    c = shade(pal.band, (rnd("bd", along // 5, ay) - 0.5) * 10)   # a painted strake along the gun deck
            if ay >= wl:                                             # below the water line
                c = mix(shade(pal.below, (rnd("b", along // 6, ay // 4) - 0.5) * 10), c, 0.12)
            elif ay == wl - 1:
                c = hexc("#e7e2d2")                                  # boot-top line
            for y0, y1 in box.deco.get("rows", []):                  # gun ports
                if y0 <= ay <= y1:
                    for gz in box.deco.get("ports", []):
                        if gz - 7 <= along <= gz + 6:
                            edge = along in (gz - 7, gz + 6) or ay in (y0, y1)
                            return hexc("#2a1a12") if edge else hexc("#120c0a")
        if box.deco.get("castle") and side_face and ay < -4 and (along // 8) % 3 == 0 and -20 <= ay <= -10:
            return hexc("#2a1a12")
        return c
    if mat == "deck":
        if top:
            return wood(pal.deck, az, ax, 5, along=az)             # planks run fore and aft
        return wood(pal.deck, ax + az, ay, 5, along=ax + az)
    if mat in ("dark", "keel", "cap"):
        col = pal.cap if mat == "cap" else pal.dark
        along = az if side_face or top else ax
        c = wood(col, along, ay if not top else ax, 3, along=along)
        if mat == "cap" and (face in ("down",) or ay == box.y):
            c = shade(c, 18)
        return c
    if mat in ("wale_blue", "wale_black", "wale_red"):
        if ay in (box.y, box.y + box.h - 1):
            return pal.wale_edge
        return shade(pal.wale, (rnd("w", az // 5) - 0.5) * 8)
    if mat == "mast":
        if top:
            ring = int(math.hypot(ax, az))
            return shade(pal.mast, -20 if ring % 2 else 0)
        c = shade(pal.mast, ((ax + az) % 3 == 0) * -12 + (rnd("m", ax + az, ay // 5) - 0.5) * 10)
        if ay % 40 in (0, 1, 2):
            return hexc("#3b3a3e") if ay % 40 != 1 else hexc("#57565c")  # iron bands
        return c
    if mat == "spar":
        return shade(pal.dark, 10 + (rnd("sp", (ax + ay + az) // 4) - 0.5) * 12)
    if mat in ("sail", "sail_square"):
        base = pal.sail
        c = shade(base, (rnd("c", ax, ay, az) - 0.5) * 6)
        if mat == "sail_square":
            # the cloth full of wind: lighter in the belly, shaded towards the leeches and the foot
            k = min(1.0, abs(ax) / max(1.0, box.deco.get("half", box.w / 2)))
            c = shade(c, -18 * k * k - (6 if (ay - box.y) > box.h * 0.6 else 0))
        seam_coord = az if mat == "sail" else ax
        if seam_coord % 12 == 0:
            c = shade(base, -22)                                     # cloth seams
        if ay % 30 == 18 and seam_coord % 4 == 0:
            c = shade(base, -45)                                     # reef points
        if mat == "sail" and pal is SLOOP and ay % 60 in range(26, 31):
            c = shade(pal.stripe, (rnd("st", seam_coord) - 0.5) * 8)
        if mat == "sail_square":
            if abs(ax) >= box.deco.get("half", abs(box.x)) - 1:
                c = shade(base, -30)                                 # bolt rope along the leeches
            if pal in (BRIG, GALLEON) and box.deco.get("emblem"):
                mid = box.deco.get("mid", 0)
                if abs(ax) <= 4 or abs(ay - mid) <= 3:
                    c = shade(pal.stripe, (rnd("e", ax, ay) - 0.5) * 6)   # red cross on the courses
            if pal in (BRIG, GALLEON, LINE) and not box.deco.get("emblem") and ay % 40 in range(8, 11):
                c = shade(pal.stripe, (rnd("e2", ax) - 0.5) * 6)
        return c
    if mat == "rope":
        return shade(hexc("#b59a66"), -25 if (ax + ay + az) % 3 == 0 else (rnd("r", ax, ay, az) - 0.5) * 12)
    if mat == "rope_coil":
        r = int(math.hypot(ax - (box.x + box.w / 2), az - (box.z + box.d / 2)))
        return shade(hexc("#b59a66"), -30 if r % 2 else 0)
    if mat == "hatch":
        if top:
            if (ax - box.x) % 4 == 0 or (az - box.z) % 4 == 0:
                return shade(pal.dark, 5)
            return hexc("#140e0a")
        return pal.dark
    if mat == "barrel":
        if top:
            return shade(hexc("#8a5f35"), -15 if int(math.hypot(ax - box.x - 5, az - box.z - 5)) % 3 == 0 else 0)
        rel = ay - box.y
        if rel in (2, 3, box.h - 4, box.h - 3):
            return hexc("#46454a")
        stave = (ax + az) // 3
        return shade(hexc("#8a5f35"), (rnd("bs", stave) - 0.5) * 20 + (-18 if (ax + az) % 3 == 0 else 0))
    if mat == "crate":
        lx, ly = (az - box.z if side_face else ax - box.x), ay - box.y
        wmax = box.d if side_face else box.w
        if top:
            lx, ly, wmax = ax - box.x, az - box.z, box.w
        edge = lx in (0, 1, wmax - 2, wmax - 1) or ly in (0, 1, (box.h if not top else box.d) - 2,
                                                           (box.h if not top else box.d) - 1)
        c = shade(hexc("#b8905a"), (rnd("cr", ly // 3) - 0.5) * 14)
        return shade(c, -30) if edge else c
    if mat == "lantern":
        if (ay - box.y) in (0, box.h - 1) or ((ax - box.x) in (0, box.w - 1) and not top):
            return hexc("#6a5020")
        return hexc("#ffd97a") if (ax + az) % 3 else hexc("#ffc44a")
    if mat == "glass":
        lx = (az - box.z) if side_face else (ax - box.x)
        ly = ay - box.y
        wmax = box.d if side_face else box.w
        if lx in (0, wmax - 1) or ly in (0, box.h - 1) or lx == wmax // 2 or ly == box.h // 2:
            return hexc("#c9a55a")
        return hexc("#bfe4f2") if (lx + ly) % 5 else hexc("#f5fbff")
    if mat == "door":
        return shade(pal.dark, -10 if (ax - box.x) % 3 == 0 else 0)
    if mat == "iron":
        c = hexc("#34353a")
        if ay == box.y or ax == box.x:
            c = hexc("#55565c")
        return shade(c, (rnd("i", ax, ay, az) - 0.5) * 8)
    if mat == "gold":
        return shade(hexc("#c79a3c"), (rnd("au", ax // 2, ay // 2) - 0.5) * 30)
    if mat == "capstan":
        return shade(pal.dark, 20 if (ax + az) % 4 < 2 else -5)
    if mat == "wheel":
        lx, ly = ax - box.x - 10, ay - box.y - 10
        r = math.hypot(lx, ly)
        if 7.5 <= r <= 10 or r < 2.5:
            return hexc("#6e4a2c")
        if abs(lx) <= 1 or abs(ly) <= 1 or abs(abs(lx) - abs(ly)) <= 1:
            return hexc("#8a6238")
        return (0, 0, 0, 0)
    if mat == "flag":
        band = (ay - box.y) // 4
        return pal.flag1 if band % 2 == 0 else pal.flag2
    if mat == "pennant":
        return pal.flag1 if (az // 6) % 2 == 0 else pal.flag2
    if mat == "keel":
        return pal.dark
    return hexc("#ff00ff")


def face_rects(b):
    u, v, w, h, d = b.u, b.v, b.w, b.h, b.d
    return {
        "down": (u + d, v, w, d),                 # model -Y face (the top in the world)
        "up": (u + d + w, v, w, d),
        "west": (u, v + d, d, h),
        "north": (u + d, v + d, w, h),
        "east": (u + d + w, v + d, d, h),
        "south": (u + 2 * d + w, v + d, w, h),
    }


def face_coord(face, b, i, j):
    """Absolute model coordinates of texel (i, j) inside a face rectangle."""
    if face in ("down", "up"):
        return b.x + i, b.y if face == "down" else b.y + b.h - 1, b.z + j
    if face in ("north", "south"):
        return b.x + i, b.y + j, b.z if face == "north" else b.z + b.d - 1
    return (b.x if face == "west" else b.x + b.w - 1), b.y + j, b.z + (b.d - 1 - i if face == "west" else i)


def pack(boxes, width):
    """Shelf packing of box UV footprints; returns the texture height (power of two)."""
    order = sorted(boxes, key=lambda b: -(b.d + b.h))
    x = y = shelf = 0
    for b in order:
        fw, fh = 2 * (b.w + b.d), b.d + b.h
        if x + fw > width:
            x, y, shelf = 0, y + shelf, 0
        b.u, b.v = x, y
        x += fw
        shelf = max(shelf, fh)
    total = y + shelf
    size = 64
    while size < total:
        size *= 2
    return size


EPS = 0.12


def faces(b):
    """The six faces of a box: (axis, side, plane, rect in the other two axes)."""
    lo = (b.x, b.y, b.z)
    hi = (b.x + b.w, b.y + b.h, b.z + b.d)
    out = []
    for a in range(3):
        o = [i for i in range(3) if i != a]
        rect = (lo[o[0]], hi[o[0]], lo[o[1]], hi[o[1]])
        out.append((a, "-", lo[a], rect))
        out.append((a, "+", hi[a], rect))
    return out


def unfight(root):
    """Faces of two boxes of a part in one plane, facing the same way and overlapping, flicker (z-fighting): the box
    added later is grown a hair all round (its texture stays as it is), so that it is drawn over the other.
    Returns the number of boxes grown."""
    pushed = 0

    def visit(part):
        nonlocal pushed
        boxes = part.boxes
        for j in range(len(boxes)):
            for i in range(j):
                for (a, sd, pl, r), (a2, sd2, pl2, r2) in ((f, g) for f in faces(boxes[j]) for g in faces(boxes[i])):
                    if a != a2 or sd != sd2 or pl != pl2:
                        continue
                    if min(r[1], r2[1]) - max(r[0], r2[0]) <= 0 or min(r[3], r2[3]) - max(r[2], r2[2]) <= 0:
                        continue
                    if boxes[j].grow < boxes[i].grow + EPS:
                        if boxes[j].grow == 0:
                            pushed += 1
                        boxes[j].grow = boxes[i].grow + EPS
        for c in part.children:
            visit(c)

    visit(root)
    return pushed


def pirate(img):
    """The same ship under black sails (and dark glass): the pirates' texture."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a and max(r, g, b) > 175 and max(r, g, b) - min(r, g, b) < 60:
                v = int(22 + 38 * (r + g + b) / 3 / 255)
                px[x, y] = (v, v - 3, v - 5, a)
    return out


def sail_textures():
    """The sails' cloth: canvas with its seams (vertical cloths) and reef bands; a navy course's red cross; the
    pirates' black cloth, patched, with a skull on the courses."""
    import random
    random.seed(7)
    n = 64

    def cloth(base, seam, band, extra=None):
        img = Image.new("RGBA", (n, n))
        px = img.load()
        for y in range(n):
            for x in range(n):
                c = base
                v = random.randint(-5, 5)
                if x % 8 == 0:
                    c = seam
                if y in (12, 13, 26, 27):
                    c = band
                c = tuple(max(0, min(255, ch + v)) for ch in c[:3]) + (255,)
                if extra:
                    c = extra(x, y, c)
                px[x, y] = c
        return img

    canvas, seam, band = (241, 234, 215), (214, 204, 182), (225, 216, 196)
    cloth(canvas, seam, band).save(os.path.join(TEXTURES, "sail.png"))

    def cross(x, y, c):
        if abs(x - 32) <= 4 or abs(y - 36) <= 4:
            return (178, 34, 34, 255)
        return c
    cloth(canvas, seam, band, cross).save(os.path.join(TEXTURES, "sail_cross.png"))
    black, bseam, bband = (38, 36, 36), (24, 22, 22), (30, 28, 28)
    patches = [(random.randint(2, 54), random.randint(2, 54), random.randint(5, 9)) for _ in range(5)]

    def patched(x, y, c):
        for (px0, py0, sz) in patches:
            if px0 <= x < px0 + sz and py0 <= y < py0 + sz:
                return (58, 52, 48, 255)
        return c
    cloth(black, bseam, bband, patched).save(os.path.join(TEXTURES, "sail_pirate.png"))
    skull = ["..XXXXXX..", ".XXXXXXXX.", "XXXXXXXXXX", "XX..XX..XX", "XX..XX..XX", "XXXXXXXXXX", ".XXXX.XXX.",
             "..XXXXXX..", "..X.XX.X..", "..........", "X........X", ".XX....XX.", "...XXXX...", ".XX....XX.", "X........X"]

    def skulled(x, y, c):
        c = patched(x, y, c)
        sx, sy = (x - 22) // 2, (y - 18) // 2
        if 0 <= sy < len(skull) and 0 <= sx < len(skull[sy]) and skull[sy][sx] == "X":
            return (226, 222, 210, 255)
        return c
    cloth(black, bseam, bband, skulled).save(os.path.join(TEXTURES, "sail_pirate_skull.png"))


def export(name, root, pal, width=1024, sails=None):
    fixed = unfight(root)
    boxes = [b for b, _ in root.all_boxes()]
    height = pack(boxes, width)
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    px = img.load()
    for b, part in root.all_boxes():
        # boxes of rotated parts get painted in their own local frame, which is fine for ropes and spars
        for face, (u, v, fw, fh) in face_rects(b).items():
            for j in range(fh):
                for i in range(fw):
                    ax, ay, az = face_coord(face, b, i, j)
                    ay_abs = ay + (part.pivot[1] if part.anim else 0)
                    px[u + i, v + j] = paint_pixel(b.mat, face, ax, ay_abs, az + (part.pivot[2] if part.anim else 0), b, pal)

    def part_json(p):
        return {
            "name": p.name,
            "pivot": list(p.pivot),
            "rotation": list(p.rot),
            "anim": p.anim,
            "boxes": [dict({"uv": [b.u, b.v], "from": [b.x, b.y, b.z], "size": [b.w, b.h, b.d]},
                           **({"grow": round(b.grow, 3)} if b.grow else {})) for b in p.boxes],
            "children": [part_json(c) for c in p.children],
        }

    os.makedirs(MODELS, exist_ok=True)
    os.makedirs(TEXTURES, exist_ok=True)
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"texture_size": [width, height], "scale": 0.5,
                   "parts": [part_json(c) for c in root.children], "sails": sails or []}, f)
    img.save(os.path.join(TEXTURES, name + ".png"))
    if name != "sloop":
        pirate(img).save(os.path.join(TEXTURES, name + "_pirate.png"))
    print(f"{name}: {len(boxes)} boxes, texture {width}x{height}, {fixed} boxes grown apart (flicker)")


if __name__ == "__main__":
    for name, build, pal, width in (("sloop", build_sloop, SLOOP, 1024), ("brig", build_brig, BRIG, 1024),
                                    ("galleon", build_galleon, GALLEON, 1024), ("ship_of_the_line", build_line, LINE, 2048)):
        SAILS.clear()
        root = build()
        export(name, root, pal, width=width, sails=[dict(x) for x in SAILS])
    sail_textures()
