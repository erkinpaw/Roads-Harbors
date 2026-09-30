package org.webtrade.minecraftportsmod.fleet;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Default names for new ports and vessels, in Russian or English depending on the language of the player who
 * founds/builds them. Names already taken are skipped; if every name is taken a number is appended.
 */
public final class Names {

    private Names() {
    }

    // ------------------------------------------------------------------ ports

    /** {masculine, feminine} adjective forms. */
    private static final String[][] PORT_ADJ_RU = {
            {"Тихий", "Тихая"}, {"Солёный", "Солёная"}, {"Янтарный", "Янтарная"}, {"Серебряный", "Серебряная"},
            {"Туманный", "Туманная"}, {"Северный", "Северная"}, {"Южный", "Южная"}, {"Западный", "Западная"},
            {"Восточный", "Восточная"}, {"Каменный", "Каменная"}, {"Сосновый", "Сосновая"}, {"Чаячий", "Чаячья"},
            {"Коралловый", "Коралловая"}, {"Лунный", "Лунная"}, {"Ветреный", "Ветреная"}, {"Жемчужный", "Жемчужная"},
            {"Кедровый", "Кедровая"}, {"Золотой", "Золотая"}, {"Дальний", "Дальняя"}, {"Старый", "Старая"},
            {"Рыбацкий", "Рыбацкая"}, {"Штормовой", "Штормовая"}};
    private static final String[] PORT_NOUN_RU_M = {"залив", "причал", "порт", "рейд", "мыс"};
    private static final String[] PORT_NOUN_RU_F = {"гавань", "бухта", "пристань", "заводь", "лагуна", "коса"};

    private static final String[] PORT_ADJ_EN = {"Quiet", "Salt", "Amber", "Silver", "Misty", "North", "South", "West",
            "East", "Stone", "Pine", "Gull", "Coral", "Moon", "Windy", "Pearl", "Cedar", "Golden", "Far", "Old",
            "Fisher's", "Storm"};
    private static final String[] PORT_NOUN_EN = {"Harbour", "Bay", "Landing", "Cove", "Haven", "Point", "Wharf",
            "Lagoon", "Spit", "Roads", "Quay"};

    public static String portName(ServerPlayer founder, Collection<String> taken) {
        return portName(russian(founder), taken);
    }

    public static String portName(boolean ru, Collection<String> taken) {
        List<String> all = new ArrayList<>();
        if (ru) {
            for (String[] adj : PORT_ADJ_RU) {
                for (String n : PORT_NOUN_RU_F) all.add(adj[1] + " " + n);
                for (String n : PORT_NOUN_RU_M) all.add(adj[0] + " " + n);
            }
        } else {
            for (String adj : PORT_ADJ_EN) for (String n : PORT_NOUN_EN) all.add(adj + " " + n);
        }
        return pick(all, taken);
    }

    // ------------------------------------------------------------------ vessels

