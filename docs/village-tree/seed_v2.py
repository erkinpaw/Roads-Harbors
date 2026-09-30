# -*- coding: utf-8 -*-
"""
The village tree, version 2: the village's economy in ~30 categories (what buildings make and use up), and the
concrete Minecraft items only at the counters — each resident sells what their trade works with, an item taking
so many units of a category from the store. Writes seed2/*.json (for the editor's store) and icons2.js.
"""
import base64
import io
import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "seed2")
os.makedirs(os.path.join(OUT, "nodes"), exist_ok=True)
TEX = os.path.join(HERE, "mctex", "assets", "minecraft", "textures")

# ---------------------------------------------------------------- the categories the village counts
GROUPS = [("food", "Еда и корм"), ("wood", "Дерево"), ("stone", "Камень и земля"), ("metal", "Руда и металл"),
          ("goods", "Ремесло"), ("gear", "Снаряжение"), ("home", "Быт")]
CATS = [
    # id, name, group, icon texture, note
    ("food", "Еда", "food", "item/bread", "Вся еда: хлеб, мясо, рыба, овощи. Житель съедает 6 в день. Разнообразие — позже."),
    ("grain", "Зерно и корм", "food", "item/wheat", "Пшеница, семена, сено: корм скоту, мука для пекарни."),
    ("log", "Древесина", "wood", "block/oak_log", "Брёвна любой породы."),
    ("planks", "Доски", "wood", "block/oak_planks", "Сырьё для стройки и столярки."),
    ("sticks", "Палки", "wood", "item/stick", "Рукояти инструментов, факелы, заборы."),
    ("joinery", "Столярка", "wood", "item/oak_door", "Двери, ступеньки, полублоки, заборы, люки, лестницы, таблички."),
    ("charcoal", "Древесный уголь", "wood", "item/charcoal", "Топливо, как уголь, но из брёвен."),
    ("stone", "Камень", "stone", "block/cobblestone", "Булыжник, камень, гранит, диорит, андезит, сланец."),
    ("cut_stone", "Тёсаный камень", "stone", "block/stone_bricks", "Каменные кирпичи, ступени, плиты, мостовая."),
    ("sand", "Песок", "stone", "block/sand", "Для стекла."),
    ("clay", "Глина", "stone", "item/clay_ball", "Для кирпича и гончарки."),
    ("coal", "Уголь", "metal", "item/coal", "Топливо плавильни и кузни, свет."),
    ("ore", "Руда", "metal", "item/raw_iron", "Железная и медная руда."),
    ("metal", "Металл", "metal", "item/iron_ingot", "Железные и медные слитки."),
    ("gold", "Золото", "metal", "item/gold_ingot", "Руда и слитки золота."),
    ("gems", "Самоцветы", "metal", "item/emerald", "Алмазы, изумруды, лазурит, редстоун, аметист."),
    ("tools", "Инструменты", "gear", "item/iron_pickaxe", "Кирки, топоры, лопаты, мотыги, удочки, ножницы. Качество — по кузнице."),
    ("weapons", "Оружие", "gear", "item/iron_sword", "Мечи, луки, стрелы, щиты."),
    ("armor", "Броня", "gear", "item/iron_chestplate", "Кожаная, кольчужная, железная, золотая, алмазная."),
    ("wool", "Ткани", "goods", "block/white_wool", "Шерсть и нить."),
    ("leather", "Кожа", "goods", "item/leather", "Кожа, шкурки."),
    ("dyes", "Красители", "goods", "item/red_dye", "Все краски; какие цвета есть — зависит от художника и сырья."),
    ("flowers", "Цветы", "goods", "block/poppy", "Для красителей, пасеки, крылец."),
    ("wax", "Воск и мёд", "goods", "item/honeycomb", "Соты: свечи; мёд идёт в еду."),
    ("glass", "Стекло", "goods", "block/glass", "Стекло, панели, склянки."),
    ("ceramics", "Керамика", "goods", "item/brick", "Кирпич, горшки, терракота."),
    ("paper", "Бумага и книги", "goods", "item/book", "Бумага, книги, карты."),
    ("lighting", "Освещение", "home", "item/lantern", "Факелы, свечи, фонари."),
    ("furnishings", "Мебель и быт", "home", "item/painting", "Кровати, ковры, картины, сундуки, бочки."),
    ("fertilizer", "Удобрения", "food", "item/bone_meal", "Костная мука и компост: урожай выше."),
    ("transport", "Транспорт", "home", "item/oak_boat", "Лодки, лошади, сёдла, вагонетки."),
]

# ---------------------------------------------------------------- the concrete items (the counters' goods)
ITEMS = []


def item(iid, name, cat, units, tex):
    ITEMS.append({"id": iid, "name": name, "res": cat, "units": units, "tex": tex})


for tier, tname in [("wooden", "Деревянн"), ("stone", "Каменн"), ("copper", "Медн"), ("iron", "Железн"), ("golden", "Золот"), ("diamond", "Алмазн")]:
    end = {"wooden": ("ая", "ый", "ая", "ая", "ый"), "stone": ("ая", "ый", "ая", "ая", "ый"), "copper": ("ая", "ый", "ая", "ая", "ый"),
           "iron": ("ая", "ый", "ая", "ая", "ый"), "golden": ("ая", "ой", "ая", "ая", "ой"), "diamond": ("ая", "ый", "ая", "ая", "ый")}[tier]
    item(f"{tier}_pickaxe", f"{tname}{end[0]} кирка", "tools", 3, f"item/{tier}_pickaxe")
    item(f"{tier}_axe", f"{tname}{end[1]} топор", "tools", 3, f"item/{tier}_axe")
    item(f"{tier}_shovel", f"{tname}{end[2]} лопата", "tools", 1, f"item/{tier}_shovel")
    item(f"{tier}_hoe", f"{tname}{end[3]} мотыга", "tools", 2, f"item/{tier}_hoe")
    item(f"{tier}_sword", f"{tname}{end[4]} меч", "weapons", 2, f"item/{tier}_sword")
item("fishing_rod", "Удочка", "tools", 1, "item/fishing_rod")
item("shears", "Ножницы", "tools", 2, "item/shears")
item("flint_and_steel", "Огниво", "tools", 1, "item/flint_and_steel")
item("brush", "Кисть", "tools", 1, "item/brush")
item("bucket", "Ведро", "metal", 3, "item/bucket")
item("bow", "Лук", "weapons", 2, "item/bow")
item("crossbow", "Арбалет", "weapons", 4, "item/crossbow_standby")
item("arrow", "Стрелы ×8", "weapons", 1, "item/arrow")
item("shield", "Щит", "weapons", 3, "block/oak_planks")
for m, mn, u in [("leather", "Кожан", 1), ("chainmail", "Кольчужн", 2), ("iron", "Железн", 3), ("golden", "Золот", 3), ("diamond", "Алмазн", 5)]:
    item(f"{m}_helmet", f"{mn}{'ый' if m != 'golden' else 'ой'} шлем", "armor", u * 5, f"item/{m}_helmet")
    item(f"{m}_chestplate", f"{mn}{'ый' if m != 'golden' else 'ой'} нагрудник", "armor", u * 8, f"item/{m}_chestplate")
    item(f"{m}_leggings", f"{mn}{'ые' if m != 'golden' else 'ые'} поножи", "armor", u * 7, f"item/{m}_leggings")
    item(f"{m}_boots", f"{mn}{'ые' if m != 'golden' else 'ые'} ботинки", "armor", u * 4, f"item/{m}_boots")
