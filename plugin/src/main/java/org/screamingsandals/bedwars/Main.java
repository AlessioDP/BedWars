package org.screamingsandals.bedwars;

import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.screamingsandals.bedwars.lib.lang.I18n;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.screamingsandals.bedwars.api.BedwarsAPI;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.game.GameStore;
import org.screamingsandals.bedwars.api.statistics.PlayerStatisticsManager;
import org.screamingsandals.bedwars.api.utils.ColorChanger;
import org.screamingsandals.bedwars.commands.*;
import org.screamingsandals.bedwars.config.Configurator;
import org.screamingsandals.bedwars.database.DatabaseManager;
import org.screamingsandals.bedwars.game.Game;
import org.screamingsandals.bedwars.game.GamePlayer;
import org.screamingsandals.bedwars.game.ItemSpawnerType;
import org.screamingsandals.bedwars.game.TeamColor;
import org.screamingsandals.bedwars.holograms.LeaderboardHolograms;
import org.screamingsandals.bedwars.holograms.StatisticsHolograms;
import org.screamingsandals.bedwars.inventories.ShopInventory;
import org.screamingsandals.bedwars.listener.*;
import org.screamingsandals.bedwars.placeholderapi.BedwarsExpansion;
import org.screamingsandals.bedwars.premium.PremiumBedwars;
import org.screamingsandals.bedwars.special.SpecialRegister;
import org.screamingsandals.bedwars.statistics.PlayerStatisticManager;
import org.screamingsandals.bedwars.tab.TabManager;
import org.screamingsandals.bedwars.utils.BedWarsSignOwner;
import org.screamingsandals.bedwars.utils.UpdateChecker;
import org.screamingsandals.bedwars.lib.debug.Debug;
import org.screamingsandals.bedwars.lib.nms.holograms.HologramManager;
import org.screamingsandals.bedwars.lib.nms.utils.ClassStorage;
import org.screamingsandals.bedwars.lib.signmanager.SignListener;
import org.screamingsandals.bedwars.lib.signmanager.SignManager;
import org.screamingsandals.lib.material.MaterialMapping;
import org.screamingsandals.lib.utils.InitUtils;
import org.screamingsandals.simpleinventories.bukkit.SimpleInventoriesBukkit;
import pronze.lib.scoreboards.ScoreboardManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.screamingsandals.bedwars.lib.lang.I18n.i18n;

public class Main extends JavaPlugin implements BedwarsAPI {
    private static Main instance;
    private String version;
    private boolean isDisabling = false;
    private boolean isSpigot, isLegacy;
    private boolean isVault;
    private boolean isNMS;
    private int versionNumber = 0;
    private Economy econ = null;
    private HashMap<String, Game> games = new HashMap<>();
    private HashMap<Player, GamePlayer> playersInGame = new HashMap<>();
    private HashMap<Entity, Game> entitiesInGame = new HashMap<>();
    private Configurator configurator;
    private ShopInventory menu;
    private HashMap<String, ItemSpawnerType> spawnerTypes = new HashMap<>();
    private DatabaseManager databaseManager;
    private PlayerStatisticManager playerStatisticsManager;
    private StatisticsHolograms hologramInteraction;
    private HashMap<String, BaseCommand> commands;
    private ColorChanger colorChanger;
    private SignManager signManager;
    private HologramManager manager;
    private LeaderboardHolograms leaderboardHolograms;
    private TabManager tabManager;
    public static List<String> autoColoredMaterials = new ArrayList<>();
    private Metrics metrics;
    @Getter
    private String buildInfo;
    @Getter
    private final List<Listener> registeredListeners = new ArrayList<>();

    static {
        // ColorChanger list of materials
        autoColoredMaterials.add("WOOL");
        autoColoredMaterials.add("CARPET");
        autoColoredMaterials.add("CONCRETE");
        autoColoredMaterials.add("CONCRETE_POWDER");
        autoColoredMaterials.add("STAINED_CLAY"); // LEGACY ONLY
        autoColoredMaterials.add("TERRACOTTA"); // FLATTENING ONLY
        autoColoredMaterials.add("STAINED_GLASS");
        autoColoredMaterials.add("STAINED_GLASS_PANE");
    }

    public static Main getInstance() {
        return instance;
    }

    public static Configurator getConfigurator() {
        return instance.configurator;
    }

    public static String getVersion() {
        return instance.version;
    }

    public static boolean isSpigot() {
        return instance.isSpigot;
    }

