# -*- coding: utf-8 -*-
"""
The village tree, locally: the data (tree.json) behind the editor, and a first model of a village's economy.

    python model.py sync
        tree.json -> tree-data.js (what index.html reads when it is opened from this folder)

    python model.py export
        tree.json -> the mod's copy in src/main/resources/data/minecraftportsmod/village/tree.json (run after every
        change of the tree the mod should get).

    python model.py reach
        Which building levels a village can ever get to, in order, and what the rest wait for.

    python model.py balance [--village 10|20|30|"hut@1=3,wood_hut@2=1,..."]
        A village's day, driven by demand: what the people eat and the buildings use up (upkeep) is asked of the
        buildings that make it; their recipes ask for their own inputs, and so on down the chains. Prints, for
        every category, what is asked against what could be made, and how busy each building's workers are.
        --village 10 / 20 / 30 are ready villages of that many people; or give "building@level=count,...".

Semantics of the data (the same as the editor's, v5):
    recipes[] : {lvl (from which building level), out {i: item | r: category, n pieces}, use [{r, n}] per making,
                 per (makings a day by ONE worker), p (chance in % the making gives anything; absent = always)}
                an item weighs `units` units of its category (items[]) in the store
    levels[i] : {cost (build/raise, once), use (upkeep a day: tool wear, light, feed; "p" chance), workers, note}
    unlock    : what opening the node in the tree takes, once
Food: every bed is a person eating 6 a day (the homes' own "жильцы" rows are not counted twice).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TREE = os.path.join(HERE, "tree.json")


def load():
    return json.load(open(TREE, encoding="utf-8"))


def sync():
    tree = load()
    with open(os.path.join(HERE, "tree-data.js"), "w", encoding="utf-8") as f:
        f.write("// Generated from tree.json by model.py sync; the editor (index.html) reads it when opened locally.\n")
        f.write("window.TREE_DATA = " + json.dumps(tree, ensure_ascii=False) + ";\n")
    print("tree-data.js:", len(tree["nodes"]), "buildings,", len(tree["catalog"]["resources"]), "resources")


BUILD_DAYS = 40
VILLAGES = {
    "10": "campfire@1=1,storehouse@1=1,hut@1=3,wood_hut@1=1,mine_house@1=1,smithy@1=1,field@1=2,farm@1=1,fish_hut@1=1",
    "20": "campfire@1=1,square@1=1,storehouse@2=1,house@1=4,hut@2=1,wood_hut@3=1,sawmill@2=1,smithy@2=1,"
          "mine_house@1=1,field@2=3,farm@2=1,fish_hut@1=1,bakery@1=1",
    "30": "campfire@1=1,square@1=1,well@1=1,storehouse@3=1,storehouse_2@1=1,house@2=4,house_tall@1=2,hut@3=1,"
          "wood_hut@3=1,sawmill@2=1,carpenter@1=1,smithy@3=1,mine_house@2=1,quarry@1=1,stonemason@1=1,smelter@1=1,potter@1=1,glassworks@1=1,"
          "field@2=4,farm@3=1,fish_hut@2=1,smokehouse@1=1,bakery@2=1,sheep_pen@1=1,market@1=1",
}


def parse_village(spec):
    spec = VILLAGES.get(spec, spec)
    out = []
    for part in spec.split(","):
        k, cnt = part.split("=")
        nid, lvl = k.split("@") if "@" in k else (k, "1")
        out.append((nid.strip(), int(lvl), int(cnt)))
    return out


def balance(spec):
    tree = load()
    names = {r["id"]: r["name"] for r in tree["catalog"]["resources"]}
    units = {i["id"]: (i["res"], i.get("units", 1)) for i in tree["items"]}
    nodes = tree["nodes"]
    village = parse_village(spec)
    groups, upkeep, beds = [], {}, 0
    for nid, lvl, cnt in village:
        n = nodes[nid]
        L = n["levels"][min(lvl, len(n["levels"])) - 1]
        beds += (n.get("beds") or 0) * cnt
        for x in L.get("use", []):
            if x["r"] == "food" and "жильц" in (x.get("t") or ""):
                continue
            upkeep[x["r"]] = upkeep.get(x["r"], 0) + x["n"] * x.get("p", 100) / 100 * cnt
        w = L.get("workers", n.get("workers") or 0)
        rs = [x for x in n.get("recipes", []) if x.get("lvl", 1) <= lvl]
        if rs:
            groups.append({"id": nid, "name": n["name"], "lvl": lvl, "cnt": cnt, "workers": (w or 1) * cnt, "load": 0.0, "recipes": rs,
                           "main": None})
    upkeep["food"] = upkeep.get("food", 0) + 6 * beds
    workers = sum(g["workers"] for g in groups if nodes[g["id"]].get("workers"))

    def out_units(x):
        o = x["out"]
        res, u = (units[o["i"]] if "i" in o else (o["r"], 1))
        return res, o["n"] * u * x.get("p", 100) / 100

    # who can make each category, and how much a worker-day of their best recipe for it gives
    makers = {}
    for g in groups:
        best = {}
        for x in g["recipes"]:
            res, u = out_units(x)
            rate = u * x.get("per", 1)
            if rate > best.get(res, (0, None))[0]:
                best[res] = (rate, x)
        for res, (rate, x) in best.items():
            makers.setdefault(res, []).append((g, rate, x))
    for g in groups:
        g["main"] = out_units(g["recipes"][0])[0]
    capacity = {r: sum(g["workers"] * rate for g, rate, _ in ms) for r, ms in makers.items()}

    # building: what the village's buildings cost, spread over BUILD_DAYS (the village keeps building)
    build = {}
    for nid, lvl, cnt in village:
        n = nodes[nid]
        for x in n.get("unlock", []):
            build[x["r"]] = build.get(x["r"], 0) + x["n"] / BUILD_DAYS
        for L in n["levels"][:lvl]:
            for x in L.get("cost", []):
                build[x["r"]] = build.get(x["r"], 0) + x["n"] * cnt / BUILD_DAYS
    for r, v in build.items():
        upkeep[r] = upkeep.get(r, 0) + v

    # demand down the chains; each ask goes first to the makers doing it best, as far as their workers last
    asked, unmet = {}, {}
    pending = dict(upkeep)
    sweep_main = True
    left = {}
    for _ in range(120):
        if not pending:
            break
        nxt = {}
        for r, d in sorted(pending.items(), key=lambda t: -t[1]):
            asked[r] = asked.get(r, 0) + d
            # a building does its own trade first (what its first recipe makes), side products only when nobody else can
            for g, rate, x in sorted(makers.get(r, []), key=lambda t: (t[0]["main"] != r, -t[1])):
                if d <= 1e-9:
                    break
                if sweep_main and g["main"] != r:
                    continue
                free = g["workers"] - g["load"]
                if free <= 1e-9:
                    continue
                days = min(free, d / rate)
                g["load"] += days
                d -= days * rate
                for y in x.get("use", []):
                    nxt[y["r"]] = nxt.get(y["r"], 0) + y["n"] * days * x.get("per", 1)
            if d > 1e-6:
                left[r] = left.get(r, 0) + d
        pending = nxt
        if not pending and sweep_main:
            sweep_main, pending, left = False, dict(left), {}
            for r, v in pending.items():
                asked[r] -= v          # asked again below, not twice
    unmet = left

    print(f"Village: {beds} people ({workers} of them at work), {sum(c for _, _, c in village)} buildings\n")
    print(f"  (building: the village's own costs spread over {BUILD_DAYS} days)")
    print(f"  {'category':26} {'asked':>8} {'can make':>9}")
    for r in sorted(set(asked) | set(capacity), key=lambda r: -(asked.get(r, 0))):
        a, c = asked.get(r, 0), capacity.get(r, 0)
        u = unmet.get(r, 0)
        flag = "NOBODY MAKES IT" if a > 0.01 and c == 0 else f"SHORT by {u:.1f}" if u > 0.05 else "idle" if a < 0.01 else ""
        print(f"  {names.get(r, r):26} {a:8.1f} {c:9.1f}  {flag}")
    print("\n  building (level x count)      busy")
    for g in sorted(groups, key=lambda g: -g["load"] / g["workers"]):
        pct = 100 * g["load"] / g["workers"]
        print(f"  {g['name'][:26]:26} {g['lvl']}x{g['cnt']}  {pct:5.0f}%{'  OVERLOADED' if pct > 100 else ''}")


MOD_TREE = os.path.join(HERE, "..", "..", "src", "main", "resources", "data", "minecraftportsmod", "village", "tree.json")


def export():
    """tree.json -> the mod's own copy (src/main/resources/data/minecraftportsmod/village/tree.json): what the
    buildings make (recipes), their workers by level, and the items (their category and weight in the store)."""
    tree = load()
    out = {"buildings": {}, "items": {i["id"]: {"res": i["res"], "units": i.get("units", 1), "name": i["name"]} for i in tree["items"]}}
    for nid, n in tree["nodes"].items():
        out["buildings"][nid] = {
            "name": n["name"],
            "workers": [L.get("workers", n.get("workers") or 0) for L in n["levels"]],
            "recipes": n.get("recipes", []),
        }
    os.makedirs(os.path.dirname(MOD_TREE), exist_ok=True)
    with open(MOD_TREE, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("mod tree:", os.path.normpath(MOD_TREE), len(out["buildings"]), "buildings,", sum(len(b["recipes"]) for b in out["buildings"].values()), "recipes")


def reach():
    """Which building levels a village can ever get to: a level needs its parent node (at the level its "opens"
    names), and everything its opening and building costs to be made by what already stands. By hand, from the
    start, people gather logs and stones and grow food."""
    import re
    tree = load()
    nodes = tree["nodes"]
    names = {r["id"]: r["name"] for r in tree["catalog"]["resources"]}
    units = {i["id"]: i["res"] for i in tree["items"]}
    by_name = {n["name"]: k for k, n in nodes.items()}
    have = {"log", "stone", "food", "grain"}
    built = {}                       # node -> highest level reached
    order = []
    changed = True
    while changed:
        changed = False
        for nid, n in sorted(nodes.items(), key=lambda t: t[1].get("order", 0)):
            lvl = built.get(nid, 0)
            if lvl >= len(n["levels"]):
                continue
            if lvl == 0:
                par = n.get("parent")
                need_lvl = 1
                m = re.search(r"(.+?)\s+(\d)\s*ур", n.get("opens") or "")
                if m and m.group(1).strip() in by_name:
                    par, need_lvl = by_name[m.group(1).strip()], int(m.group(2))
                if par and built.get(par, 0) < need_lvl:
                    continue
                costs = n.get("unlock", []) + n["levels"][0].get("cost", [])
            else:
                costs = n["levels"][lvl].get("cost", [])
            if all(x["r"] in have for x in costs):
                built[nid] = lvl + 1
                order.append(f"{n['name']} {lvl + 1}")
                for x in n.get("recipes", []):
                    if x.get("lvl", 1) <= lvl + 1:
                        o = x["out"]
                        have.add(units.get(o["i"]) if "i" in o else o["r"])
                changed = True
    print("Reachable, in the order the village can get them:\n  " + ", ".join(order))
    print("\nNever reached:")
    for nid, n in sorted(nodes.items(), key=lambda t: t[1].get("order", 0)):
        lvl = built.get(nid, 0)
        if lvl < len(n["levels"]):
            costs = (n.get("unlock", []) + n["levels"][0].get("cost", [])) if lvl == 0 else n["levels"][lvl].get("cost", [])
            miss = sorted({names.get(x["r"], x["r"]) for x in costs if x["r"] not in have})
            print(f"  {n['name']} {lvl + 1}: " + (("needs " + ", ".join(miss)) if miss else "waits for: " + (n.get("opens") or "?")))


def main(argv):
    if argv and argv[0] == "export":
        export()
        return
    if argv and argv[0] == "reach":
        reach()
        return
    if not argv or argv[0] not in ("sync", "balance"):
        print(__doc__)
        return
    if argv[0] == "sync":
        sync()
        return
    spec = "20"
    if len(argv) > 2 and argv[1] == "--village":
        spec = argv[2]
    balance(spec)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