item("horse_armor", "Конская броня", "armor", 12, "item/iron_horse_armor")
# food
for iid, name, u, tex in [("bread", "Хлеб", 5, "item/bread"), ("baked_potato", "Печёный картофель", 5, "item/baked_potato"),
                          ("carrot", "Морковь", 3, "item/carrot"), ("potato", "Картофель", 1, "item/potato"), ("beetroot", "Свёкла", 1, "item/beetroot"),
                          ("apple", "Яблоко", 4, "item/apple"), ("golden_apple", "Золотое яблоко", 12, "item/golden_apple"),
                          ("melon_slice", "Ломтик арбуза", 2, "item/melon_slice"), ("pumpkin_pie", "Тыквенный пирог", 8, "item/pumpkin_pie"),
                          ("sweet_berries", "Сладкие ягоды", 2, "item/sweet_berries"), ("glow_berries", "Светящиеся ягоды", 2, "item/glow_berries"),
                          ("cake", "Торт", 14, "item/cake"), ("cookie", "Печенье", 2, "item/cookie"), ("honey_bottle", "Мёд", 6, "item/honey_bottle"),
                          ("cooked_cod", "Жареная треска", 5, "item/cooked_cod"), ("cooked_salmon", "Жареный лосось", 6, "item/cooked_salmon"),
                          ("cod", "Треска", 2, "item/cod"), ("salmon", "Лосось", 2, "item/salmon"), ("tropical_fish", "Тропическая рыба", 1, "item/tropical_fish"),
                          ("steak", "Стейк", 8, "item/cooked_beef"), ("cooked_porkchop", "Жареная свинина", 8, "item/cooked_porkchop"),
                          ("cooked_mutton", "Жареная баранина", 6, "item/cooked_mutton"), ("cooked_chicken", "Жареная курица", 6, "item/cooked_chicken"),
                          ("rabbit_stew", "Тушёный кролик", 10, "item/rabbit_stew"), ("mushroom_stew", "Грибной суп", 6, "item/mushroom_stew"),
                          ("beetroot_soup", "Свекольный суп", 6, "item/beetroot_soup"), ("dried_kelp", "Сушёная ламинария", 1, "item/dried_kelp"),
                          ("egg", "Яйцо", 1, "item/egg"), ("milk", "Молоко (ведро)", 4, "item/milk_bucket"), ("sugar", "Сахар", 1, "item/sugar")]:
    item(iid, name, "food", u, tex)
for iid, name, u, tex in [("wheat", "Пшеница", 1, "item/wheat"), ("wheat_seeds", "Семена пшеницы", 1, "item/wheat_seeds"),
                          ("beetroot_seeds", "Семена свёклы", 1, "item/beetroot_seeds"), ("pumpkin_seeds", "Семена тыквы", 1, "item/pumpkin_seeds"),
                          ("melon_seeds", "Семена арбуза", 1, "item/melon_seeds"), ("hay_block", "Тюк сена", 9, "block/hay_block_side"),
                          ("sugar_cane", "Сахарный тростник", 1, "item/sugar_cane"), ("cocoa_beans", "Какао-бобы", 1, "item/cocoa_beans"),
                          ("pumpkin", "Тыква", 2, "block/pumpkin_side")]:
    item(iid, name, "grain", u, tex)
# wood
for kind, kname in [("oak", "Дубов"), ("spruce", "Елов"), ("birch", "Берёзов"), ("jungle", "Тропическ"), ("acacia", "Акациев"),
                    ("dark_oak", "Тёмно-дубов"), ("mangrove", "Мангров"), ("cherry", "Вишнёв"), ("pale_oak", "Бледно-дубов")]:
    item(f"{kind}_log", f"{kname}ое бревно", "log", 1, f"block/{kind}_log")
    item(f"{kind}_planks", f"{kname}ые доски", "planks", 1, f"block/{kind}_planks")
    item(f"{kind}_door", f"{kname}ая дверь", "joinery", 1, f"item/{kind}_door")
item("bamboo", "Бамбук", "log", 1, "item/bamboo")
item("stick", "Палка ×4", "sticks", 1, "item/stick")
for iid, name, u, tex in [("stairs", "Деревянные ступеньки ×2", 1, "block/oak_planks"), ("slab", "Деревянные полублоки ×4", 1, "block/oak_planks"),
                          ("fence", "Забор ×2", 1, "block/oak_planks"), ("fence_gate", "Калитка", 1, "block/oak_planks"),
                          ("trapdoor", "Люк", 1, "block/oak_trapdoor"), ("ladder", "Лестница ×3", 1, "block/ladder"), ("sign", "Табличка", 1, "item/oak_sign"),
                          ("scaffolding", "Строительные леса ×6", 1, "block/scaffolding_side")]:
    item(iid, name, "joinery", u, tex)
item("charcoal", "Древесный уголь", "charcoal", 1, "item/charcoal")
item("sapling", "Саженец", "log", 1, "block/oak_sapling")
item("leaves", "Листва", "log", 1, "block/oak_leaves")
# stone and earth
for iid, name, cat, u, tex in [("cobblestone", "Булыжник", "stone", 1, "block/cobblestone"), ("stone", "Камень", "stone", 1, "block/stone"),
                               ("granite", "Гранит", "stone", 1, "block/granite"), ("diorite", "Диорит", "stone", 1, "block/diorite"),
                               ("andesite", "Андезит", "stone", 1, "block/andesite"), ("deepslate", "Глубинный сланец", "stone", 1, "block/deepslate"),
                               ("tuff", "Туф", "stone", 1, "block/tuff"), ("calcite", "Кальцит", "stone", 1, "block/calcite"),
                               ("gravel", "Гравий", "stone", 1, "block/gravel"), ("flint", "Кремень", "stone", 1, "item/flint"),
                               ("dirt", "Земля", "stone", 1, "block/dirt"), ("stone_bricks", "Каменные кирпичи", "cut_stone", 1, "block/stone_bricks"),
                               ("smooth_stone", "Гладкий камень", "cut_stone", 1, "block/smooth_stone"),
                               ("stone_stairs", "Каменные ступени ×2", "cut_stone", 1, "block/stone"), ("stone_slab", "Каменные плиты ×4", "cut_stone", 1, "block/smooth_stone_slab_side"),
                               ("polished_andesite", "Мостовая", "cut_stone", 1, "block/polished_andesite"), ("sandstone", "Песчаник", "cut_stone", 1, "block/sandstone"),
                               ("sand", "Песок", "sand", 1, "block/sand"), ("red_sand", "Красный песок", "sand", 1, "block/red_sand"),
                               ("clay_ball", "Глина", "clay", 1, "item/clay_ball"), ("obsidian", "Обсидиан", "stone", 4, "block/obsidian")]:
    item(iid, name, cat, u, tex)
# metal
for iid, name, cat, u, tex in [("coal", "Уголь", "coal", 1, "item/coal"), ("raw_iron", "Железная руда", "ore", 1, "item/raw_iron"),
                               ("raw_copper", "Медная руда", "ore", 1, "item/raw_copper"), ("iron_ingot", "Железный слиток", "metal", 1, "item/iron_ingot"),
                               ("copper_ingot", "Медный слиток", "metal", 1, "item/copper_ingot"), ("iron_nugget", "Железные самородки ×9", "metal", 1, "item/iron_nugget"),
                               ("chain", "Цепь", "metal", 1, "item/iron_chain;item/chain"), ("iron_bars", "Решётка ×4", "metal", 1, "block/iron_bars"),
                               ("rail", "Рельсы ×8", "metal", 3, "block/rail"), ("anvil", "Наковальня", "metal", 31, "block/anvil"),
                               ("raw_gold", "Золотая руда", "gold", 1, "item/raw_gold"), ("gold_ingot", "Золотой слиток", "gold", 1, "item/gold_ingot"),
                               ("diamond", "Алмаз", "gems", 8, "item/diamond"), ("emerald", "Изумруд", "gems", 6, "item/emerald"),
                               ("lapis", "Лазурит", "gems", 1, "item/lapis_lazuli"), ("redstone", "Редстоун", "gems", 1, "item/redstone"),
                               ("amethyst_shard", "Осколок аметиста", "gems", 2, "item/amethyst_shard")]:
    item(iid, name, cat, u, tex)
# crafts
COLORS = [("white", "Бел"), ("light_gray", "Светло-сер"), ("gray", "Сер"), ("black", "Чёрн"), ("brown", "Коричнев"), ("red", "Красн"),
          ("orange", "Оранжев"), ("yellow", "Жёлт"), ("lime", "Лаймов"), ("green", "Зелён"), ("cyan", "Бирюзов"), ("light_blue", "Голуб"),
          ("blue", "Син"), ("purple", "Фиолетов"), ("magenta", "Сиренев"), ("pink", "Розов")]
