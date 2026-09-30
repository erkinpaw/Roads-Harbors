# v4 (2026-09-28): no charcoal pit, armorer, fletcher, tannery; no weapons/armor; smithy without thread;
# fishing joins the food branch; whole numbers everywhere (under 1 a day -> 1 with a chance).
import json, glob, os, math

def load(p): return json.load(open(p, encoding='utf-8'))
cat = load('db2/meta/catalog.json'); cat.pop('version', None); cat['version'] = 4
branches = load('db2/meta/branches.json')
items = load('db2/meta/items.json')['items']
nodes = {os.path.basename(f)[:-5]: load(f) for f in glob.glob('db2/nodes/*.json')}
before = json.loads(json.dumps(nodes))

GONE_NODES = ('charcoal_pit', 'armorer', 'fletcher', 'tannery')
for nid in GONE_NODES: del nodes[nid]
GONE_RES = ('weapons', 'armor')
cat['resources'] = [r for r in cat['resources'] if r['id'] not in GONE_RES]
items = [i for i in items if i['res'] not in GONE_RES and i['id'] != 'charcoal']
byid = {r['id']: r for r in cat['resources']}
byid['fuel']['note'] = 'Дрова (у дровосека) и уголь (из шахты). Кузница, плавильня, печи.'
byid['leather']['note'] = 'Кожа, шкурки, перья: коровник, крольчатник, курятник.'
names = {i['id'] for i in items}

# fishing is food
branches['branches'] = [b for b in branches['branches'] if b['id'] != 'fish']
for b in branches['branches']:
    if b['id'] == 'farm': b['name'] = 'Еда'
for n in nodes.values():
    if n['branch'] == 'fish': n['branch'] = 'farm'

# smithy: logs and sticks only (no thread, no fishing rods)
S = nodes['smithy']
S['levels'][0]['use'] = [x for x in S['levels'][0]['use'] if x['r'] != 'wool']
S['sells'] = [s for s in S['sells'] if s['item'] != 'fishing_rod']

# whole numbers: n >= 1 rounds half up; n < 1 becomes 1 with a chance of n (times any chance it had)
def whole(x):
    n = x['n']
    if n <= 0: return x
    if n < 1:
        p = x.get('p', 100) * n
        x['n'] = 1
        x['p'] = max(1, int(math.floor(p + 0.5)))
    else:
        x['n'] = int(math.floor(n + 0.5))
        if 'p' in x: x['p'] = int(math.floor(x['p'] + 0.5))
    if x.get('p') is not None and x['p'] >= 100: del x['p']
    return x
for n in nodes.values():
    for L in n['levels']:
        for k in ('make', 'use', 'cost'): L[k] = [whole(x) for x in L.get(k, [])]
    n['unlock'] = [whole(x) for x in n.get('unlock', [])]
    n['sells'] = [s for s in n.get('sells', []) if s['item'] in names]
for i in items:
    i['units'] = max(1, int(math.floor(i['units'] + 0.5)))

# checks
ids = {r['id'] for r in cat['resources']}
for nid, n in nodes.items():
    assert not n['parent'] or n['parent'] in nodes, (nid, n['parent'])
    assert n['branch'] in {b['id'] for b in branches['branches']}, nid
    for L in n['levels']:
        for k in ('make', 'use', 'cost'):
            for x in L[k]: assert x['r'] in ids and float(x['n']).is_integer(), (nid, x)
    for x in n['unlock']: assert x['r'] in ids, (nid, x)
for i in items: assert i['res'] in ids, i

os.makedirs('out4/nodes', exist_ok=True)
json.dump(cat, open('out4/catalog.json', 'w', encoding='utf-8'), ensure_ascii=False)
json.dump({'items': items}, open('out4/items.json', 'w', encoding='utf-8'), ensure_ascii=False)
json.dump(branches, open('out4/branches.json', 'w', encoding='utf-8'), ensure_ascii=False)
changed = [nid for nid, n in nodes.items() if n != before[nid]]
for nid in changed: json.dump(nodes[nid], open('out4/nodes/%s.json' % nid, 'w', encoding='utf-8'), ensure_ascii=False)
json.dump(changed, open('out4/changed.json', 'w'))
print('resources', len(ids), 'items', len(items), 'nodes', len(nodes), 'changed', len(changed))
