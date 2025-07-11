package org.leng.ChestQuick;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.Location;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChestQuick extends JavaPlugin implements Listener {

    // 固定校验码
    private static final String FIXED_VERIFICATION_CODE = "CQ7X9P2R5M4K6T8W1Y3Z0B4D6F8H0J2L4N6Q8S0U2V";
    private String maskedVerificationCode;

    private List<String> resources;
    private List<String> containerTypes;
    private String successMessage;
    private String fullMessage;
    private String invalidCodeMessage;
    private String hologramText;
    private int hologramDuration;
    private Plugin decentHolograms;
    private boolean useHolograms = false;

    // 胡桃数据相关
    private File hutaoFile;
    private FileConfiguration hutaoConfig;

    // 存储当前活跃的悬浮文字
    private Map<String, Location> activeHolograms = new HashMap<>();

    // 扫描任务ID
    private int scanTaskId = -1;

    private static final Map<String, String> MATERIAL_TRANSLATIONS = new HashMap<String, String>() {{
        put("IRON_INGOT", "铁锭");
        put("GOLD_INGOT", "金锭");
        put("DIAMOND", "钻石");
        put("EMERALD", "绿宝石");
    }};

    @Override
    public void onEnable() {
        // 从配置文件加载校验码
        reloadConfigValues();

        // 校验码验证
        validateVerificationCode();

        getServer().getPluginManager().registerEvents(this, this);

        checkHologramsSupport();

        // 初始化胡桃数据
        setupHutaoConfig();

        // 清理旧的悬浮文字
        clearExistingHolograms();

        // 启动定期扫描任务
        startScanTask();

        getLogger().info("§aChestQuick 插件已启用！");
        getLogger().info("§a插件校验码: " + maskedVerificationCode);
    }

    private void setupHutaoConfig() {
        hutaoFile = new File(getDataFolder(), "hutao.yml");
        if (!hutaoFile.exists()) {
            saveResource("hutao.yml", false);
        }
        hutaoConfig = YamlConfiguration.loadConfiguration(hutaoFile);
    }

    private void saveHutaoConfig() {
        try {
            hutaoConfig.save(hutaoFile);
        } catch (IOException e) {
            getLogger().warning("无法保存hutao.yml文件: " + e.getMessage());
        }
    }

    private void reloadConfigValues() {
        // 从配置文件加载校验码
        String currentCode = getConfig().getString("verification-code");
        if (!FIXED_VERIFICATION_CODE.equals(currentCode)) {
            getLogger().warning("§c配置重载失败：校验码不匹配！");
            return;
        }

        // 生成掩码版本的校验码
        maskedVerificationCode = currentCode.substring(0, 8) + "***" + currentCode.substring(currentCode.length() - 8);

        resources = getConfig().getStringList("resources");
        containerTypes = getConfig().getStringList("container-types");
        successMessage = getConfig().getString("messages.success", "§a已放入 %material% x%amount%");
        fullMessage = getConfig().getString("messages.full", "§c箱子空间不足，无法放入所有物品！");
        invalidCodeMessage = getConfig().getString("messages.invalid_code", "§c[CQ]校验码无效！");
        hologramText = getConfig().getString("hologram.text", "§7左键放入资源");
        hologramDuration = getConfig().getInt("hologram.duration", 60);
        int scanInterval = getConfig().getInt("hologram.scan-interval", 20); // 默认20 ticks (1秒)

        // 清理旧的悬浮文字位置记录
        getConfig().set("hologram.active-holograms", null);
        saveConfig();

        // 重启扫描任务
        restartScanTask(scanInterval);
    }

    private void validateVerificationCode() {
        String currentCode = getConfig().getString("verification-code");
        if (!FIXED_VERIFICATION_CODE.equals(currentCode)) {
            getLogger().severe("§c校验码不匹配！当前校验码: " + currentCode);
            Bukkit.broadcastMessage(invalidCodeMessage);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("§a校验码验证成功！");
    }

    private void checkHologramsSupport() {
        decentHolograms = getServer().getPluginManager().getPlugin("DecentHolograms");
        if (decentHolograms != null) {
            try {
                Class.forName("eu.decentsoftware.holograms.api.DHAPI");
                useHolograms = true;
                getLogger().info("§a检测到 DecentHolograms 插件，已启用悬浮文字功能！");
            } catch (ClassNotFoundException e) {
                getLogger().warning("§cDecentHolograms API未找到，悬浮文字功能将不可用！");
                useHolograms = false;
            }
        } else {
            getLogger().info("§c未检测到 DecentHolograms 插件，悬浮文字功能将不可用！");
        }
    }

    @Override
    public void onDisable() {
        stopScanTask();
        clearExistingHolograms();
        getLogger().info("§cChestQuick 插件已禁用！");
    }

    // 启动定期扫描任务
    private void startScanTask() {
        int scanInterval = getConfig().getInt("hologram.scan-interval", 20);
        if (scanTaskId == -1 && useHolograms) {
            scanTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::scanForContainers, 0, scanInterval);
        }
    }

    // 停止扫描任务
    private void stopScanTask() {
        if (scanTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scanTaskId);
            scanTaskId = -1;
        }
    }

    // 重启扫描任务
    private void restartScanTask(int newInterval) {
        stopScanTask();
        if (useHolograms) {
            scanTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::scanForContainers, 0, newInterval);
        }
    }

    // 扫描所有容器并创建悬浮文字
    private void scanForContainers() {
        if (!useHolograms) return;

        // 为所有在线玩家扫描周围的容器
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanNearbyContainers(player);
        }
    }

    // 扫描玩家附近的容器
    private void scanNearbyContainers(Player player) {
        int radius = getConfig().getInt("hologram.scan-radius", 10); // 默认10格半径

        Location playerLoc = player.getLocation();
        int minX = playerLoc.getBlockX() - radius;
        int maxX = playerLoc.getBlockX() + radius;
        int minY = Math.max(0, playerLoc.getBlockY() - radius);
        int maxY = Math.min(255, playerLoc.getBlockY() + radius);
        int minZ = playerLoc.getBlockZ() - radius;
        int maxZ = playerLoc.getBlockZ() + radius;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location loc = new Location(player.getWorld(), x, y, z);
                    Material blockType = loc.getBlock().getType();

                    if (isContainerType(blockType)) {
                        createHologram(loc.add(0.5, 1.5, 0.5));
                    }
                }
            }
        }
    }

    // 清理所有现有的悬浮文字
    private void clearExistingHolograms() {
        if (!useHolograms) return;

        try {
            Class<?> dhapiClass = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            for (String hologramId : activeHolograms.keySet()) {
                try {
                    dhapiClass.getMethod("removeHologram", String.class)
                            .invoke(null, hologramId);
                } catch (Exception e) {
                    // 忽略错误
                }
            }
            activeHolograms.clear();
        } catch (Exception e) {
            getLogger().warning("清理悬浮文字时出错: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 玩家加入时扫描附近的容器
        if (useHolograms) {
            scanNearbyContainers(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chestquick")) {
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("reload")) {
                    if (sender.hasPermission("chestquick.admin")) {
                        reloadConfig();
                        reloadConfigValues();
                        validateVerificationCode();
                        sender.sendMessage("§a配置已重新加载！");
                        sender.sendMessage("§e校验码: " + maskedVerificationCode);
                    } else {
                        sender.sendMessage("§c你没有权限执行此命令！");
                    }
                    return true;
                } else if (args[0].equalsIgnoreCase("code")) {
                    if (sender.hasPermission("chestquick.admin")) {
                        sender.sendMessage("§e插件校验码: " + maskedVerificationCode);
                    } else {
                        sender.sendMessage("§c你没有权限查看校验码！");
                    }
                    return true;
                } else if (args[0].equalsIgnoreCase("hutao")) {
                    return handleHutaoCommand(sender);
                } else if (args[0].equalsIgnoreCase("rescan")) {
                    if (sender.hasPermission("chestquick.admin")) {
                        if (sender instanceof Player) {
                            scanNearbyContainers((Player) sender);
                            sender.sendMessage("§a已重新扫描附近的容器！");
                        } else {
                            sender.sendMessage("§c只有玩家才能使用此命令！");
                        }
                    }
                    return true;
                }
            }

            String version = getDescription().getVersion();
            sender.sendMessage("§6§lChestQuick " + version);
            sender.sendMessage("§e作者：shazi_awa");
            sender.sendMessage("§e类型：bw附属");
            sender.sendMessage("§e授权于：Mistral Network");
            sender.sendMessage("§e校验码: " + maskedVerificationCode);
            sender.sendMessage("§e使用 /chestquick reload 重载配置");
            sender.sendMessage("§6永远喜欢胡桃的人数: " + hutaoConfig.getInt("likes", 0));
            return true;
        }
        return false;
    }

    private boolean handleHutaoCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家才能使用此命令！");
            return true;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        
        if (hutaoConfig.getStringList("voted").contains(uuid.toString())) {
            player.sendMessage("§a你已经投过票了！胡桃也很喜欢你哦~");
            return true;
        }
        
        List<String> voted = hutaoConfig.getStringList("voted");
        voted.add(uuid.toString());
        hutaoConfig.set("voted", voted);
        
        int likes = hutaoConfig.getInt("likes", 0) + 1;
        hutaoConfig.set("likes", likes);
        
        saveHutaoConfig();
        
        player.sendMessage("§d§l感谢你对胡桃的喜爱！当前喜欢胡桃的人数: " + likes);
        return true;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        org.bukkit.block.Block block = event.getClickedBlock();
        if (block == null) return;

        Material blockType = block.getType();
        if (!isContainerType(blockType)) return;

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getItemInHand();

        if (itemInHand == null || itemInHand.getType() == Material.AIR) return;
        if (!resources.contains(itemInHand.getType().name())) return;

        Inventory containerInventory = getContainerInventory(block, player);
        if (containerInventory == null) return;

        int amount = itemInHand.getAmount();
        boolean allItemsMoved = false;

        for (int i = 0; i < containerInventory.getSize(); i++) {
            ItemStack targetItem = containerInventory.getItem(i);

            if (targetItem == null || targetItem.getType() == Material.AIR) {
                containerInventory.setItem(i, itemInHand.clone());
                allItemsMoved = true;
                break;
            } else if (targetItem.isSimilar(itemInHand)) {
                int freeSpace = targetItem.getMaxStackSize() - targetItem.getAmount();
                if (freeSpace > 0) {
                    int toMove = Math.min(freeSpace, amount);
                    targetItem.setAmount(targetItem.getAmount() + toMove);
                    amount -= toMove;
                    if (amount == 0) {
                        allItemsMoved = true;
                        break;
                    }
                }
            }
        }

        if (allItemsMoved) {
            player.setItemInHand(null);
            String materialName = getMaterialTranslation(itemInHand.getType().name());
            player.sendMessage(successMessage
                .replace("%material%", materialName)
                .replace("%amount%", String.valueOf(itemInHand.getAmount())));

            // 在箱子上方1.5格创建悬浮文字
            createHologram(block.getLocation().add(0.5, 1.5, 0.5));
        } else {
            player.sendMessage(fullMessage);
        }
    }

    private boolean isContainerType(Material material) {
        return containerTypes.contains(material.name());
    }

    private Inventory getContainerInventory(org.bukkit.block.Block block, Player player) {
        Material blockType = block.getType();
        
        if (blockType == Material.ENDER_CHEST) {
            return player.getEnderChest();
        } else {
            BlockState blockState = block.getState();
            if (blockState instanceof Chest) {
                return ((Chest) blockState).getInventory();
            }
        }
        return null;
    }

    private String getMaterialTranslation(String materialName) {
        return MATERIAL_TRANSLATIONS.getOrDefault(materialName, materialName);
    }

    private void createHologram(final Location location) {
        if (!useHolograms) return;

        String hologramId = "chestquick_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();

        // 如果已经存在这个悬浮文字，就不再创建
        if (activeHolograms.containsKey(hologramId)) {
            return;
        }

        try {
            Class<?> dhapiClass = Class.forName("eu.decentsoftware.holograms.api.DHAPI");

            // 创建新悬浮文字
            Object hologram = dhapiClass.getMethod("createHologram", String.class, Location.class, List.class)
                    .invoke(null, hologramId, location, java.util.Arrays.asList(hologramText));

            // 记录活跃的悬浮文字
            activeHolograms.put(hologramId, location);

            if (hologramDuration > 0) {
                Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            dhapiClass.getMethod("removeHologram", String.class)
                                    .invoke(null, hologramId);
                            activeHolograms.remove(hologramId);
                        } catch (Exception e) {
                            getLogger().warning("移除悬浮文字时出错: " + e.getMessage());
                        }
                    }
                }, hologramDuration * 20L);
            }
        } catch (Exception e) {
            getLogger().warning("创建悬浮文字时出错: " + e.getMessage());
        }
    }
}