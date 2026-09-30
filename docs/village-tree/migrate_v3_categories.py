# Categories v3 (2026-09-28): db/ export -> out/ docs to write back.
import json, glob, os, copy

def load(p): return json.load(open(p, encoding='utf-8'))
cat = load('db/meta/catalog.json')
items = load('db/meta/items.json')['items']
nodes = {os.path.basename(f)[:-5]: load(f) for f in glob.glob('db/nodes/*.json')}
for n in nodes.values():
    for k in ('version',):
        n.pop(k, None)

# ---------------------------------------------------------------- catalog
res = cat['resources']
byid = {r['id']: r for r in res}
def after(anchor, new):
    i = next(i for i, r in enumerate(res) if r['id'] == anchor)
    res.insert(i + 1, new)
after('joinery', {'cat': 'wood', 'id': 'fuel', 'name': 'Топливо', 'note': 'Уголь, древесный уголь, дрова (у дров нет своего предмета). Кузница, плавильня, печи.'})
after('metal', {'cat': 'metal', 'id': 'metalware', 'name': 'Металлические изделия', 'note': 'Вёдра, цепи, решётки, рельсы, фонари, наковальни, компасы. Делает слесарня из металла.'})
i = next(i for i, r in enumerate(res) if r['id'] == 'tools')
res[i:i + 1] = [
    {'cat': 'gear', 'id': 'tools1', 'name': 'Инструменты I', 'note': 'Деревянные кирки, топоры, лопаты, мотыги; удочки. Кузница 1 ур.'},
    {'cat': 'gear', 'id': 'tools2', 'name': 'Инструменты II', 'note': 'Каменные инструменты. Кузница 2 ур.'},
    {'cat': 'gear', 'id': 'tools3', 'name': 'Инструменты III', 'note': 'Железные инструменты, ножницы, огниво. Кузница 3 ур.'},
]
res[:] = [r for r in res if r['id'] not in ('coal', 'charcoal')]
byid = {r['id']: r for r in res}
byid['log']['name'] = 'Брёвна'
byid['joinery']['note'] = 'Ступеньки, полублоки, заборы, калитки, двери, люки, лестницы, таблички, сундуки. Делает лесопилка.'
byid['metal']['note'] = 'Железные и медные слитки, самородки. Делает плавильня.'
byid['leather']['name'] = 'Кожа и перья'
byid['leather']['note'] = 'Кожа, шкурки, перья.'
byid['wool']['note'] = 'Шерсть (белая и цветная), нить, ковры, флаги.'
byid['furnishings']['name'] = 'Мебель'
byid['furnishings']['note'] = 'Бочки, верстаки, кровати, книжные полки, рамки, картины. Делает столяр (картины — художник).'
byid['lighting']['note'] = 'Факелы, свечи. Фонари — в металлических изделиях.'
byid['ceramics']['note'] = 'Кирпич, горшки, терракота, расписные горшки.'
byid['transport']['note'] = 'Лодки. Остальное — позже, вместе с животными.'
byid['weapons']['note'] = 'Мечи (дерево, камень, железо), луки, арбалеты, стрелы, щиты.'
byid['armor']['note'] = 'Кожаная, кольчужная, железная.'
byid['tools1']  # exists
cat['version'] = 3

# ---------------------------------------------------------------- items
GONE = {'brush', 'spyglass', 'glazed_terracotta', 'scaffolding', 'saddle', 'lead', 'name_tag', 'bell', 'horse_armor'}
MOVE = {'bucket': 'metalware', 'chain': 'metalware', 'iron_bars': 'metalware', 'rail': 'metalware', 'anvil': 'metalware',
        'compass': 'metalware', 'minecart': 'metalware', 'lantern': 'metalware', 'chest': 'joinery', 'carpet': 'wool',
        'banner': 'wool', 'feather': 'leather', 'fishing_rod': 'tools1', 'shears': 'tools3', 'flint_and_steel': 'tools3'}
out_items = []
for it in items:
    iid = it['id']
    if iid in GONE: continue
    if iid.startswith(('copper_', 'golden_', 'diamond_')) and iid not in ('copper_ingot', 'golden_apple'): continue
    if iid in MOVE: it['res'] = MOVE[iid]
    elif it['res'] in ('coal', 'charcoal'): it['res'] = 'fuel'
    elif it['res'] == 'tools':
        it['res'] = 'tools1' if iid.startswith('wooden_') else 'tools2' if iid.startswith('stone_') else 'tools3'
    out_items.append(it)
names = {x['id'] for x in out_items}

# ---------------------------------------------------------------- nodes: generic pass
def merge(rows):
    out = []
    for x in rows:
        same = next((y for y in out if y['r'] == x['r'] and y.get('t') == x.get('t') and y.get('p') == x.get('p')), None)
        if same: same['n'] = round(same['n'] + x['n'], 3)
        else: out.append(x)
    return out