    private static final String[] VESSEL_RU = {
            "Чайка", "Альбатрос", "Бриз", "Надежда", "Отважный", "Буревестник", "Ласточка", "Стремительный", "Мечта",
            "Удача", "Звезда", "Волна", "Русалка", "Сирена", "Фортуна", "Паллада", "Аврора", "Заря", "Ураган", "Шторм",
            "Смелый", "Быстрый", "Верный", "Гордый", "Лёгкий", "Вихрь", "Сокол", "Орёл", "Кречет", "Беркут", "Ястреб",
            "Баклан", "Пеликан", "Фрегат", "Кит", "Дельфин", "Косатка", "Нерпа", "Морж", "Тюлень", "Осётр", "Лосось",
            "Скат", "Марлин", "Меч-рыба", "Жемчужина", "Янтарь", "Изумруд", "Сапфир", "Рубин", "Топаз", "Коралл",
            "Бирюза", "Малахит", "Агат", "Комета", "Полярная звезда", "Сириус", "Вега", "Орион", "Кассиопея", "Андромеда",
            "Персей", "Пегас", "Меридиан", "Экватор", "Норд", "Зюйд", "Ост", "Вест", "Пассат", "Муссон", "Сирокко",
            "Мистраль", "Бора", "Тайфун", "Циклон", "Прилив", "Отлив", "Штиль", "Горизонт", "Маяк", "Якорь", "Компас",
            "Секстант", "Штурвал", "Парус", "Мачта", "Бушприт", "Кливер", "Дюна", "Рассвет", "Закат", "Полдень",
            "Сумерки", "Полночь", "Странник", "Скиталец", "Бродяга", "Искатель", "Первопроходец", "Мореход", "Лоцман",
            "Кормчий", "Боцман", "Юнга", "Корсар", "Флибустьер", "Капер", "Викинг", "Варяг", "Помор", "Ермак", "Садко",
            "Афалина", "Каравелла", "Бригантина", "Шхуна", "Галера", "Ладья", "Струг", "Коч", "Ботик", "Тартана",
            "Весна", "Лето", "Осень", "Зима", "Вера", "Любовь", "Слава", "Победа", "Свобода", "Воля", "Дружба", "Верность",
            "Отвага", "Честь", "Искра", "Пламя", "Молния", "Гром", "Радуга", "Облако", "Туман", "Роса", "Иней", "Снежинка",
            "Метель", "Вьюга", "Капель", "Ручей", "Родник", "Лагуна", "Бухта", "Коса", "Остров", "Атолл", "Риф", "Утёс",
            "Бриллиант", "Серебрянка", "Золотинка", "Ветерок", "Волнушка", "Пеструшка", "Белянка", "Смуглянка",
            "Непоседа", "Проказница", "Хитрюга", "Ворчун", "Молчун", "Весельчак", "Добряк", "Силач", "Храбрец",
            "Ёрш", "Окунь", "Щука", "Карась", "Налим", "Сом", "Сазан", "Судак", "Хариус", "Таймень", "Нельма", "Омуль"};
    private static final String[] VESSEL_EPITHET_RU = {"Быстрая", "Смелая", "Белая", "Синяя", "Золотая", "Серебряная",
            "Морская", "Северная", "Вольная", "Дерзкая", "Лёгкая", "Верная", "Гордая", "Весёлая", "Ясная", "Тихая",
            "Алая", "Ночная", "Ветреная", "Счастливая"};
    private static final String[] VESSEL_NOUN_RU = {"Чайка", "Ласточка", "Звезда", "Волна", "Дева", "Удача", "Мечта",
            "Стрела", "Комета", "Русалка", "Сирена", "Жемчужина", "Роза", "Лилия", "Королева", "Принцесса", "Птица",
            "Цапля", "Касатка", "Лебедь"};