for c, cn in COLORS:
    end = "яя" if c in ("blue",) else "ая"
    item(f"{c}_dye", f"{cn}{end} краска", "dyes", 1, f"item/{c}_dye")
for c, cn in COLORS:
    end = "яя" if c in ("blue",) else "ая"
    item(f"{c}_wool", f"{cn}{end} шерсть", "wool", 1, f"block/{c}_wool")
for iid, name, cat, u, tex in [("string", "Нить ×4", "wool", 1, "item/string"), ("carpet", "Ковёр ×3", "furnishings", 1, "block/red_wool"),
                               ("banner", "Флаг", "furnishings", 3, "block/white_wool"), ("leather", "Кожа", "leather", 1, "item/leather"),
                               ("rabbit_hide", "Кроличья шкурка", "leather", 1, "item/rabbit_hide"), ("book", "Книга", "paper", 3, "item/book"),
                               ("paper", "Бумага ×3", "paper", 1, "item/paper"), ("map", "Карта", "paper", 4, "item/map"),
                               ("writable_book", "Книга с пером", "paper", 4, "item/writable_book"), ("compass", "Компас", "metal", 5, "item/compass_00;item/compass"),
                               ("clock", "Часы", "gold", 5, "item/clock_00;item/clock"), ("spyglass", "Подзорная труба", "metal", 4, "item/spyglass"),
                               ("glass", "Стекло", "glass", 1, "block/glass"), ("glass_pane", "Стеклянные панели ×16", "glass", 6, "block/glass"),
                               ("glass_bottle", "Склянки ×3", "glass", 1, "item/glass_bottle"), ("stained_glass", "Витражное стекло", "glass", 1, "block/light_blue_stained_glass"),
                               ("brick", "Кирпич", "ceramics", 1, "item/brick"), ("bricks", "Кирпичный блок", "ceramics", 4, "block/bricks"),
                               ("flower_pot", "Цветочный горшок", "ceramics", 3, "item/flower_pot"), ("terracotta", "Терракота", "ceramics", 1, "block/terracotta"),
                               ("glazed_terracotta", "Глазурованная терракота", "ceramics", 2, "block/blue_glazed_terracotta"),
                               ("decorated_pot", "Расписной горшок", "ceramics", 4, "item/decorated_pot;item/flower_pot"),
                               ("honeycomb", "Соты", "wax", 1, "item/honeycomb"), ("candle", "Свеча", "lighting", 1, "item/candle"),
                               ("torch", "Факелы ×4", "lighting", 1, "block/torch"), ("lantern", "Фонарь", "lighting", 3, "item/lantern"),
                               ("poppy", "Мак", "flowers", 1, "block/poppy"), ("dandelion", "Одуванчик", "flowers", 1, "block/dandelion"),
                               ("cornflower", "Василёк", "flowers", 1, "block/cornflower"), ("oxeye_daisy", "Ромашка", "flowers", 1, "block/oxeye_daisy"),
                               ("allium", "Лук-слизун", "flowers", 1, "block/allium"), ("azure_bluet", "Хоустония", "flowers", 1, "block/azure_bluet"),
                               ("bone_meal", "Костная мука ×3", "fertilizer", 1, "item/bone_meal"), ("bone", "Кость", "fertilizer", 3, "item/bone"),
                               ("red_bed", "Кровать", "furnishings", 4, "item/red_bed;block/red_wool"), ("painting", "Картина", "furnishings", 3, "item/painting"),
                               ("item_frame", "Рамка", "furnishings", 2, "item/item_frame"), ("chest", "Сундук", "furnishings", 2, "block/oak_planks"),
                               ("barrel", "Бочка", "furnishings", 2, "block/barrel_side"), ("bookshelf", "Книжная полка", "furnishings", 5, "block/bookshelf"),
                               ("bell", "Колокол", "furnishings", 8, "item/bell"), ("lead", "Поводок", "transport", 1, "item/lead"),
                               ("saddle", "Седло", "transport", 6, "item/saddle"), ("oak_boat", "Лодка", "transport", 5, "item/oak_boat"),
                               ("minecart", "Вагонетка", "transport", 5, "item/minecart"), ("name_tag", "Бирка", "transport", 4, "item/name_tag"),
                               ("ink_sac", "Чернильный мешок", "dyes", 1, "item/ink_sac"), ("feather", "Перо", "wool", 1, "item/feather")]:
    item(iid, name, cat, u, tex)

# ---------------------------------------------------------------- buildings
BR = [("center", "Центр", "#C9A55A"), ("home", "Жильё", "#8A5A30"), ("store", "Склады", "#7A5A3A"), ("trade", "Торговля и знания", "#3A9A6A"),
      ("fish", "Рыбалка", "#4A7AA0"), ("wood", "Лес", "#5E8A3C"), ("mine", "Шахта и металл", "#707070"), ("craft", "Ремесло", "#9A4A6A"),
      ("animals", "Животные", "#A0703A"), ("farm", "Поля", "#B89A30")]


def r(res, n, t=""):
    d = {"r": res, "n": n}
    if t:
        d["t"] = t
    return d


def lvl(make=(), use=(), cost=(), note=""):
    return {"make": list(make), "use": list(use), "cost": list(cost), "note": note}


def sell(iid, lv=1, n=2):
    return {"item": iid, "lvl": lv, "n": n}


NODES = []


def node(nid, name, branch, parent, opens, unlock, levels, icon, beds=1, workers=1, sells=(), note=""):
    NODES.append({"id": nid, "name": name, "branch": branch, "parent": parent, "opens": opens, "unlock": list(unlock), "beds": beds,
                  "workers": workers, "note": note, "levels": levels, "icon": icon, "sells": list(sells)})


EAT = 6   # a person eats a day


def home_use(beds, extra=()):
    return [r("food", EAT * beds, "жильцы")] + list(extra)


# the master of a workshop lives in it (1 bed): he eats there; the other workers eat at their homes
M = r("food", EAT, "мастер")

# ---- centre
node("campfire", "Костёр", "center", "", "С самого начала", [], [lvl(use=[r("log", 1, "топливо")], note="Место сбора."), lvl(), lvl()], "item/campfire", beds=0, workers=0)
node("square", "Площадь", "center", "campfire", "4 жителя", [r("stone", 50), r("log", 20)],
     [lvl(use=[r("lighting", 0.5, "фонари")], cost=[r("cut_stone", 30), r("joinery", 4)], note="Мощёная площадь."), lvl(), lvl()], "block/polished_andesite", beds=0, workers=0)
node("well", "Колодец", "center", "square", "6 жителей", [r("cut_stone", 60), r("metal", 5)],
     [lvl(use=[r("lighting", 1)], cost=[r("cut_stone", 40), r("joinery", 6), r("metal", 3)], note="Настроение +."), lvl(), lvl()], "item/water_bucket", beds=0, workers=0)

# ---- homes: the people eat at home; the better the home, the more it wants (light, furnishings) — and the happier
node("tent", "Палатка", "home", "", "С самого начала", [], [lvl(use=home_use(2), cost=[r("log", 4), r("wool", 2)]), lvl(), lvl()], "item/string", beds=2, workers=0)
node("hut", "Хижина", "home", "", "С самого начала", [], [
    lvl(use=home_use(2), cost=[r("log", 20), r("stone", 6)], note="2 места."),
    lvl(use=home_use(2, [r("flowers", 0.1, "горшки")]), cost=[r("ceramics", 2), r("joinery", 2)]),
    lvl(use=home_use(2, [r("lighting", 0.2)]), cost=[r("stone", 8), r("lighting", 2)])], "item/oak_door", beds=2, workers=0)
