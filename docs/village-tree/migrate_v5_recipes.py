# v5 (2026-09-28): recipes per item (vanilla proportions), per = times a day for one worker;
# levels keep cost, upkeep (use) and workers; "sells" goes (a building sells what its recipes make).
import json, os, re

R = 'D:/AI/web trade plugin minecraft/MinecraftPortsMod/docs/village-tree/'
tree = json.load(open(R + 'tree.json', encoding='utf-8'))
nodes, items = tree['nodes'], tree['items']
ITEM = {i['id']: i for i in items}

# ---- items are single pieces now ("Палка ×4" -> "Палка", 1 unit)
for i in items:
    m = re.match(r'^(.*?)\s*×\s*\d+$', i['name'])
    if m:
        i['name'] = m.group(1)
        i['units'] = 1
SINGLE = {'stairs': 'Деревянная ступенька', 'slab': 'Деревянный полублок', 'stone_stairs': 'Каменная ступенька',
          'stone_slab': 'Каменная плита', 'glass_bottle': 'Склянка', 'glass_pane': 'Стеклянная панель',
          'iron_bars': 'Железная решётка', 'rail': 'Рельс', 'iron_nugget': 'Железный самородок', 'torch': 'Факел'}
for k, v in SINGLE.items():
    if k in ITEM: ITEM[k]['name'] = v

def rc(lvl, out, n, use=None, per=1, p=None):
    x = {'lvl': lvl, 'out': ({'r': out[2:]} if out.startswith('r:') else {'i': out}), 'use': [{'r': r, 'n': k} for r, k in (use or {}).items()], 'per': per}
    x['out']['n'] = n
    if p: x['p'] = p
    if 'i' in x['out']: assert x['out']['i'] in ITEM, out
    return x