    private static final String[] VESSEL_EN = {
            "Seagull", "Albatross", "Breeze", "Hope", "Valiant", "Petrel", "Swallow", "Swift", "Dream", "Fortune",
            "Star", "Wave", "Mermaid", "Siren", "Pallas", "Aurora", "Dawn", "Hurricane", "Tempest", "Intrepid",
            "Endeavour", "Resolute", "Faithful", "Proud", "Nimble", "Whirlwind", "Falcon", "Eagle", "Osprey", "Kestrel",
            "Hawk", "Cormorant", "Pelican", "Frigatebird", "Whale", "Dolphin", "Orca", "Seal", "Walrus", "Narwhal",
            "Sturgeon", "Salmon", "Manta", "Marlin", "Swordfish", "Pearl", "Amber", "Emerald", "Sapphire", "Ruby",
            "Topaz", "Coral", "Turquoise", "Jade", "Agate", "Comet", "Polaris", "Sirius", "Vega", "Orion",
            "Cassiopeia", "Andromeda", "Perseus", "Pegasus", "Meridian", "Equator", "Nor'easter", "Sou'wester",
            "Trade Wind", "Monsoon", "Sirocco", "Mistral", "Typhoon", "Cyclone", "High Tide", "Low Tide", "Calm",
            "Horizon", "Lighthouse", "Anchor", "Compass", "Sextant", "Helm", "Topsail", "Bowsprit", "Jib", "Sunrise",
            "Sunset", "Noon", "Twilight", "Midnight", "Wanderer", "Rover", "Drifter", "Seeker", "Pathfinder",
            "Mariner", "Pilot", "Helmsman", "Boatswain", "Cabin Boy", "Corsair", "Buccaneer", "Privateer", "Viking",
            "Argonaut", "Odyssey", "Nautilus", "Caravel", "Brigantine", "Schooner", "Galley", "Longship", "Cog",
            "Spring", "Summer", "Autumn", "Winter", "Faith", "Charity", "Glory", "Victory", "Liberty", "Freedom",
            "Friendship", "Loyalty", "Courage", "Honour", "Spark", "Flame", "Lightning", "Thunder", "Rainbow", "Cloud",
            "Mist", "Dew", "Frost", "Snowflake", "Blizzard", "Brook", "Spring Water", "Lagoon", "Cove", "Isle",
            "Atoll", "Reef", "Cliff", "Diamond", "Silverfin", "Goldie", "Zephyr", "Ripple", "Speckle", "Snowy",
            "Sandy", "Fidget", "Mischief", "Rascal", "Grumbler", "Quietus", "Jester", "Gentle", "Hercules", "Daredevil",
            "Perch", "Pike", "Carp", "Catfish", "Trout", "Grayling", "Tarpon", "Herring", "Mackerel", "Cod", "Haddock",
            "Bluefin"};
    private static final String[] VESSEL_EPITHET_EN = {"Swift", "Brave", "White", "Blue", "Golden", "Silver", "Sea",
            "Northern", "Wild", "Bold", "Lucky", "Faithful", "Proud", "Merry", "Bright", "Quiet", "Scarlet", "Night",
            "Windy", "Jolly"};
    private static final String[] VESSEL_NOUN_EN = {"Gull", "Swallow", "Star", "Wave", "Maiden", "Fortune", "Dream",
            "Arrow", "Comet", "Mermaid", "Siren", "Pearl", "Rose", "Lily", "Queen", "Duchess", "Heron", "Petrel",
            "Tern", "Swan"};

    public static String vesselName(ServerPlayer builder, Collection<String> taken) {
        return vesselName(russian(builder), taken);
    }

    public static String vesselName(boolean ru, Collection<String> taken) {
        List<String> all = new ArrayList<>(List.of(ru ? VESSEL_RU : VESSEL_EN));
        String[] ep = ru ? VESSEL_EPITHET_RU : VESSEL_EPITHET_EN;
        String[] nouns = ru ? VESSEL_NOUN_RU : VESSEL_NOUN_EN;
        for (String e : ep) for (String n : nouns) all.add(e + " " + n);
        return pick(all, taken);
    }

    // ------------------------------------------------------------------ helpers

    /** Whether a name is written in Cyrillic (names of things belonging to a Russian-named port follow it). */
    public static boolean cyrillic(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.UnicodeBlock.of(name.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        }
        return false;
    }

    private static boolean russian(ServerPlayer player) {
        if (player == null) return false;
        String lang = player.clientInformation().language();
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("ru");
    }

    private static String pick(List<String> all, Collection<String> taken) {
        List<String> free = new ArrayList<>();
        for (String n : all) if (!taken.contains(n)) free.add(n);
        if (!free.isEmpty()) return free.get(ThreadLocalRandom.current().nextInt(free.size()));
        String base = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        for (int i = 2; ; i++) {
            if (!taken.contains(base + " " + i)) return base + " " + i;
        }
    }

    public static int vesselNameCount() {
        return VESSEL_RU.length + VESSEL_EPITHET_RU.length * VESSEL_NOUN_RU.length;
    }
}