node("house", "Деревянный дом", "home", "hut", "Хижина 3 ур.", [r("planks", 60), r("joinery", 10)], [
    lvl(use=home_use(2, [r("lighting", 0.2)]), cost=[r("log", 20), r("planks", 20), r("joinery", 6), r("stone", 15)], note="2 места."),
    lvl(use=home_use(2, [r("lighting", 0.3), r("furnishings", 0.05)]), cost=[r("glass", 4), r("joinery", 4)]),
    lvl(use=home_use(2, [r("lighting", 0.3), r("furnishings", 0.1)]), cost=[r("furnishings", 3), r("joinery", 4)])], "block/oak_log", beds=2, workers=0)
node("house_tall", "Двухэтажный деревянный дом", "home", "house", "Деревянный дом 3 ур.", [r("planks", 80), r("joinery", 20)], [
    lvl(use=home_use(3, [r("lighting", 0.3), r("furnishings", 0.05)]), cost=[r("log", 30), r("planks", 40), r("joinery", 12), r("glass", 6)], note="3 места."),
    lvl(use=home_use(3, [r("lighting", 0.4), r("furnishings", 0.1)]), cost=[r("furnishings", 3)]),
    lvl(use=home_use(3, [r("lighting", 0.4), r("furnishings", 0.15)]), cost=[r("furnishings", 4), r("ceramics", 4)])], "block/red_wool", beds=3, workers=0)
node("stone_house", "Каменный дом", "home", "house", "Деревянный дом 3 ур.", [r("cut_stone", 60), r("joinery", 10)], [
    lvl(use=home_use(2, [r("lighting", 0.3), r("furnishings", 0.05)]), cost=[r("cut_stone", 50), r("planks", 15), r("joinery", 8), r("glass", 4)], note="Теплее: настроение выше."),
    lvl(use=home_use(2, [r("lighting", 0.3), r("furnishings", 0.1)]), cost=[r("furnishings", 3)]),
    lvl(use=home_use(2, [r("lighting", 0.4), r("furnishings", 0.15)]), cost=[r("furnishings", 4)])], "block/stone_bricks", beds=2, workers=0)
node("stone_house_tall", "Двухэтажный каменный дом", "home", "stone_house", "Каменный дом 3 ур.", [r("cut_stone", 100), r("metal", 5)], [
    lvl(use=home_use(3, [r("lighting", 0.4), r("furnishings", 0.1)]), cost=[r("cut_stone", 80), r("planks", 25), r("joinery", 14), r("glass", 8), r("metal", 3)]),
    lvl(use=home_use(3, [r("lighting", 0.5), r("furnishings", 0.15)]), cost=[r("furnishings", 4)]),
    lvl(use=home_use(3, [r("lighting", 0.5), r("furnishings", 0.2)]), cost=[r("furnishings", 5), r("ceramics", 4)])], "block/bricks", beds=3, workers=0)

# ---- stores
node("storehouse", "Малый склад", "store", "", "С самого начала", [], [
    lvl(cost=[r("log", 20), r("stone", 4)], note="+160 к вместимости."), lvl(cost=[r("planks", 10)], note="+240"), lvl(cost=[r("joinery", 6)], note="+320")],
    "block/barrel_side", beds=0, workers=0)
node("storehouse_2", "Большой склад", "store", "storehouse", "Малый склад 3 ур.", [r("planks", 60), r("joinery", 10)], [
    lvl(use=[r("lighting", 0.2)], cost=[r("log", 30), r("planks", 30), r("joinery", 8), r("stone", 30), r("metal", 4)], note="+320 к вместимости."),
    lvl(cost=[r("joinery", 8)], note="+480"), lvl(cost=[r("metal", 6)], note="+640")], "block/oak_planks", beds=0, workers=0)

# ---- mine and metal
node("mine_house", "Дом шахтёров", "mine", "", "Есть шахтёр", [r("log", 60), r("stone", 60)], [
    lvl(make=[r("stone", 12), r("coal", 3), r("clay", 1), r("sand", 1), r("lighting", 0.5, "факелы из угля")], use=[M, r("tools", 0.3, "кирки"), r("lighting", 0.2), r("sticks", 0.5)],
        cost=[r("log", 35), r("stone", 40)], note="Камень, уголь, немного глины и песка (у воды)."),
    lvl(make=[r("stone", 15), r("coal", 5), r("ore", 4), r("clay", 1), r("sand", 1)], use=[M, r("tools", 0.3), r("lighting", 0.3), r("planks", 0.5, "крепь")],
        cost=[r("planks", 30), r("joinery", 4)], note="Каменные кирки: появляется руда."),
    lvl(make=[r("stone", 18), r("coal", 7), r("ore", 6), r("gold", 0.3), r("gems", 0.3)], use=[M, r("tools", 0.35), r("lighting", 0.5), r("planks", 1, "крепь")],
        cost=[r("metal", 10), r("joinery", 8)], note="Глубокие штреки: золото, самоцветы.")],
     "item/stone_pickaxe", sells=[sell("wooden_pickaxe", 1, 2), sell("stone_pickaxe", 2, 2), sell("wooden_shovel", 1, 2), sell("stone_shovel", 2, 2),
                                  sell("iron_pickaxe", 3, 1), sell("coal", 1, 8), sell("cobblestone", 1, 32), sell("raw_iron", 2, 4), sell("raw_copper", 2, 4),
                                  sell("lapis", 3, 2), sell("redstone", 3, 4)])
node("quarry", "Каменоломня", "mine", "mine_house", "Дом шахтёров 2 ур.", [r("stone", 60), r("planks", 30)], [
    lvl(make=[r("stone", 16), r("sand", 5), r("clay", 1)], use=[M, r("tools", 0.3)], note="Камень блоками, песок, гравий."),
    lvl(make=[r("stone", 22), r("sand", 7), r("clay", 3)], use=[M, r("tools", 0.3)]),
    lvl(make=[r("stone", 28), r("sand", 9), r("clay", 4)], use=[M, r("tools", 0.35)])],
     "block/stone", sells=[sell("stone", 1, 32), sell("granite", 1, 16), sell("diorite", 1, 16), sell("andesite", 1, 16), sell("sand", 1, 16),
                           sell("gravel", 1, 16), sell("flint", 1, 4), sell("deepslate", 3, 16), sell("tuff", 3, 16), sell("calcite", 3, 8)])
node("stonemason", "Каменотёс", "mine", "quarry", "Каменоломня 2 ур.", [r("cut_stone", 20), r("metal", 5)], [
    lvl(make=[r("cut_stone", 8)], use=[M, r("stone", 10), r("tools", 0.2)], note="Тёсаный камень для каменных домов и площадей."),
    lvl(make=[r("cut_stone", 12)], use=[M, r("stone", 15), r("tools", 0.2), r("coal", 1, "гладкий камень")]),
    lvl(make=[r("cut_stone", 16)], use=[M, r("stone", 20), r("tools", 0.25), r("coal", 1.5)])],
     "block/smooth_stone", sells=[sell("stone_bricks", 1, 16), sell("stone_slab", 1, 8), sell("stone_stairs", 2, 8), sell("smooth_stone", 2, 8),
                                  sell("polished_andesite", 2, 16), sell("sandstone", 3, 8)])
node("smelter", "Плавильня", "mine", "mine_house", "Дом шахтёров 2 ур.", [r("stone", 60), r("ceramics", 10), r("metal", 3)], [
    lvl(make=[r("metal", 3), r("glass", 2)], use=[M, r("ore", 3), r("sand", 2), r("coal", 2.5, "или древесный уголь")], note="Руда → металл. Топливо: уголь или древесный уголь."),
    lvl(make=[r("metal", 5), r("gold", 0.3), r("glass", 2)], use=[M, r("ore", 5), r("sand", 2), r("coal", 3.5)]),
    lvl(make=[r("metal", 8), r("gold", 0.5), r("glass", 3)], use=[M, r("ore", 8), r("sand", 3), r("coal", 5)], note="Плавильные печи: быстрее.")],
     "block/furnace_front", sells=[sell("iron_ingot", 1, 4), sell("copper_ingot", 1, 4), sell("iron_nugget", 2, 4), sell("gold_ingot", 2, 1), sell("glass", 1, 8)])