for nid, n in nodes.items():
    for li, L in enumerate(n['levels']):
        for k in ('make', 'use', 'cost'):
            rows = []
            for x in L.get(k, []):
                x = dict(x)
                if k == 'use' and x['r'] == 'food' and x.get('t') == 'мастер': continue
                if x['r'] in ('coal', 'charcoal'): x['r'] = 'fuel'
                if x['r'] == 'tools':
                    if k == 'make' and nid != 'smithy': continue
                    x['r'] = 'tools%d' % (li + 1)
                    if k == 'use' and not x.get('t'): x['t'] = 'износ'
                rows.append(x)
            L[k] = merge(rows)
    for x in n.get('unlock', []):
        if x['r'] in ('coal', 'charcoal'): x['r'] = 'fuel'
    n['sells'] = [s for s in n.get('sells', []) if s['item'] in names]

def lv(n, i): return n['levels'][i]
def drop(n, k, r, levels=(0, 1, 2)):
    for i in levels: lv(n, i)[k] = [x for x in lv(n, i)[k] if x['r'] != r]
def sells_drop(n, *ids): n['sells'] = [s for s in n['sells'] if s['item'] not in ids]

# ---------------------------------------------------------------- smithy: one of the first buildings
S = nodes['smithy']
S.update(parent='', order=10, opens='С начала, одна из первых построек (рядом со складом)',
         unlock=[{'r': 'log', 'n': 30}, {'r': 'stone', 'n': 10}],
         note='Пока кузницы нет, у жителей нет инструментов: работают руками, медленно.')
S['levels'] = [
    {'cost': [{'r': 'log', 'n': 40}, {'r': 'stone', 'n': 15}],
     'use': [{'r': 'log', 'n': 4, 't': 'тешут из бревна'}, {'r': 'sticks', 'n': 2}, {'r': 'fuel', 'n': 1},
             {'r': 'wool', 'n': 0.2, 't': 'добавка: нить → удочки'}],
     'make': [{'r': 'tools1', 'n': 1.5}], 'note': 'Деревянные инструменты.'},
    {'cost': [{'r': 'log', 'n': 20}, {'r': 'stone', 'n': 30}],
     'use': [{'r': 'log', 'n': 3}, {'r': 'sticks', 'n': 3}, {'r': 'stone', 'n': 4, 't': 'булыжник'}, {'r': 'fuel', 'n': 1.5}],
     'make': [{'r': 'tools2', 'n': 2}], 'note': 'Каменные инструменты.'},
    {'cost': [{'r': 'stone', 'n': 40}, {'r': 'metal', 'n': 10}],
     'use': [{'r': 'log', 'n': 3}, {'r': 'sticks', 'n': 3}, {'r': 'stone', 'n': 3}, {'r': 'metal', 'n': 3, 't': 'железо'}, {'r': 'fuel', 'n': 2}],
     'make': [{'r': 'tools3', 'n': 2}], 'note': 'Железные инструменты, ножницы, огниво.'},
]
S['sells'] = ([{'item': t, 'lvl': 1, 'n': 1} for t in ('wooden_pickaxe', 'wooden_axe', 'wooden_shovel', 'wooden_hoe', 'fishing_rod')]
              + [{'item': t, 'lvl': 2, 'n': 1} for t in ('stone_pickaxe', 'stone_axe', 'stone_shovel', 'stone_hoe')]
              + [{'item': t, 'lvl': 3, 'n': 1} for t in ('iron_pickaxe', 'iron_axe', 'iron_shovel', 'iron_hoe', 'shears', 'flint_and_steel')])

# ---------------------------------------------------------------- locksmith (new): metal -> metalware
nodes['locksmith'] = {
    'name': 'Слесарня', 'branch': 'mine', 'parent': 'smelter', 'icon': 'b:locksmith', 'order': 16,
    'opens': 'Плавильня 2 ур.', 'beds': 1, 'workers': 1,
    'note': 'Плавильня делает из руды металл, слесарня — из металла изделия. Концепт под вопросом.',
    'unlock': [{'r': 'stone', 'n': 40}, {'r': 'metal', 'n': 10}],
    'levels': [
        {'cost': [{'r': 'log', 'n': 30}, {'r': 'stone', 'n': 30}], 'use': [{'r': 'metal', 'n': 2}, {'r': 'fuel', 'n': 1}],
         'make': [{'r': 'metalware', 'n': 1.5}], 'note': 'Вёдра, цепи, решётки.'},
        {'cost': [{'r': 'planks', 'n': 20}, {'r': 'cut_stone', 'n': 20}],
         'use': [{'r': 'metal', 'n': 3}, {'r': 'fuel', 'n': 1.5}, {'r': 'lighting', 'n': 0.5, 't': 'добавка: факелы → фонари'}],
         'make': [{'r': 'metalware', 'n': 2.5}], 'note': 'Фонари, рельсы.'},
        {'cost': [{'r': 'metal', 'n': 10}],
         'use': [{'r': 'metal', 'n': 4}, {'r': 'fuel', 'n': 2}, {'r': 'gems', 'n': 0.1, 't': 'добавка: редстоун → компасы'}],
         'make': [{'r': 'metalware', 'n': 3.5}], 'note': 'Наковальни, компасы, вагонетки.'},
    ],
    'sells': [{'item': 'bucket', 'lvl': 1, 'n': 1}, {'item': 'chain', 'lvl': 1, 'n': 2}, {'item': 'iron_bars', 'lvl': 1, 'n': 2},
              {'item': 'lantern', 'lvl': 2, 'n': 2}, {'item': 'rail', 'lvl': 2, 'n': 4},
              {'item': 'anvil', 'lvl': 3, 'n': 1}, {'item': 'compass', 'lvl': 3, 'n': 1}, {'item': 'minecart', 'lvl': 3, 'n': 1}],
}