    public static boolean isVault() {
        return instance.isVault;
    }

    public static boolean isLegacy() {
        return instance.isLegacy;
    }

    public static boolean isNMS() {
        return instance.isNMS;
    }

    public static void depositPlayer(Player player, double coins) {
        try {
            if (isVault() && instance.configurator.config.getBoolean("vault.enable")) {
                EconomyResponse response = instance.econ.depositPlayer(player, coins);
                if (response.transactionSuccess()) {
                    player.sendMessage(i18n("vault_deposite").replace("%coins%", Double.toString(coins)).replace(
                            "%currency%",
                            (coins == 1 ? instance.econ.currencyNameSingular() : instance.econ.currencyNamePlural())));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static int getVaultKillReward() {
        return instance.configurator.config.getInt("vault.reward.kill");
    }

    public static int getVaultWinReward() {
        return instance.configurator.config.getInt("vault.reward.win");
    }

    public static Game getGame(String string) {
        return instance.games.get(string);
    }

    public static boolean isGameExists(String string) {
        return instance.games.containsKey(string);
    }

    public static void addGame(Game game) {
        instance.games.put(game.getName(), game);
    }

    public static void removeGame(Game game) {
        instance.games.remove(game.getName());
    }

    public static Game getInGameEntity(Entity entity) {
        return instance.entitiesInGame.getOrDefault(entity, null);
    }

    public static void registerGameEntity(Entity entity, Game game) {
        instance.entitiesInGame.put(entity, game);
    }

    public static void unregisterGameEntity(Entity entity) {
        instance.entitiesInGame.remove(entity);
    }

    public static boolean isPlayerInGame(Player player) {
        if (instance.playersInGame.containsKey(player))
            return instance.playersInGame.get(player).isInGame();
        return false;
    }

    public static GamePlayer getPlayerGameProfile(Player player) {
        if (instance.playersInGame.containsKey(player))
            return instance.playersInGame.get(player);
        GamePlayer gPlayer = new GamePlayer(player);
        instance.playersInGame.put(player, gPlayer);
        return gPlayer;
    }

    public static void unloadPlayerGameProfile(Player player) {
        if (instance.playersInGame.containsKey(player)) {
            instance.playersInGame.get(player).changeGame(null);
            instance.playersInGame.remove(player);
        }
    }

    public static boolean isPlayerGameProfileRegistered(Player player) {
        return instance.playersInGame.containsKey(player);
    }

    public static void sendGameListInfo(CommandSender player) {
        for (Game game : instance.games.values()) {
            player.sendMessage((game.getStatus() == GameStatus.DISABLED ? "§c" : "§a") + game.getName() + "§f "
                    + game.countPlayers());
        }
    }

    public static void openStore(Player player, GameStore store) {
        instance.menu.show(player, store);
    }

    public static boolean isFarmBlock(Material mat) {
        if (instance.configurator.config.getBoolean("farmBlocks.enable")) {
            List<String> list = (List<String>) instance.configurator.config.getList("farmBlocks.blocks");
            return list.contains(mat.name());
        }
        return false;
    }

    public static boolean isBreakableBlock(Material mat) {
        if (instance.configurator.config.getBoolean("breakable.enabled")) {
            List<String> list = (List<String>) instance.configurator.config.getList("breakable.blocks");
            boolean asblacklist = instance.configurator.config.getBoolean("breakable.asblacklist", false);
            return list.contains(mat.name()) ? !asblacklist : asblacklist;
        }
        return false;
    }

    public static boolean isCommandLeaveShortcut(String command) {
        if (instance.configurator.config.getBoolean("leaveshortcuts.enabled")) {
            List<String> commands = (List<String>) instance.configurator.config.getList("leaveshortcuts.list");
            for (String comm : commands) {
                if (!comm.startsWith("/")) {
                    comm = "/" + comm;
                }
                if (comm.equals(command)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isCommandAllowedInGame(String commandPref) {
        if ("/bw".equals(commandPref) || "/bedwars".equals(commandPref)) {
            return true;
        }
        List<String> commands = instance.configurator.config.getStringList("allowed-commands");
        for (String comm : commands) {
            if (!comm.startsWith("/")) {
                comm = "/" + comm;
            }
            if (comm.equals(commandPref)) {
                return !instance.configurator.config.getBoolean("change-allowed-commands-to-blacklist", false);
            }
        }
        return instance.configurator.config.getBoolean("change-allowed-commands-to-blacklist", false);
    }

    public static ItemSpawnerType getSpawnerType(String key) {
        return instance.spawnerTypes.get(key);
    }

    public static List<String> getAllSpawnerTypes() {
        return new ArrayList<>(instance.spawnerTypes.keySet());
    }

    public static List<String> getGameNames() {
        List<String> list = new ArrayList<>();
        for (Game game : instance.games.values()) {
            list.add(game.getName());
        }
        return list;
    }

    public static DatabaseManager getDatabaseManager() {
        return instance.databaseManager;
    }

    public static PlayerStatisticManager getPlayerStatisticsManager() {
        return instance.playerStatisticsManager;
    }

    public static boolean isPlayerStatisticsEnabled() {
        return instance.configurator.config.getBoolean("statistics.enabled");
    }

    public static boolean isHologramsEnabled() {
        return instance.configurator.config.getBoolean("holograms.enabled") && instance.hologramInteraction != null;
    }

    public static StatisticsHolograms getHologramInteraction() {
        return instance.hologramInteraction;
    }

    public static HashMap<String, BaseCommand> getCommands() {
        return instance.commands;
    }

    public static List<Entity> getGameEntities(Game game) {
        List<Entity> entityList = new ArrayList<>();
        for (Map.Entry<Entity, Game> entry : instance.entitiesInGame.entrySet()) {
            if (entry.getValue() == game) {
                entityList.add(entry.getKey());
            }
        }
        return entityList;
    }

    public static int getVersionNumber() {
        return instance.versionNumber;
    }

    public static SignManager getSignManager() {
        return instance.signManager;
    }

    public static HologramManager getHologramManager() {
        return instance.manager;
    }

    public static LeaderboardHolograms getLeaderboardHolograms() {
        return instance.leaderboardHolograms;
    }

    public static TabManager getTabManager() {
        return instance.tabManager;
    }

    public void onEnable() {
        instance = this;
        version = this.getDescription().getVersion();
        var snapshot = version.toLowerCase().contains("pre") || version.toLowerCase().contains("snapshot");
        isNMS = ClassStorage.NMS_BASED_SERVER;
        isSpigot = ClassStorage.IS_SPIGOT_SERVER;
        colorChanger = new org.screamingsandals.bedwars.utils.ColorChanger();

        try {
            var conf = new YamlConfiguration();
            conf.load(new InputStreamReader(Main.class.getResourceAsStream("/build_info.yml")));
            buildInfo = conf.getString("build");
        } catch (Exception exception) {
            buildInfo = "invalid";
        }

        if (!getServer().getPluginManager().isPluginEnabled("Vault")) {
            isVault = false;
        } else {
            isVault = setupEconomy();
        }

        var bukkitVersion = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
        versionNumber = 0;

        for (int i = 0; i < 2; i++) {
            versionNumber += Integer.parseInt(bukkitVersion[i]) * (i == 0 ? 100 : 1);
        }

        isLegacy = versionNumber < 113;

        configurator = new Configurator(this);
        configurator.createFiles();

        Debug.init(getName());
        Debug.setDebug(configurator.config.getBoolean("debug"));

        I18n.load(this, configurator.config.getString("locale"));

        databaseManager = new DatabaseManager(configurator.config.getString("database.host"),
                configurator.config.getInt("database.port"), configurator.config.getString("database.user"),
                configurator.config.getString("database.password"), configurator.config.getString("database.db"),
                configurator.config.getString("database.table-prefix", "bw_"), configurator.config.getBoolean("database.useSSL"));

        if (isPlayerStatisticsEnabled()) {
            playerStatisticsManager = new PlayerStatisticManager();
            playerStatisticsManager.initialize();
        }

        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new BedwarsExpansion().register();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (configurator.config.getBoolean("holograms.enabled")) {
                hologramInteraction = new StatisticsHolograms();
                hologramInteraction.loadHolograms();

                leaderboardHolograms = new LeaderboardHolograms();
                leaderboardHolograms.loadHolograms();
            }
        } catch (Throwable exception) {
            getLogger().severe("Failed to load holograms");
            exception.printStackTrace();
        }

        var partiesEnabled = false;

        if (Main.getConfigurator().config.getBoolean("party.enabled", false)) {
            final var partyPlugin = getServer().getPluginManager().getPlugin("Parties");
            if (partyPlugin != null && partyPlugin.isEnabled()) {
                partiesEnabled = true;
            }
        }

        commands = new HashMap<>();
        new AddholoCommand();
        new AdminCommand();
        new AutojoinCommand();
        new AllJoinCommand();
        new HelpCommand();
        new JoinCommand();
        new LeaveCommand();
        new ListCommand();
        new RejoinCommand();
        new ReloadCommand();
        new RemoveholoCommand();
        new StatsCommand();
        new MainlobbyCommand();
        new LeaderboardCommand();
        new DumpCommand();
        new LanguageCommand();
        if (partiesEnabled)
            new PartyCommand();

        final var cmd = new BwCommandsExecutor();
        getCommand("bw").setExecutor(cmd);
        getCommand("bw").setTabCompleter(cmd);

        registerBedwarsListener(new PlayerListener());
        if (versionNumber >= 109) {
            registerBedwarsListener(new Player19Listener());
        }

        final var playerBeforeOrAfter112Listener = versionNumber >= 122 ? new Player112Listener() : new PlayerBefore112Listener();
        registerBedwarsListener(playerBeforeOrAfter112Listener);

        registerBedwarsListener(new VillagerListener());
        registerBedwarsListener(new WorldListener());
        if (Main.getConfigurator().config.getBoolean("bungee.enabled") && Main.getConfigurator().config.getBoolean("bungee.motd.enabled")) {
            registerBedwarsListener(new BungeeMotdListener());
        }

        if (partiesEnabled) {
            registerBedwarsListener(new PartyListener());
        }

        InitUtils.doIfNot(SimpleInventoriesBukkit::isInitialized, () -> SimpleInventoriesBukkit.init(this));

        this.manager = new HologramManager(this);

        SpecialRegister.onEnable(this);

        PremiumBedwars.init();

        getServer().getServicesManager().register(BedwarsAPI.class, this, this, ServicePriority.Normal);

        for (var spawnerN : configurator.config.getConfigurationSection("resources").getKeys(false)) {

            var name = Main.getConfigurator().config.getString("resources." + spawnerN + ".name");
            var translate = Main.getConfigurator().config.getString("resources." + spawnerN + ".translate");
            var interval = Main.getConfigurator().config.getInt("resources." + spawnerN + ".interval", 1);
            var spread = Main.getConfigurator().config.getDouble("resources." + spawnerN + ".spread");
            var damage = Main.getConfigurator().config.getInt("resources." + spawnerN + ".damage");
            var materialName = Main.getConfigurator().config.getString("resources." + spawnerN + ".material", "AIR");
            var colorName = Main.getConfigurator().config.getString("resources." + spawnerN + ".color", "WHITE");

            if (damage != 0) {
                materialName += ":" + damage;
            }

            var result = MaterialMapping.resolve(materialName).orElse(MaterialMapping.getAir());
            if (result.as(Material.class) == Material.AIR) {
                continue;
            }

            ChatColor color;
            try {
                color = ChatColor.valueOf(colorName.toUpperCase());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                color = ChatColor.WHITE;
            }
            spawnerTypes.put(spawnerN.toLowerCase(), new ItemSpawnerType(spawnerN.toLowerCase(), name, translate,
                    spread, result.as(Material.class), color, interval, result.getDurability()));
        }

        menu = new ShopInventory();

        if (getConfigurator().config.getBoolean("bungee.enabled")) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        }

        Bukkit.getConsoleSender().sendMessage(ChatColor.AQUA + "============" + ChatColor.RED + "===" + ChatColor.WHITE + "======  by ScreamingSandals <Misat11, Ceph, Pronze>");
        Bukkit.getConsoleSender()
                .sendMessage(ChatColor.AQUA + "+ Screaming " + ChatColor.RED + "Bed" + ChatColor.WHITE + "Wars +  " + ChatColor.GOLD + "Version: " + version + " " + (PremiumBedwars.isPremium() ? ChatColor.AQUA + "PREMIUM" : ChatColor.GREEN + "FREE"));
        Bukkit.getConsoleSender()
                .sendMessage(ChatColor.AQUA + "============" + ChatColor.RED + "===" + ChatColor.WHITE + "======  " + (snapshot ? ChatColor.RED + "SNAPSHOT VERSION (" + buildInfo + ") - Use at your own risk" : ChatColor.GREEN + "STABLE VERSION"));
        if (isVault) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[B" + ChatColor.WHITE + "W] " + ChatColor.GOLD + "Found Vault");
        }
        if (!isSpigot) {
            Bukkit.getConsoleSender()
                    .sendMessage(ChatColor.RED + "[B" + ChatColor.WHITE + "W] " + ChatColor.RED + "WARNING: You are not using Spigot. Some features may not work properly.");
        }

        if (versionNumber < 109) {
            Bukkit.getConsoleSender().sendMessage(
                    ChatColor.RED + "[B" + ChatColor.WHITE + "W] " + ChatColor.RED + "IMPORTANT WARNING: You are using version older than 1.9! This version is not officially supported, and some features may not work at all!");
        }

        final var arenasFolder = new File(getDataFolder(), "arenas");
        if (arenasFolder.exists()) {
            try (var stream = Files.walk(Paths.get(arenasFolder.getAbsolutePath()))) {
                final var results = stream.filter(Files::isRegularFile)
                        .map(Path::toString)
                        .collect(Collectors.toList());

                if (results.isEmpty()) {
                    Debug.info("No arenas have been found!", true);
                } else {
                    for (String result : results) {
                        File file = new File(result);
                        if (file.exists() && file.isFile()) {
                            Game.loadGame(file);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace(); // maybe remove after testing
            }
        }

        final var signOwner = new BedWarsSignOwner();
        signManager = new SignManager(signOwner, configurator.signsFile);
        registerBedwarsListener(new SignListener(signOwner, signManager));

        try {
            // Fixing bugs created by third party plugin

            // PerWorldInventory
            if (Bukkit.getPluginManager().isPluginEnabled("PerWorldInventory")) {
                final var pwi = Bukkit.getPluginManager().getPlugin("PerWorldInventory");
                if (pwi.getClass().getName().equals("me.ebonjaeger.perworldinventory.PerWorldInventory")) {
                    // Kotlin version
                    registerBedwarsListener(new PerWorldInventoryKotlinListener());
                } else {
                    // Legacy version
                    registerBedwarsListener(new PerWorldInventoryLegacyListener());
                }
            }

        } catch (Throwable ignored) {
            // maybe something here can cause exception
        }

        if (Main.getConfigurator().config.getBoolean("tab.enable")) {
            tabManager = new TabManager();
        }

        if (Main.getConfigurator().config.getBoolean("update-checker.zero.console")
                || Main.getConfigurator().config.getBoolean("update-checker.zero.oped-players")
        || Main.getConfigurator().config.getBoolean("update-checker.one.console")
                || Main.getConfigurator().config.getBoolean("update-checker.one.oped-players")) {
            UpdateChecker.run();
        }

        /* Initialize our ScoreboardLib*/
        ScoreboardManager.init(this);

        final var pluginId = 7147;
        metrics = new Metrics(this, pluginId);

        Bukkit.getConsoleSender().sendMessage(ChatColor.WHITE + "Everything is loaded! If you like our work, consider visiting our Patreon! <3");
        Bukkit.getConsoleSender().sendMessage(ChatColor.WHITE + "https://www.patreon.com/screamingsandals");
    }

    public void onDisable() {
        isDisabling = true;
        if (signManager != null) {
            signManager.save();
        }
        for (var game : games.values()) {
            game.stop();
        }
        this.getServer().getServicesManager().unregisterAll(this);

        if (isHologramsEnabled() && hologramInteraction != null) {
            hologramInteraction.unloadHolograms();
            leaderboardHolograms.unloadHolograms();
        }

        metrics = null;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }

        econ = rsp.getProvider();
        return true;
    }

    @Override
    public List<org.screamingsandals.bedwars.api.game.Game> getGames() {
        return new ArrayList<>(games.values());
    }

    @Override
    public org.screamingsandals.bedwars.api.game.Game getGameOfPlayer(Player player) {
        return isPlayerInGame(player) ? getPlayerGameProfile(player).getGame() : null;
    }

    @Override
    public boolean isGameWithNameExists(String name) {
        return games.containsKey(name);
    }

    @Override
    public org.screamingsandals.bedwars.api.game.Game getGameByName(String name) {
        return games.get(name);
    }

    @Override
    public org.screamingsandals.bedwars.api.game.Game getGameWithHighestPlayers() {
        TreeMap<Integer, org.screamingsandals.bedwars.api.game.Game> gameList = new TreeMap<>();
        for (org.screamingsandals.bedwars.api.game.Game game : getGames()) {
            if (game.getStatus() != GameStatus.WAITING) {
                continue;
            }
            if (game.getConnectedPlayers().size() >= game.getMaxPlayers()) {
                continue;
            }
            gameList.put(game.countConnectedPlayers(), game);
        }

        Map.Entry<Integer, org.screamingsandals.bedwars.api.game.Game> lastEntry = gameList.lastEntry();
        return lastEntry.getValue();
    }

    @Override
    public org.screamingsandals.bedwars.api.game.Game getGameWithLowestPlayers() {
        TreeMap<Integer, org.screamingsandals.bedwars.api.game.Game> gameList = new TreeMap<>();
        for (org.screamingsandals.bedwars.api.game.Game game : getGames()) {
            if (game.getStatus() != GameStatus.WAITING) {
                continue;
            }
            if (game.getConnectedPlayers().size() >= game.getMaxPlayers()) {
                continue;
            }
            gameList.put(game.countConnectedPlayers(), game);
        }

        Map.Entry<Integer, org.screamingsandals.bedwars.api.game.Game> lastEntry = gameList.firstEntry();
        return lastEntry.getValue();
    }

    @Override
    public List<org.screamingsandals.bedwars.api.game.ItemSpawnerType> getItemSpawnerTypes() {
        return new ArrayList<>(spawnerTypes.values());
    }

    @Override
    public org.screamingsandals.bedwars.api.game.ItemSpawnerType getItemSpawnerTypeByName(String name) {
        return spawnerTypes.get(name);
    }

    @Override
    public boolean isItemSpawnerTypeRegistered(String name) {
        return spawnerTypes.containsKey(name);
    }

    @Override
    public boolean isEntityInGame(Entity entity) {
        return entitiesInGame.containsKey(entity);
    }

    @Override
    public org.screamingsandals.bedwars.api.game.Game getGameOfEntity(Entity entity) {
        return entitiesInGame.get(entity);
    }

    @Override
    public void registerEntityToGame(Entity entity, org.screamingsandals.bedwars.api.game.Game game) {
        if (!(game instanceof Game)) {
            return;
        }
        entitiesInGame.put(entity, (Game) game);
    }

    @Override
    public void unregisterEntityFromGame(Entity entity) {
        if (entitiesInGame.containsKey(entity)) {
            entitiesInGame.remove(entity);
        }
    }

    @Override
    public boolean isPlayerPlayingAnyGame(Player player) {
        return isPlayerInGame(player);
    }

    @Override
    public String getPluginVersion() {
        return version;
    }

    @Override
    public ColorChanger getColorChanger() {
        return colorChanger;
    }

    public static ItemStack applyColor(TeamColor color, ItemStack itemStack) {
        return applyColor(color, itemStack, false);
    }

    public static ItemStack applyColor(TeamColor color, ItemStack itemStack, boolean clone) {
        org.screamingsandals.bedwars.api.TeamColor teamColor = color.toApiColor();
        if (clone) {
            itemStack = itemStack.clone();
        }
        return instance.getColorChanger().applyColor(teamColor, itemStack);
    }

    public static ItemStack applyColor(org.screamingsandals.bedwars.api.TeamColor teamColor, ItemStack itemStack) {
        return instance.getColorChanger().applyColor(teamColor, itemStack);
    }

    @Override
    public org.screamingsandals.bedwars.api.game.Game getFirstWaitingGame() {
        final TreeMap<Integer, Game> availableGames = new TreeMap<>();
        games.values().forEach(game -> {
            if (game.getStatus() != GameStatus.WAITING) {
                return;
            }

            availableGames.put(game.getConnectedPlayers().size(), game);
        });

        if (availableGames.isEmpty()) {
            return null;
        }

        return availableGames.lastEntry().getValue();
    }

    public org.screamingsandals.bedwars.api.game.Game getFirstRunningGame() {
        final TreeMap<Integer, Game> availableGames = new TreeMap<>();
        games.values().forEach(game -> {
            if (game.getStatus() != GameStatus.RUNNING && game.getStatus() != GameStatus.GAME_END_CELEBRATING) {
                return;
            }

            availableGames.put(game.getConnectedPlayers().size(), game);
        });

        if (availableGames.isEmpty()) {
            return null;
        }

        return availableGames.lastEntry().getValue();
    }

    @Override
    public String getHubServerName() {
        return configurator.config.getString("bungee.server");
    }

    @Override
    public PlayerStatisticsManager getStatisticsManager() {
        return isPlayerStatisticsEnabled() ? playerStatisticsManager : null;
    }

    public static boolean isDisabling() {
        return instance.isDisabling;
    }

    public void registerBedwarsListener(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
        registeredListeners.add(listener);
    }
}