node("smithy", "Кузница", "mine", "smelter", "Плавильня 1 ур.", [r("cut_stone", 40), r("metal", 10)], [
    lvl(make=[r("tools", 1.5)], use=[M, r("metal", 2), r("sticks", 2), r("stone", 2), r("coal", 1.5)],
        note="Инструменты для всех ремёсел: каменные и медные. Без кузницы деревня делает только деревянные (у лесника)."),
    lvl(make=[r("tools", 2.5), r("weapons", 0.5), r("lighting", 1, "фонари")], use=[M, r("metal", 4), r("sticks", 3), r("coal", 2.5)],
        note="Железные инструменты, мечи, фонари, цепи."),
    lvl(make=[r("tools", 3.5), r("weapons", 1)], use=[M, r("metal", 5), r("sticks", 4), r("coal", 3), r("gold", 0.2), r("gems", 0.1)],
        note="Золотые и алмазные вещи, рельсы, наковальни.")],
     "block/anvil", sells=[sell("iron_sword", 2, 1), sell("stone_sword", 1, 1), sell("bucket", 1, 1), sell("shears", 1, 1), sell("flint_and_steel", 1, 1),
                           sell("chain", 2, 4), sell("iron_bars", 2, 4), sell("lantern", 2, 2), sell("rail", 3, 2), sell("anvil", 3, 1),
                           sell("golden_sword", 3, 1), sell("diamond_sword", 3, 1), sell("diamond_pickaxe", 3, 1), sell("compass", 3, 1)])
node("armorer", "Бронник", "mine", "smithy", "Кузница 2 ур. + Кожевня", [r("metal", 20), r("leather", 10)], [
    lvl(make=[r("armor", 1), r("weapons", 0.5, "щиты")], use=[M, r("metal", 2), r("leather", 1), r("planks", 1), r("coal", 1)]),
    lvl(make=[r("armor", 2), r("weapons", 1)], use=[M, r("metal", 4), r("leather", 1), r("planks", 2), r("coal", 1.5)]),
    lvl(make=[r("armor", 3), r("weapons", 1)], use=[M, r("metal", 6), r("gold", 0.3), r("gems", 0.2), r("coal", 2)])],
     "item/iron_chestplate", sells=[sell("leather_chestplate", 1, 1), sell("chainmail_helmet", 1, 1), sell("chainmail_chestplate", 1, 1),
                                    sell("iron_helmet", 2, 1), sell("iron_chestplate", 2, 1), sell("iron_leggings", 2, 1), sell("iron_boots", 2, 1),
                                    sell("shield", 1, 2), sell("horse_armor", 3, 1), sell("golden_chestplate", 3, 1), sell("diamond_chestplate", 3, 1)])

# ---- the forest
node("wood_hut", "Дом лесника", "wood", "", "Есть дровосек", [r("log", 80), r("stone", 30)], [
    lvl(make=[r("log", 14), r("sticks", 2), r("tools", 1, "деревянные — сам"), r("food", 1, "яблоки")], use=[M, r("tools", 0.3, "топоры")],
        cost=[r("log", 45), r("stone", 15)], note="Рубит у дома, сажает рощу. Делает себе и соседям деревянные инструменты."),
    lvl(make=[r("log", 18), r("sticks", 3), r("tools", 1), r("food", 1.5)], use=[M, r("tools", 0.3)], cost=[r("planks", 20)]),
    lvl(make=[r("log", 22), r("sticks", 4), r("tools", 1), r("food", 2)], use=[M, r("tools", 0.35)], cost=[r("metal", 3), r("joinery", 4)],
        note="Большая роща, быстрый рост.")],
     "block/oak_sapling", sells=[sell("wooden_axe", 1, 2), sell("stone_axe", 2, 2), sell("iron_axe", 3, 1), sell("oak_log", 1, 32), sell("spruce_log", 1, 32),
                                 sell("birch_log", 1, 32), sell("sapling", 1, 4), sell("stick", 1, 8), sell("apple", 1, 4), sell("leaves", 2, 16),
                                 sell("cherry_log", 3, 16), sell("dark_oak_log", 3, 16)])
node("sawmill", "Лесопилка", "wood", "wood_hut", "Дом лесника 3 ур.", [r("log", 100), r("stone", 40)], [
    lvl(make=[r("planks", 40), r("sticks", 10)], use=[M, r("log", 12), r("tools", 0.2)], cost=[r("log", 60), r("stone", 25)], note="Бревно → 4 доски или 8 палок."),
    lvl(make=[r("planks", 44), r("sticks", 10), r("joinery", 6)], use=[M, r("log", 15), r("tools", 0.2)], note="Столярка: ступеньки, полублоки, заборы."),
    lvl(make=[r("planks", 52), r("sticks", 12), r("joinery", 10)], use=[M, r("log", 18), r("tools", 0.25)], note="Двери, люки, таблички, леса.")],
     "block/oak_planks", sells=[sell("oak_planks", 1, 32), sell("spruce_planks", 1, 32), sell("stairs", 2, 8), sell("slab", 2, 8), sell("fence", 2, 8),
                                sell("fence_gate", 2, 2), sell("ladder", 2, 4), sell("oak_door", 3, 2), sell("spruce_door", 3, 2), sell("trapdoor", 3, 2),
                                sell("sign", 3, 4), sell("scaffolding", 3, 4)])
node("carpenter", "Столярная мастерская", "wood", "sawmill", "Лесопилка 2 ур.", [r("planks", 60), r("metal", 3)], [
    lvl(make=[r("joinery", 4), r("furnishings", 1)], use=[M, r("planks", 10), r("tools", 0.2)], note="Сундуки, бочки, двери."),
    lvl(make=[r("joinery", 6), r("furnishings", 1.5), r("transport", 0.2, "лодки")], use=[M, r("planks", 16), r("wool", 1, "кровати"), r("tools", 0.2)]),
    lvl(make=[r("joinery", 8), r("furnishings", 2), r("transport", 0.3)], use=[M, r("planks", 20), r("wool", 1.5), r("paper", 0.5, "книжные полки"), r("tools", 0.25)])],
     "block/crafting_table_front", sells=[sell("chest", 1, 2), sell("barrel", 1, 2), sell("oak_door", 1, 2), sell("red_bed", 2, 1), sell("oak_boat", 2, 1),
                                          sell("bookshelf", 3, 2), sell("item_frame", 2, 2)])
node("charcoal_pit", "Углежог", "wood", "wood_hut", "Дом лесника 2 ур.", [r("log", 40), r("stone", 20)], [
    lvl(make=[r("charcoal", 4), r("lighting", 0.5, "факелы")], use=[M, r("log", 6), r("sticks", 0.5)], note="Древесный уголь — топливо, как уголь."),
    lvl(make=[r("charcoal", 6), r("lighting", 1)], use=[M, r("log", 9), r("sticks", 1)]),
    lvl(make=[r("charcoal", 8), r("lighting", 1.5)], use=[M, r("log", 12), r("sticks", 1.5)])],
     "item/charcoal", sells=[sell("charcoal", 1, 8), sell("torch", 1, 8)])

# ---- fishing
node("fish_hut", "Хижина рыбаков", "fish", "", "Есть рыбак", [r("log", 70), r("stone", 20)], [
    lvl(make=[r("food", 16), r("fertilizer", 0.3, "кости")], use=[M, r("tools", 0.3, "удочки")], cost=[r("log", 40), r("stone", 10)], note="Рыба — в еду."),
    lvl(make=[r("food", 20), r("fertilizer", 0.5), r("dyes", 0.3, "чернила")], use=[M, r("tools", 0.3)]),
    lvl(make=[r("food", 24), r("fertilizer", 0.5), r("dyes", 0.5), r("transport", 0.02, "сокровища")], use=[M, r("tools", 0.35), r("transport", 0.02, "лодки")],
        note="Лодки: сокровища, наутилусы, сёдла, бирки.")],
     "item/cod", sells=[sell("fishing_rod", 1, 2), sell("cod", 1, 8), sell("salmon", 1, 8), sell("tropical_fish", 2, 4), sell("ink_sac", 2, 2),
                        sell("name_tag", 3, 1), sell("saddle", 3, 1)])