T = 'tools'
REC = {
 # ---------------- wood
 'wood_hut': [rc(1, 'oak_log', 1, per=14), rc(1, 'spruce_log', 1, per=14), rc(1, 'birch_log', 1, per=14),
              rc(1, 'stick', 1, per=4), rc(1, 'r:fuel', 1, per=2), rc(1, 'apple', 1, per=1), rc(1, 'sapling', 1, per=4),
              rc(2, 'leaves', 1, per=16), rc(3, 'dark_oak_log', 1, per=10), rc(3, 'cherry_log', 1, per=10)],
 'sawmill': [rc(1, 'oak_planks', 4, {'log': 1}, 10), rc(1, 'spruce_planks', 4, {'log': 1}, 10), rc(1, 'stick', 4, {'planks': 2}, 3),
             rc(2, 'stairs', 4, {'planks': 6}, 2), rc(2, 'slab', 6, {'planks': 3}, 2), rc(2, 'fence', 3, {'planks': 4, 'sticks': 2}, 2),
             rc(2, 'fence_gate', 1, {'planks': 2, 'sticks': 4}, 2), rc(2, 'ladder', 3, {'sticks': 7}, 1), rc(2, 'chest', 1, {'planks': 8}, 1),
             rc(3, 'oak_door', 3, {'planks': 6}, 1), rc(3, 'spruce_door', 3, {'planks': 6}, 1), rc(3, 'trapdoor', 2, {'planks': 6}, 1),
             rc(3, 'sign', 3, {'planks': 6, 'sticks': 1}, 1)],
 'carpenter': [rc(1, 'barrel', 1, {'planks': 6, 'joinery': 2}, 2),
               rc(2, 'red_bed', 1, {'planks': 3, 'wool': 3}, 1), rc(2, 'item_frame', 1, {'sticks': 8, 'leather': 1}, 1),
               rc(3, 'bookshelf', 1, {'planks': 6, 'paper': 3}, 1)],
 # ---------------- mine and metal
 'mine_house': [rc(1, 'cobblestone', 1, per=12), rc(1, 'coal', 1, per=3), rc(1, 'clay_ball', 1, per=1), rc(1, 'sand', 1, per=1),
                rc(1, 'torch', 4, {'fuel': 1, 'sticks': 1}, 1),
                rc(2, 'raw_iron', 1, per=3), rc(2, 'raw_copper', 1, per=2),
                rc(3, 'raw_gold', 1, per=1, p=30), rc(3, 'lapis', 1, per=1, p=15), rc(3, 'redstone', 1, per=1, p=15), rc(3, 'diamond', 1, per=1, p=3)],
 'quarry': [rc(1, 'granite', 1, per=8), rc(1, 'diorite', 1, per=8), rc(1, 'andesite', 1, per=8), rc(1, 'gravel', 1, per=6),
            rc(1, 'sand', 1, per=5), rc(1, 'dirt', 1, per=8), rc(2, 'clay_ball', 1, per=3), rc(2, 'flint', 1, per=3, p=30),
            rc(3, 'deepslate', 1, per=8), rc(3, 'tuff', 1, per=6), rc(3, 'calcite', 1, per=6)],
 'stonemason': [rc(1, 'stone_bricks', 4, {'stone': 4}, 2), rc(1, 'stone_slab', 6, {'stone': 3}, 2),
                rc(2, 'stone_stairs', 4, {'stone': 6}, 2), rc(2, 'smooth_stone', 8, {'stone': 8, 'fuel': 1}, 1), rc(2, 'polished_andesite', 4, {'stone': 4}, 2),
                rc(3, 'sandstone', 1, {'sand': 4}, 3)],
 'smelter': [rc(1, 'iron_ingot', 8, {'ore': 8, 'fuel': 1}, 1), rc(1, 'copper_ingot', 8, {'ore': 8, 'fuel': 1}, 1),
             rc(2, 'iron_nugget', 9, {'metal': 1}, 1), rc(2, 'gold_ingot', 8, {'gold': 8, 'fuel': 1}, 1)],
 'smithy': [rc(1, 'wooden_pickaxe', 1, {'log': 1, 'sticks': 2, 'fuel': 1}, 2), rc(1, 'wooden_axe', 1, {'log': 1, 'sticks': 2, 'fuel': 1}, 2),
            rc(1, 'wooden_shovel', 1, {'log': 1, 'sticks': 2, 'fuel': 1}, 2), rc(1, 'wooden_hoe', 1, {'log': 1, 'sticks': 2, 'fuel': 1}, 2),
            rc(2, 'stone_pickaxe', 1, {'stone': 3, 'sticks': 2, 'fuel': 1}, 2), rc(2, 'stone_axe', 1, {'stone': 3, 'sticks': 2, 'fuel': 1}, 2),
            rc(2, 'stone_shovel', 1, {'stone': 1, 'sticks': 2, 'fuel': 1}, 2), rc(2, 'stone_hoe', 1, {'stone': 2, 'sticks': 2, 'fuel': 1}, 2),
            rc(3, 'iron_pickaxe', 1, {'metal': 3, 'sticks': 2, 'fuel': 1}, 2), rc(3, 'iron_axe', 1, {'metal': 3, 'sticks': 2, 'fuel': 1}, 2),
            rc(3, 'iron_shovel', 1, {'metal': 1, 'sticks': 2, 'fuel': 1}, 2), rc(3, 'iron_hoe', 1, {'metal': 2, 'sticks': 2, 'fuel': 1}, 2),
            rc(3, 'shears', 1, {'metal': 2, 'fuel': 1}, 1), rc(3, 'flint_and_steel', 1, {'metal': 1, 'stone': 1}, 1)],
 'locksmith': [rc(1, 'bucket', 1, {'metal': 3}, 2), rc(1, 'chain', 1, {'metal': 2}, 2), rc(1, 'iron_bars', 16, {'metal': 6}, 1),
               rc(2, 'lantern', 1, {'metal': 1, 'lighting': 1}, 2), rc(2, 'rail', 16, {'metal': 6, 'sticks': 1}, 1),
               rc(3, 'anvil', 1, {'metal': 31}, 1), rc(3, 'compass', 1, {'metal': 4, 'gems': 1}, 1), rc(3, 'minecart', 1, {'metal': 5}, 1)],
 # ---------------- food
 'field': [rc(1, 'wheat', 1, per=10), rc(1, 'potato', 1, per=2), rc(1, 'carrot', 1, per=2),
           rc(2, 'beetroot', 1, per=2), rc(3, 'pumpkin', 1, per=2), rc(3, 'sugar_cane', 1, per=3)],
 'farm': [rc(1, 'potato', 1, per=7), rc(1, 'carrot', 1, per=7), rc(1, 'bone_meal', 1, per=1),
          rc(2, 'melon_slice', 1, per=6), rc(2, 'hay_block', 1, {'grain': 9}, 1), rc(3, 'cocoa_beans', 1, per=2)],
 'orchard': [rc(1, 'apple', 1, per=10), rc(1, 'sweet_berries', 1, per=6), rc(2, 'glow_berries', 1, per=4),
             rc(2, 'cocoa_beans', 1, per=2), rc(3, 'golden_apple', 1, {'food': 1, 'gold': 8}, 1)],
 'flower_garden': [rc(1, 'poppy', 1, per=6), rc(1, 'dandelion', 1, per=6), rc(2, 'cornflower', 1, per=5),
                   rc(2, 'oxeye_daisy', 1, per=5), rc(3, 'allium', 1, per=4), rc(3, 'azure_bluet', 1, per=4)],
 'bakery': [rc(1, 'bread', 1, {'grain': 3}, 3), rc(2, 'cookie', 8, {'grain': 3}, 1), rc(2, 'pumpkin_pie', 1, {'grain': 1, 'food': 2}, 2),
            rc(3, 'cake', 1, {'grain': 3, 'food': 5}, 1), rc(3, 'sugar', 1, {'grain': 1}, 3)],
 'apiary': [rc(1, 'honeycomb', 1, per=2), rc(1, 'honey_bottle', 1, {'glass': 1}, 2)],
 'fish_hut': [rc(1, 'cod', 1, per=8), rc(1, 'salmon', 1, per=6), rc(1, 'fishing_rod', 1, {'sticks': 3, 'wool': 2}, 1),
              rc(2, 'tropical_fish', 1, per=2), rc(2, 'ink_sac', 1, per=1, p=50), rc(2, 'bone', 1, per=1, p=30)],
 'smokehouse': [rc(1, 'cooked_cod', 8, {'food': 16, 'fuel': 1}, 1), rc(1, 'cooked_salmon', 8, {'food': 16, 'fuel': 1}, 1),
                rc(2, 'steak', 8, {'food': 16, 'fuel': 1}, 1), rc(2, 'cooked_porkchop', 8, {'food': 16, 'fuel': 1}, 1)],
 'docks': [rc(1, 'oak_boat', 1, {'planks': 5}, 1, p=30)],
 # ---------------- animals
 'sheep_pen': [rc(1, 'white_wool', 1, per=5), rc(1, 'cooked_mutton', 1, per=1)],
 'cattle_barn': [rc(1, 'milk', 1, per=2), rc(1, 'leather', 1, per=2), rc(1, 'steak', 1, per=1)],
 'pigsty': [rc(1, 'cooked_porkchop', 1, per=2)],
 'coop': [rc(1, 'egg', 1, per=4), rc(1, 'feather', 1, per=1), rc(1, 'cooked_chicken', 1, per=1)],
 'rabbit_hutch': [rc(1, 'rabbit_hide', 1, per=1), rc(1, 'rabbit_stew', 1, {'food': 3}, 1)],
 'stable': [rc(1, 'r:transport', 1, per=1, p=10)],
 # ---------------- crafts
 'weaver': [rc(1, 'string', 4, {'wool': 1}, 3), rc(1, 'carpet', 3, {'wool': 2}, 2),
            rc(2, 'red_wool', 1, {'wool': 1, 'dyes': 1}, 4), rc(2, 'blue_wool', 1, {'wool': 1, 'dyes': 1}, 4), rc(2, 'yellow_wool', 1, {'wool': 1, 'dyes': 1}, 4),
            rc(3, 'banner', 1, {'wool': 6, 'sticks': 1}, 1)],
 'artist': [rc(1, 'red_dye', 1, {'flowers': 1}, 4), rc(1, 'yellow_dye', 1, {'flowers': 1}, 4), rc(1, 'white_dye', 1, {'fertilizer': 1}, 2),
            rc(2, 'blue_dye', 1, {'gems': 1}, 1), rc(2, 'orange_dye', 2, {'dyes': 2}, 2), rc(2, 'pink_dye', 2, {'dyes': 2}, 2), rc(2, 'light_blue_dye', 2, {'dyes': 2}, 2),
            rc(3, 'purple_dye', 2, {'dyes': 2}, 2), rc(3, 'magenta_dye', 2, {'dyes': 2}, 2), rc(3, 'cyan_dye', 2, {'dyes': 2}, 2),
            rc(3, 'painting', 1, {'sticks': 8, 'wool': 1}, 1)],
 'potter': [rc(1, 'brick', 8, {'clay': 8, 'fuel': 1}, 1), rc(1, 'flower_pot', 1, {'ceramics': 3}, 2),
            rc(2, 'bricks', 1, {'ceramics': 4}, 2), rc(2, 'terracotta', 8, {'clay': 32, 'fuel': 1}, 1), rc(3, 'decorated_pot', 1, {'ceramics': 4}, 1)],
 'glassworks': [rc(1, 'glass', 8, {'sand': 8, 'fuel': 1}, 1), rc(1, 'glass_pane', 16, {'glass': 6}, 1),
                rc(2, 'glass_bottle', 3, {'glass': 3}, 2), rc(3, 'stained_glass', 8, {'glass': 8, 'dyes': 1}, 1)],
 'papermill': [rc(1, 'paper', 3, {'grain': 3}, 2), rc(2, 'book', 1, {'paper': 3, 'leather': 1}, 2),
               rc(3, 'writable_book', 1, {'paper': 1, 'leather': 1, 'dyes': 1}, 1)],
 'chandler': [rc(1, 'candle', 1, {'wax': 1, 'wool': 1}, 2), rc(1, 'torch', 4, {'fuel': 1, 'sticks': 1}, 1)],
 'cartographer': [rc(2, 'map', 1, {'paper': 8, 'metalware': 1}, 1)],
}