# ---------------------------------------------------------------- the rest
W = nodes['wood_hut']
for i, k in enumerate((1, 1.5, 2)): lv(W, i)['make'].insert(2, {'r': 'fuel', 'n': k, 't': 'дрова'})
lv(W, 0)['note'] = 'Рубит у дома, сажает рощу. Собирает ветки (палки) и дрова.'
sells_drop(W, 'wooden_axe', 'stone_axe', 'iron_axe')

M = nodes['mine_house']
M['note'] = (M.get('note') or '') + ' До кузницы камни собирает житель руками, при прополке травы (меньше всех).'

drop(nodes['smelter'], 'make', 'glass')

A = nodes['armorer']
drop(A, 'use', 'gold'); drop(A, 'use', 'gems')

C = nodes['carpenter']
for i, k in enumerate((2, 3, 4)):
    L = lv(C, i)
    L['make'] = [{'r': 'furnishings', 'n': k}]
    for x in L['use']:
        if x['r'] == 'wool': x['t'] = 'добавка: шерсть → кровати'
        if x['r'] == 'paper': x['t'] = 'добавка: книги → книжные полки'
lv(C, 0)['note'] = 'Бочки, верстаки.'
lv(C, 1)['use'].append({'r': 'leather', 'n': 0.2, 't': 'добавка: кожа → рамки'})
lv(C, 1)['note'] = 'Кровати, рамки.'
lv(C, 2)['note'] = 'Книжные полки, кафедры.'
sells_drop(C, 'chest', 'oak_door', 'oak_boat')

SW = nodes['sawmill']
SW['sells'].append({'item': 'chest', 'lvl': 2, 'n': 2})
lv(SW, 1)['note'] = 'Столярка: ступеньки, полублоки, заборы, сундуки.'

WV = nodes['weaver']
for i, k in enumerate((2, 2.5, 3.5)):
    L = lv(WV, i)
    L['make'] = [{'r': 'wool', 'n': k, 't': 'нить, ковры, флаги'}]
    for x in L['use']:
        if x['r'] == 'dyes': x['t'] = 'добавка: краски → цветная шерсть, ковры'
drop(WV, 'use', 'planks')
sells_drop(WV, 'red_bed')

T = nodes['tannery']
for i, k in enumerate((0.5, 0.8, 1)): lv(T, i)['make'] = [{'r': 'armor', 'n': k, 't': 'кожаная броня'}]
drop(T, 'use', 'metal'); drop(T, 'use', 'sticks')
sells_drop(T, 'item_frame')
nodes['carpenter']['sells'].append({'item': 'item_frame', 'lvl': 2, 'n': 2}) if not any(s['item'] == 'item_frame' for s in C['sells']) else None

CH = nodes['chandler']
drop(CH, 'use', 'metal', (2,))
sells_drop(CH, 'lantern')

drop(nodes['fish_hut'], 'make', 'transport')
drop(nodes['fletcher'], 'make', 'tools'); sells_drop(nodes['fletcher'], 'fishing_rod')
drop(nodes['pigsty'], 'make', 'transport')
drop(nodes['stable'], 'use', 'armor')
for nid in ('cattle_barn', 'cartographer'):
    for L in nodes[nid]['levels']:
        for x in L['use']:
            if x['r'] == 'metal': x['r'] = 'metalware'
sells_drop(nodes['cartographer'], 'compass')

# ---------------------------------------------------------------- checks + write
ids = {r['id'] for r in res}
for nid, n in nodes.items():
    for L in n['levels']:
        for k in ('make', 'use', 'cost'):
            for x in L[k]: assert x['r'] in ids, (nid, x)
    for x in n.get('unlock', []): assert x['r'] in ids, (nid, x)
    for s in n['sells']: assert s['item'] in names, (nid, s)
    assert not n['parent'] or n['parent'] in nodes, nid
for it in out_items: assert it['res'] in ids, it
os.makedirs('out/nodes', exist_ok=True)
json.dump(cat, open('out/catalog.json', 'w', encoding='utf-8'), ensure_ascii=False)
json.dump({'items': out_items}, open('out/items.json', 'w', encoding='utf-8'), ensure_ascii=False)
for nid, n in nodes.items(): json.dump(n, open('out/nodes/%s.json' % nid, 'w', encoding='utf-8'), ensure_ascii=False)
print('resources', len(res), 'items', len(items), '->', len(out_items), 'nodes', len(nodes))