node("smokehouse", "Коптильня", "fish", "fish_hut", "Хижина рыбаков 2 ур.", [r("ceramics", 20), r("planks", 30)], [
    lvl(make=[r("food", 10)], use=[M, r("food", 6, "сырая рыба и мясо"), r("charcoal", 1)], note="Жареная еда сытнее: 6 сырой → 10."),
    lvl(make=[r("food", 15)], use=[M, r("food", 9), r("charcoal", 1.5)]),
    lvl(make=[r("food", 20)], use=[M, r("food", 12), r("charcoal", 2)])],
     "block/smoker_front", sells=[sell("cooked_cod", 1, 8), sell("cooked_salmon", 1, 8), sell("dried_kelp", 1, 16), sell("steak", 2, 4), sell("cooked_porkchop", 2, 4)])
node("docks", "Причал", "fish", "fish_hut", "Хижина рыбаков 3 ур.", [r("planks", 80), r("joinery", 20)], [
    lvl(make=[r("transport", 0.3)], use=[M, r("planks", 5)], note="Лодки; основа порта."), lvl(make=[r("transport", 0.5)], use=[M, r("planks", 8)]), lvl()],
     "item/oak_boat", sells=[sell("oak_boat", 1, 1), sell("lead", 2, 2)])

# ---- fields
node("field", "Поле", "farm", "", "С самого начала", [], [
    lvl(make=[r("grain", 10), r("food", 4, "картофель, морковь")], use=[r("fertilizer", 0.3, "по желанию")], cost=[r("log", 6)], note="Работают фермеры. Посев — в меню поля."),
    lvl(make=[r("grain", 13), r("food", 5)], use=[r("fertilizer", 0.5)], cost=[r("joinery", 2)], note="Пугало. Тыквы, арбузы."),
    lvl(make=[r("grain", 16), r("food", 6)], use=[r("fertilizer", 0.8)], cost=[r("lighting", 2)], note="Тростник, какао, ягоды.")],
     "item/wheat", beds=0, workers=0)
node("farm", "Ферма", "farm", "field", "Поле 3 ур.", [r("log", 80), r("stone", 40)], [
    lvl(make=[r("food", 14), r("fertilizer", 1, "компост")], use=[M, r("grain", 4), r("tools", 0.3, "мотыги")], cost=[r("log", 45), r("stone", 20)],
        note="Больше полей (+1 за уровень), компостер."),
    lvl(make=[r("food", 18), r("fertilizer", 1.5)], use=[M, r("grain", 5), r("tools", 0.3)]),
    lvl(make=[r("food", 22), r("fertilizer", 2)], use=[M, r("grain", 6), r("tools", 0.35)])],
     "block/hay_block_side", sells=[sell("wooden_hoe", 1, 2), sell("stone_hoe", 2, 2), sell("iron_hoe", 3, 1), sell("wheat_seeds", 1, 16), sell("wheat", 1, 16),
                                    sell("potato", 1, 16), sell("carrot", 1, 16), sell("beetroot_seeds", 2, 8), sell("pumpkin", 2, 4), sell("melon_seeds", 2, 4),
                                    sell("sugar_cane", 3, 8), sell("cocoa_beans", 3, 8), sell("bone_meal", 1, 8), sell("hay_block", 2, 4)])
node("orchard", "Сад", "farm", "farm", "Ферма 2 ур.", [r("log", 20), r("joinery", 10)], [
    lvl(make=[r("food", 10)], use=[M, r("fertilizer", 0.5)], note="Яблоки, ягоды."),
    lvl(make=[r("food", 13), r("grain", 2, "какао")], use=[M, r("fertilizer", 1)]),
    lvl(make=[r("food", 16), r("grain", 3)], use=[M, r("fertilizer", 1), r("gold", 0.05, "золотые яблоки")])],
     "item/apple", sells=[sell("apple", 1, 8), sell("sweet_berries", 1, 8), sell("glow_berries", 2, 4), sell("cocoa_beans", 2, 8), sell("golden_apple", 3, 1)])
node("flower_garden", "Цветник", "farm", "field", "Поле 2 ур.", [r("log", 20), r("joinery", 6)], [
    lvl(make=[r("flowers", 6)], use=[M, r("fertilizer", 0.5)], note="Цветы для художника, пасеки, крылец."),
    lvl(make=[r("flowers", 9)], use=[M, r("fertilizer", 0.8)]),
    lvl(make=[r("flowers", 12)], use=[M, r("fertilizer", 1)], note="Редкие цветы.")],
     "block/poppy", sells=[sell("poppy", 1, 8), sell("dandelion", 1, 8), sell("cornflower", 2, 8), sell("oxeye_daisy", 2, 8), sell("allium", 3, 4),
                           sell("azure_bluet", 3, 4), sell("flower_pot", 2, 2)])
node("bakery", "Пекарня", "farm", "farm", "Ферма 2 ур.", [r("ceramics", 20), r("planks", 30)], [
    lvl(make=[r("food", 14)], use=[M, r("grain", 8), r("charcoal", 1)], note="Хлеб: 8 зерна → 14 еды. Разнообразие → счастье."),
    lvl(make=[r("food", 20)], use=[M, r("grain", 11), r("charcoal", 1.5), r("food", 2, "яйца, молоко")]),
    lvl(make=[r("food", 26)], use=[M, r("grain", 14), r("charcoal", 2), r("food", 3), r("wax", 0.5, "мёд")], note="Торты, пироги.")],
     "item/bread", sells=[sell("bread", 1, 8), sell("cookie", 2, 8), sell("pumpkin_pie", 2, 2), sell("cake", 3, 1), sell("sugar", 2, 8)])
node("apiary", "Пасека", "farm", "flower_garden", "Цветник 1 ур.", [r("planks", 30), r("glass", 6)], [
    lvl(make=[r("wax", 1.5), r("food", 3, "мёд")], use=[M, r("glass", 0.5, "склянки"), r("flowers", 2, "опыление")]),
    lvl(make=[r("wax", 2.5), r("food", 4)], use=[M, r("glass", 0.8), r("flowers", 3)]),
    lvl(make=[r("wax", 3.5), r("food", 5)], use=[M, r("glass", 1), r("flowers", 4)])],
     "item/honeycomb", sells=[sell("honey_bottle", 1, 4), sell("honeycomb", 1, 4)])

# ---- animals
node("sheep_pen", "Овчарня", "animals", "farm", "Ферма 1 ур.", [r("joinery", 10), r("planks", 20)], [
    lvl(make=[r("wool", 5), r("food", 3, "баранина")], use=[M, r("grain", 4, "корм"), r("tools", 0.2, "ножницы")], note="Шерсть и нить."),
    lvl(make=[r("wool", 7), r("food", 4)], use=[M, r("grain", 5), r("tools", 0.2)]),
    lvl(make=[r("wool", 10), r("food", 5)], use=[M, r("grain", 7), r("tools", 0.25)])],
     "block/white_wool", sells=[sell("shears", 1, 1), sell("white_wool", 1, 16), sell("string", 1, 4), sell("cooked_mutton", 2, 4), sell("lead", 3, 2)])
node("cattle_barn", "Коровник", "animals", "farm", "Ферма 2 ур.", [r("joinery", 16), r("planks", 30)], [
    lvl(make=[r("food", 10, "молоко, говядина"), r("leather", 1.5)], use=[M, r("grain", 6), r("metal", 0.05, "вёдра")]),
    lvl(make=[r("food", 14), r("leather", 2)], use=[M, r("grain", 8), r("metal", 0.05)]),
    lvl(make=[r("food", 18), r("leather", 3)], use=[M, r("grain", 10), r("metal", 0.1)])],
     "item/milk_bucket", sells=[sell("milk", 1, 2), sell("leather", 1, 4), sell("steak", 2, 4)])
node("pigsty", "Свинарник", "animals", "farm", "Ферма 1 ур.", [r("joinery", 10), r("planks", 16)], [
    lvl(make=[r("food", 12)], use=[M, r("grain", 4, "морковь, картофель")]),
    lvl(make=[r("food", 16)], use=[M, r("grain", 5)]),
    lvl(make=[r("food", 20), r("transport", 0.02, "сёдла")], use=[M, r("grain", 7)])],
     "item/porkchop", sells=[sell("cooked_porkchop", 1, 4)])