for nid, n in nodes.items():
    rs = REC.get(nid, [])
    n['recipes'] = rs
    n.pop('sells', None)
    inputs = {y['r'] for x in rs for y in x['use']}
    w = n.get('workers') or 0
    for i, L in enumerate(n['levels']):
        L.pop('make', None)
        if rs: L['use'] = [x for x in L.get('use', []) if x['r'] not in inputs]
        L['workers'] = (i + 1) if (w and rs) else w
    if rs and n.get('icon', '').startswith('b:') is False and not n.get('icon'):
        pass
for nid in REC: assert nid in nodes, nid

ids = {r['id'] for r in tree['catalog']['resources']}
for nid, n in nodes.items():
    for x in n['recipes']:
        for y in x['use']: assert y['r'] in ids, (nid, y)
        assert x['out'].get('r', 'x') in ids | {'x'}, (nid, x)
json.dump(tree, open(R + 'tree.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
out = 'out5/nodes/'
os.makedirs(out, exist_ok=True)
for nid, n in nodes.items(): json.dump(n, open(out + nid + '.json', 'w', encoding='utf-8'), ensure_ascii=False)
json.dump({'items': items}, open('out5/items.json', 'w', encoding='utf-8'), ensure_ascii=False)
print('recipes', sum(len(n['recipes']) for n in nodes.values()), 'buildings with recipes', sum(1 for n in nodes.values() if n['recipes']))