node("coop", "Курятник", "animals", "field", "Поле 2 ур.", [r("joinery", 8), r("planks", 16)], [
    lvl(make=[r("food", 8, "яйца, курица"), r("wool", 0.5, "перья")], use=[M, r("grain", 3, "семена")]),
    lvl(make=[r("food", 11), r("wool", 0.8)], use=[M, r("grain", 4)]),
    lvl(make=[r("food", 14), r("wool", 1)], use=[M, r("grain", 5)])],
     "item/egg", sells=[sell("egg", 1, 8), sell("feather", 1, 8), sell("cooked_chicken", 2, 4)])
node("rabbit_hutch", "Крольчатник", "animals", "coop", "Курятник 2 ур.", [r("joinery", 8)], [
    lvl(make=[r("food", 6), r("leather", 1, "шкурки")], use=[M, r("grain", 3, "морковь")]),
    lvl(make=[r("food", 9), r("leather", 1.5)], use=[M, r("grain", 4)]),
    lvl(make=[r("food", 12), r("leather", 2)], use=[M, r("grain", 5)])],
     "item/rabbit_hide", sells=[sell("rabbit_hide", 1, 4), sell("rabbit_stew", 2, 2)])
node("stable", "Конюшня", "animals", "cattle_barn", "Коровник 2 ур.", [r("joinery", 30), r("planks", 40), r("grain", 30)], [
    lvl(make=[r("transport", 0.1, "лошади")], use=[M, r("grain", 6), r("food", 1, "яблоки")], note="Лошади для караванов."),
    lvl(make=[r("transport", 0.2)], use=[M, r("grain", 8), r("food", 2), r("leather", 0.2, "сёдла")]),
    lvl(make=[r("transport", 0.3)], use=[M, r("grain", 10), r("food", 2), r("leather", 0.3), r("armor", 0.05, "конская броня")])],
     "item/saddle", sells=[sell("saddle", 1, 1), sell("lead", 1, 2), sell("horse_armor", 3, 1)])

# ---- crafts
node("artist", "Дом художника", "craft", "flower_garden", "Цветник 1 ур.", [r("planks", 40), r("glass", 6)], [
    lvl(make=[r("dyes", 4)], use=[M, r("flowers", 4), r("fertilizer", 0.5, "белая")], note="4 цвета: красный, жёлтый, белый, зелёный."),
    lvl(make=[r("dyes", 6)], use=[M, r("flowers", 5), r("gems", 0.2, "лазурит → синий"), r("fertilizer", 0.5)], note="Смешанные цвета: оранжевый, розовый, голубой…"),
    lvl(make=[r("dyes", 8), r("furnishings", 0.5, "картины")], use=[M, r("flowers", 6), r("gems", 0.3), r("wool", 0.5), r("sticks", 2)], note="Все 16 цветов, картины.")],
     "item/painting", sells=[sell("red_dye", 1, 8), sell("yellow_dye", 1, 8), sell("white_dye", 1, 8), sell("green_dye", 1, 8), sell("orange_dye", 2, 8),
                             sell("pink_dye", 2, 8), sell("light_blue_dye", 2, 8), sell("blue_dye", 2, 8), sell("black_dye", 2, 8), sell("purple_dye", 3, 8),
                             sell("magenta_dye", 3, 8), sell("cyan_dye", 3, 8), sell("red_wool", 2, 8), sell("blue_wool", 2, 8), sell("yellow_wool", 2, 8),
                             sell("painting", 3, 1)])
node("weaver", "Ткацкая", "craft", "sheep_pen", "Овчарня 1 ур.", [r("planks", 30), r("wool", 10)], [
    lvl(make=[r("furnishings", 1, "ковры"), r("wool", 1, "нить")], use=[M, r("wool", 3)], note="Ковры; нить из шерсти."),
    lvl(make=[r("furnishings", 2, "ковры, флаги")], use=[M, r("wool", 5), r("dyes", 1), r("sticks", 0.5)]),
    lvl(make=[r("furnishings", 3)], use=[M, r("wool", 7), r("dyes", 1.5), r("planks", 1, "кровати")])],
     "block/red_wool", sells=[sell("carpet", 1, 4), sell("string", 1, 8), sell("banner", 2, 2), sell("red_bed", 3, 1)])
node("tannery", "Кожевня", "craft", "cattle_barn", "Коровник 1 ур.", [r("planks", 30), r("stone", 20)], [
    lvl(make=[r("armor", 0.3, "кожаная"), r("furnishings", 0.3, "рамки")], use=[M, r("leather", 1.5), r("sticks", 1)]),
    lvl(make=[r("armor", 0.5), r("furnishings", 0.5), r("paper", 0.5, "переплёт")], use=[M, r("leather", 2.5), r("sticks", 1)]),
    lvl(make=[r("armor", 0.8), r("transport", 0.1, "сёдла")], use=[M, r("leather", 3.5), r("metal", 0.3)])],
     "item/leather", sells=[sell("leather", 1, 4), sell("leather_helmet", 1, 1), sell("leather_chestplate", 1, 1), sell("leather_leggings", 2, 1),
                            sell("leather_boots", 2, 1), sell("item_frame", 2, 2), sell("saddle", 3, 1)])
node("potter", "Гончарня", "craft", "smelter", "Плавильня 1 ур.", [r("stone", 30), r("clay", 20)], [
    lvl(make=[r("ceramics", 3)], use=[M, r("clay", 3), r("coal", 1)], note="Кирпич и горшки."),
    lvl(make=[r("ceramics", 5)], use=[M, r("clay", 5), r("coal", 1.5)]),
    lvl(make=[r("ceramics", 7)], use=[M, r("clay", 7), r("coal", 2), r("dyes", 0.5, "глазурь")])],
     "item/flower_pot", sells=[sell("brick", 1, 16), sell("flower_pot", 1, 4), sell("bricks", 2, 8), sell("terracotta", 2, 8), sell("decorated_pot", 3, 1),
                               sell("glazed_terracotta", 3, 4)])
node("glassworks", "Стекольня", "craft", "smelter", "Плавильня 2 ур.", [r("ceramics", 20), r("metal", 3)], [
    lvl(make=[r("glass", 5)], use=[M, r("sand", 5), r("coal", 1.5)]),
    lvl(make=[r("glass", 7)], use=[M, r("sand", 7), r("coal", 2)]),
    lvl(make=[r("glass", 9)], use=[M, r("sand", 9), r("coal", 2.5), r("dyes", 0.5, "витражи")])],
     "block/glass", sells=[sell("glass", 1, 16), sell("glass_pane", 1, 2), sell("glass_bottle", 2, 4), sell("stained_glass", 3, 8), sell("spyglass", 3, 1)])
node("papermill", "Бумажная мастерская", "craft", "field", "Поле 3 ур.", [r("planks", 40), r("stone", 20)], [
    lvl(make=[r("paper", 2)], use=[M, r("grain", 3, "тростник")]),
    lvl(make=[r("paper", 3)], use=[M, r("grain", 4), r("leather", 0.3, "книги")]),
    lvl(make=[r("paper", 4)], use=[M, r("grain", 5), r("leather", 0.5), r("dyes", 0.2, "чернила")], note="Карты, книги с пером.")],
     "item/paper", sells=[sell("paper", 1, 8), sell("book", 2, 2), sell("map", 3, 1), sell("writable_book", 3, 1)])
node("chandler", "Свечная", "craft", "apiary", "Пасека 1 ур.", [r("planks", 30)], [
    lvl(make=[r("lighting", 2)], use=[M, r("wax", 1), r("wool", 0.5, "фитиль")], note="Свечи для домов 2-3 ур."),
    lvl(make=[r("lighting", 3)], use=[M, r("wax", 1.5), r("wool", 0.8), r("dyes", 0.3)]),
    lvl(make=[r("lighting", 4)], use=[M, r("wax", 2), r("wool", 1), r("metal", 0.3, "фонари")])],
     "item/candle", sells=[sell("candle", 1, 8), sell("torch", 1, 8), sell("lantern", 3, 2)])
node("fletcher", "Лучник-стрельник", "craft", "wood_hut", "Дом лесника 2 ур. + Курятник", [r("planks", 30)], [
    lvl(make=[r("weapons", 1), r("tools", 0.3, "удочки")], use=[M, r("sticks", 2), r("wool", 1, "нить, перья"), r("stone", 1, "кремень")]),
    lvl(make=[r("weapons", 1.5), r("tools", 0.4)], use=[M, r("sticks", 3), r("wool", 1.5), r("stone", 1)]),
    lvl(make=[r("weapons", 2)], use=[M, r("sticks", 4), r("wool", 2), r("metal", 0.3, "арбалеты")])],
     "item/bow", sells=[sell("arrow", 1, 8), sell("bow", 1, 1), sell("fishing_rod", 1, 2), sell("crossbow", 3, 1)])

# ---- trade and knowledge
node("market", "Рыночная лавка", "trade", "", "4 взрослых", [r("log", 60), r("stone", 20)], [
    lvl(use=[M], cost=[r("log", 40), r("stone", 10)], note="Торговец продаёт понемногу всё со склада, но дороже мастеров; покупает всё."),
    lvl(use=[M, r("paper", 0.2, "счета")], note="Больше товаров, лучше цены."), lvl(use=[M, r("paper", 0.3)], note="Караваны в соседние деревни (позже).")],
     "item/emerald", sells=[sell("bread", 1, 4), sell("oak_log", 1, 16), sell("cobblestone", 1, 16), sell("torch", 1, 4), sell("emerald", 3, 1)])
node("cartographer", "Дом картографа", "trade", "", "5 взрослых", [r("log", 60), r("planks", 30)], [
    lvl(use=[r("food", 12, "разведчик ест вдвое")], cost=[r("log", 40), r("planks", 20), r("stone", 10)], note="Разведчик: 500 блоков."),
    lvl(use=[r("food", 12), r("paper", 0.5), r("metal", 0.05, "компас")], note="900 блоков."),
    lvl(use=[r("food", 12), r("paper", 1), r("metal", 0.05)], note="1400 блоков.")],
     "item/map", sells=[sell("map", 1, 1), sell("compass", 2, 1), sell("spyglass", 3, 1)])
node("tavern", "Таверна", "trade", "market", "Рыночная лавка 2 ур. + 8 жителей", [r("planks", 60), r("cut_stone", 30)], [
    lvl(use=[M, r("food", 8, "угощение"), r("lighting", 0.5)], note="Вечера в таверне вместо костра: настроение +."),
    lvl(use=[M, r("food", 12), r("lighting", 0.8), r("furnishings", 0.1)], note="Разнообразная еда → счастье."),
    lvl(use=[M, r("food", 16), r("lighting", 1), r("furnishings", 0.2)], note="Праздники.")],
     "item/honey_bottle", sells=[sell("cooked_cod", 1, 4), sell("bread", 1, 4), sell("honey_bottle", 2, 2), sell("cake", 3, 1), sell("rabbit_stew", 3, 2)])
node("library", "Библиотека", "trade", "cartographer", "Дом картографа 2 ур. + Бумажная 2 ур.", [r("furnishings", 10), r("planks", 40)], [
    lvl(use=[M, r("paper", 1), r("lighting", 0.3)], note="Знания: дешевле открывать узлы (идея)."),
    lvl(use=[M, r("paper", 1.5), r("lighting", 0.4)]),
    lvl(use=[M, r("paper", 2), r("lighting", 0.5)], note="Школа: дети быстрее осваивают ремесло (идея).")],
     "block/bookshelf", sells=[sell("book", 1, 2), sell("writable_book", 2, 1), sell("bookshelf", 3, 1)])
node("caravanserai", "Караван-двор", "trade", "market", "Рыночная лавка 3 ур. + Конюшня 1 ур.", [r("planks", 80), r("joinery", 20)], [
    lvl(use=[M, r("grain", 3), r("transport", 0.02)], note="Обмен излишками с соседними деревнями."),
    lvl(use=[M, r("grain", 4), r("transport", 0.03)]),
    lvl(use=[M, r("grain", 5), r("transport", 0.05), r("metal", 0.3, "рельсы")], note="Дороги и рельсы между деревнями.")],
     "item/chest_minecart", sells=[sell("minecart", 3, 1), sell("rail", 3, 2)])

for i, n in enumerate(NODES):
    n["order"] = i

# ---------------------------------------------------------------- icons: the categories' and the items'
def load(key):
    for cand in key.split(";"):
        f = os.path.join(TEX, *cand.split("/")) + ".png"
        if os.path.exists(f):
            img = Image.open(f).convert("RGBA")
            w, h = img.size
            if h > w:
                img = img.crop((0, 0, w, w))
            if img.size != (16, 16):
                img = img.resize((16, 16), Image.NEAREST)
            if cand.split("/")[1] in ("oak_leaves",):
                px = img.load()
                for y in range(16):
                    for x in range(16):
                        rr, g, b, a = px[x, y]
                        px[x, y] = (int(rr * 0.45), int(g * 0.75), int(b * 0.35), a)
            buf = io.BytesIO()
            img.save(buf, "PNG", optimize=True)
            return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    return None


icons, missing = {}, []
for cid, name, group, tex, note in CATS:
    u = load(tex)
    if u:
        icons[cid] = u
    else:
        missing.append(tex)
for it in ITEMS:
    u = load(it["tex"])
    if u:
        icons["i:" + it["id"]] = u
    else:
        missing.append(it["tex"])
for n in NODES:
    u = load(n["icon"])
    if u:
        icons["b:" + n["id"]] = u
    else:
        missing.append(n["icon"])
    n["icon"] = "b:" + n["id"] if u else ""

# ---------------------------------------------------------------- checks, and out
cids = {c[0] for c in CATS}
iids = {i["id"] for i in ITEMS}
assert len(iids) == len(ITEMS), "duplicate item"
for n in NODES:
    for L in n["levels"]:
        for k in ("make", "use", "cost"):
            for row in L[k]:
                assert row["r"] in cids, (n["id"], row["r"])
    for row in n["unlock"]:
        assert row["r"] in cids, (n["id"], row["r"])
    for s in n["sells"]:
        assert s["item"] in iids, (n["id"], s["item"])
    assert n["parent"] == "" or n["parent"] in {x["id"] for x in NODES}, n["id"]
for it in ITEMS:
    assert it["res"] in cids, it

catalog = {"version": 2, "groups": [{"id": a, "name": b} for a, b in GROUPS],
           "cats": [{"id": a, "name": b} for a, b in GROUPS],
           "resources": [{"id": a, "name": b, "cat": c, "note": e} for a, b, c, d, e in CATS]}
json.dump(catalog, open(os.path.join(OUT, "catalog.json"), "w", encoding="utf-8"), ensure_ascii=False)
json.dump({"items": [{k: v for k, v in it.items() if k != "tex"} for it in ITEMS]}, open(os.path.join(OUT, "items.json"), "w", encoding="utf-8"), ensure_ascii=False)
json.dump({"branches": [{"id": a, "name": b, "color": c} for a, b, c in BR]}, open(os.path.join(OUT, "branches.json"), "w", encoding="utf-8"), ensure_ascii=False)
for n in NODES:
    d = dict(n)
    d.pop("id")
    json.dump(d, open(os.path.join(OUT, "nodes", n["id"] + ".json"), "w", encoding="utf-8"), ensure_ascii=False)
open(os.path.join(HERE, "icons2.js"), "w", encoding="utf-8").write("window.ICONS=" + json.dumps(icons, separators=(",", ":")) + ";\n")
print(len(CATS), "categories,", len(ITEMS), "items,", len(NODES), "buildings,", len(icons), "icons; missing:", missing)
print(os.path.getsize(os.path.join(HERE, "icons2.js")), "bytes of icons")
