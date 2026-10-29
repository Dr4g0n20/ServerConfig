package serverconfig;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ServerConfig extends JavaPlugin implements Listener {

    private static final String BYPASS = "serverconfig.bypass";

    private static class Limit {
        Material mat;
        int data;   // -1 = any variant
        int max;

        String key() {
            return mat.name() + (data >= 0 ? "@" + data : "");
        }
    }

    // ---- settings loaded from config.yml ----
    private double dmgCapHp;
    private boolean dmgPlayers;
    private boolean dmgMobs;
    private final Map<DamageCause, Double> causeCaps = new HashMap<>();

    private double kbH;
    private double kbV;
    private boolean kbMobs;

    private int potionMax;
    private final Map<PotionEffectType, Integer> potionOverrides = new HashMap<>();

    private int enchMax;
    private final Map<Enchantment, Integer> enchOverrides = new HashMap<>();

    private final List<Limit> limits = new ArrayList<>();

    private double regenDefault;

    // ---- per-player data from data.yml ----
    private final Map<UUID, Double> speedMult = new HashMap<>();
    private final Map<UUID, Double> regenMult = new HashMap<>();
    private final Map<UUID, Double> attackSpeedMap = new HashMap<>();
    private final Map<UUID, Double> maxHealthMap = new HashMap<>();
    private final Map<UUID, Double> armorMap = new HashMap<>();
    private final Set<UUID> exempt = new HashSet<>();
    private File dataFile;

    // ---- runtime only ----
    private final Map<UUID, Long> lastCombatHit = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    private static final Map<String, String> EFFECT_ALIASES = new HashMap<>();
    private static final Map<String, String> ENCHANT_ALIASES = new HashMap<>();

    static {
        EFFECT_ALIASES.put("STRENGTH", "INCREASE_DAMAGE");
        EFFECT_ALIASES.put("HASTE", "FAST_DIGGING");
        EFFECT_ALIASES.put("MINING_FATIGUE", "SLOW_DIGGING");
        EFFECT_ALIASES.put("RESISTANCE", "DAMAGE_RESISTANCE");
        EFFECT_ALIASES.put("JUMP_BOOST", "JUMP");
        EFFECT_ALIASES.put("SLOWNESS", "SLOW");
        EFFECT_ALIASES.put("INSTANT_HEALTH", "HEAL");
        EFFECT_ALIASES.put("INSTANT_DAMAGE", "HARM");
        EFFECT_ALIASES.put("NAUSEA", "CONFUSION");

        ENCHANT_ALIASES.put("SHARPNESS", "DAMAGE_ALL");
        ENCHANT_ALIASES.put("SMITE", "DAMAGE_UNDEAD");
        ENCHANT_ALIASES.put("BANE_OF_ARTHROPODS", "DAMAGE_ARTHROPODS");
        ENCHANT_ALIASES.put("PROTECTION", "PROTECTION_ENVIRONMENTAL");
        ENCHANT_ALIASES.put("FIRE_PROTECTION", "PROTECTION_FIRE");
        ENCHANT_ALIASES.put("BLAST_PROTECTION", "PROTECTION_EXPLOSIONS");
        ENCHANT_ALIASES.put("PROJECTILE_PROTECTION", "PROTECTION_PROJECTILE");
        ENCHANT_ALIASES.put("FEATHER_FALLING", "PROTECTION_FALL");
        ENCHANT_ALIASES.put("EFFICIENCY", "DIG_SPEED");
        ENCHANT_ALIASES.put("UNBREAKING", "DURABILITY");
        ENCHANT_ALIASES.put("FORTUNE", "LOOT_BONUS_BLOCKS");
        ENCHANT_ALIASES.put("LOOTING", "LOOT_BONUS_MOBS");
        ENCHANT_ALIASES.put("POWER", "ARROW_DAMAGE");
        ENCHANT_ALIASES.put("PUNCH", "ARROW_KNOCKBACK");
        ENCHANT_ALIASES.put("FLAME", "ARROW_FIRE");
        ENCHANT_ALIASES.put("INFINITY", "ARROW_INFINITE");
        ENCHANT_ALIASES.put("RESPIRATION", "OXYGEN");
        ENCHANT_ALIASES.put("AQUA_AFFINITY", "WATER_WORKER");
        ENCHANT_ALIASES.put("LUCK_OF_THE_SEA", "LUCK");
    }

    // =====================================================================
    // Startup / config loading
    // =====================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::potionTick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, this::inventoryTick, 20L, 20L);
        for (Player p : getServer().getOnlinePlayers()) {
            applySpeed(p);
        }
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        dmgCapHp = c.getDouble("damage.max-hearts", 6.0) * 2.0;
        dmgPlayers = c.getBoolean("damage.apply-to-players", true);
        dmgMobs = c.getBoolean("damage.apply-to-mobs", false);
        causeCaps.clear();
        ConfigurationSection cs = c.getConfigurationSection("damage.cause-caps-hearts");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    causeCaps.put(DamageCause.valueOf(key.toUpperCase()), cs.getDouble(key) * 2.0);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Unknown damage cause in config: " + key);
                }
            }
        }

        kbH = c.getDouble("knockback.max-horizontal", 1.5);
        kbV = c.getDouble("knockback.max-vertical", 1.0);
        kbMobs = c.getBoolean("knockback.apply-to-mobs", false);

        potionMax = c.getInt("potions.max-level", 10);
        potionOverrides.clear();
        ConfigurationSection ps = c.getConfigurationSection("potions.per-effect");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                PotionEffectType t = PotionEffectType.getByName(key.toUpperCase());
                if (t == null) {
                    getLogger().warning("Unknown potion effect in config: " + key);
                } else {
                    potionOverrides.put(t, ps.getInt(key));
                }
            }
        }

        enchMax = c.getInt("enchants.max-level", 10);
        enchOverrides.clear();
        ConfigurationSection es = c.getConfigurationSection("enchants.per-enchant");
        if (es != null) {
            for (String key : es.getKeys(false)) {
                Enchantment en = Enchantment.getByName(key.toUpperCase());
                if (en == null) {
                    getLogger().warning("Unknown enchantment in config: " + key);
                } else {
                    enchOverrides.put(en, es.getInt(key));
                }
            }
        }

        limits.clear();
        ConfigurationSection ls = c.getConfigurationSection("item-limits");
        if (ls != null) {
            for (String key : ls.getKeys(false)) {
                int max = ls.getInt(key);
                Limit l = parseItem(key, max);
                if (l == null || max < 0) {
                    getLogger().warning("Bad item limit in config: " + key);
                } else {
                    limits.add(l);
                }
            }
        }

        regenDefault = c.getDouble("regen.default-multiplier", 1.0);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        speedMult.clear();
        regenMult.clear();
        readUuidMap(data.getConfigurationSection("speed"), speedMult);
        readUuidMap(data.getConfigurationSection("regen"), regenMult);
        attackSpeedMap.clear();
        maxHealthMap.clear();
        armorMap.clear();
        readUuidMap(data.getConfigurationSection("attackspeed"), attackSpeedMap);
        readUuidMap(data.getConfigurationSection("maxhealth"), maxHealthMap);
        readUuidMap(data.getConfigurationSection("armor"), armorMap);
        exempt.clear();
        for (String id : data.getStringList("exempt")) {
            try {
                exempt.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // skip bad entry
            }
        }
    }

    private void readUuidMap(ConfigurationSection sec, Map<UUID, Double> out) {
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                out.put(UUID.fromString(key), sec.getDouble(key));
            } catch (IllegalArgumentException ignored) {
                // skip bad key
            }
        }
    }

    private void saveData() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : speedMult.entrySet()) {
            data.set("speed." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Double> e : regenMult.entrySet()) {
            data.set("regen." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Double> e : attackSpeedMap.entrySet()) {
            data.set("attackspeed." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Double> e : maxHealthMap.entrySet()) {
            data.set("maxhealth." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Double> e : armorMap.entrySet()) {
            data.set("armor." + e.getKey(), e.getValue());
        }
        List<String> exemptIds = new ArrayList<>();
        for (UUID id : exempt) {
            exemptIds.add(id.toString());
        }
        data.set("exempt", exemptIds);
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // =====================================================================
    // Damage cap
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        boolean isPlayer = victim instanceof Player;

        if (isPlayer && !dmgPlayers) return;
        if (!isPlayer && !dmgMobs) return;
        if (isPlayer && isExempt((Player) victim)) return;

        double cap = dmgCapHp;
        Double causeCap = causeCaps.get(event.getCause());
        if (causeCap != null) cap = causeCap;
        if (cap <= 0) return;

        double finalDamage = event.getFinalDamage();
        if (finalDamage <= cap) return;

        // Scale every modifier by the same ratio so the final damage lands on the cap.
        double ratio = cap / finalDamage;
        for (DamageModifier mod : DamageModifier.values()) {
            if (event.isApplicable(mod)) {
                event.setDamage(mod, event.getDamage(mod) * ratio);
            }
        }
    }

    // =====================================================================
    // Knockback cap
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (kbH <= 0 && kbV <= 0) return;
        final Entity victim = e.getEntity();
        if (victim instanceof Player) {
            lastCombatHit.put(victim.getUniqueId(), System.currentTimeMillis());
        } else if (kbMobs && victim instanceof LivingEntity) {
            getServer().getScheduler().runTask(this, () -> {
                if (!victim.isDead()) {
                    victim.setVelocity(clampVelocity(victim.getVelocity()));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Player p = e.getPlayer();
        if (isExempt(p)) return;
        Long t = lastCombatHit.get(p.getUniqueId());
        // Only touch velocity that comes from a fight, so other plugins' effects still work.
        if (t == null || System.currentTimeMillis() - t > 250L) return;
        e.setVelocity(clampVelocity(e.getVelocity()));
    }

    private Vector clampVelocity(Vector v) {
        Vector r = v.clone();
        if (kbH > 0) {
            double h = Math.sqrt(r.getX() * r.getX() + r.getZ() * r.getZ());
            if (h > kbH) {
                double s = kbH / h;
                r.setX(r.getX() * s);
                r.setZ(r.getZ() * s);
            }
        }
        if (kbV > 0 && r.getY() > kbV) {
            r.setY(kbV);
        }
        return r;
    }

    // =====================================================================
    // Potion level cap
    // =====================================================================

    private int potionLimit(PotionEffectType t) {
        Integer o = potionOverrides.get(t);
        return o != null ? o : potionMax;
    }

    private void enforcePotions(LivingEntity le) {
        for (PotionEffect pe : new ArrayList<>(le.getActivePotionEffects())) {
            int max = potionLimit(pe.getType());
            if (max > 0 && pe.getAmplifier() + 1 > max) {
                le.addPotionEffect(new PotionEffect(pe.getType(), pe.getDuration(), max - 1,
                        pe.isAmbient(), pe.hasParticles()), true);
            }
        }
    }

    private void potionTick() {
        if (potionMax <= 0 && potionOverrides.isEmpty()) return;
        for (Player p : getServer().getOnlinePlayers()) {
            if (!isExempt(p)) enforcePotions(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        final Player p = e.getPlayer();
        if (isExempt(p)) return;
        getServer().getScheduler().runTask(this, () -> {
            if (p.isOnline()) enforcePotions(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        for (LivingEntity le : e.getAffectedEntities()) {
            if (le instanceof Player && !isExempt((Player) le)) {
                final Player p = (Player) le;
                getServer().getScheduler().runTask(this, () -> {
                    if (p.isOnline()) enforcePotions(p);
                });
            }
        }
    }

    // =====================================================================
    // Enchant level cap
    // =====================================================================

    private int enchantLimit(Enchantment en) {
        Integer o = enchOverrides.get(en);
        return o != null ? o : enchMax;
    }

    private boolean enchantCapActive() {
        return enchMax > 0 || !enchOverrides.isEmpty();
    }

    /** Lowers any too-high enchant on the item. Returns true if the item changed. */
    private boolean capItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        boolean changed = false;

        for (Map.Entry<Enchantment, Integer> en : new ArrayList<>(meta.getEnchants().entrySet())) {
            int max = enchantLimit(en.getKey());
            if (max > 0 && en.getValue() > max) {
                meta.addEnchant(en.getKey(), max, true);
                changed = true;
            }
        }
        if (meta instanceof EnchantmentStorageMeta) {
            EnchantmentStorageMeta sm = (EnchantmentStorageMeta) meta;
            for (Map.Entry<Enchantment, Integer> en : new ArrayList<>(sm.getStoredEnchants().entrySet())) {
                int max = enchantLimit(en.getKey());
                if (max > 0 && en.getValue() > max) {
                    sm.addStoredEnchant(en.getKey(), max, true);
                    changed = true;
                }
            }
        }
        if (changed) item.setItemMeta(meta);
        return changed;
    }

    private void scanEnchants(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (capItem(it)) inv.setItem(i, it);
        }
        ItemStack[] armor = inv.getArmorContents();
        boolean armorChanged = false;
        for (ItemStack a : armor) {
            if (capItem(a)) armorChanged = true;
        }
        if (armorChanged) inv.setArmorContents(armor);
        ItemStack off = inv.getItemInOffHand();
        if (capItem(off)) inv.setItemInOffHand(off);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!enchantCapActive()) return;
        for (Map.Entry<Enchantment, Integer> en : e.getEnchantsToAdd().entrySet()) {
            int max = enchantLimit(en.getKey());
            if (max > 0 && en.getValue() > max) {
                en.setValue(max);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        if (!enchantCapActive()) return;
        if (!(e.getPlayer() instanceof Player) || isExempt((Player) e.getPlayer())) return;
        Inventory inv = e.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (capItem(it)) inv.setItem(i, it);
        }
    }

    // =====================================================================
    // Item limits
    // =====================================================================

    private boolean matches(ItemStack it, Limit l) {
        return it != null && it.getType() == l.mat && (l.data < 0 || it.getDurability() == l.data);
    }

    private int countItems(Player p, Limit l) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (matches(it, l)) n += it.getAmount();
        }
        ItemStack off = inv.getItemInOffHand();
        if (matches(off, l)) n += off.getAmount();
        return n;
    }

    private void warn(Player p, Limit l) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 3000L) return;
        lastWarn.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.RED + "You can only carry " + l.max + " of " + friendly(l) + ".");
    }

    private String friendly(Limit l) {
        if (l.mat == Material.GOLDEN_APPLE && l.data == 1) return "enchanted golden apples";
        return l.mat.name().toLowerCase().replace('_', ' ');
    }

    /** Drops any items over the limit at the player's feet. */
    private void enforceLimits(Player p) {
        PlayerInventory inv = p.getInventory();
        for (Limit l : limits) {
            int excess = countItems(p, l) - l.max;
            if (excess <= 0) continue;
            int dropped = 0;

            for (int i = 35; i >= 0 && excess > 0; i--) {
                ItemStack it = inv.getItem(i);
                if (!matches(it, l)) continue;
                int take = Math.min(excess, it.getAmount());
                ItemStack drop = it.clone();
                drop.setAmount(take);
                if (take >= it.getAmount()) {
                    inv.setItem(i, null);
                } else {
                    it.setAmount(it.getAmount() - take);
                    inv.setItem(i, it);
                }
                excess -= take;
                dropped += take;
                Item di = p.getWorld().dropItem(p.getLocation(), drop);
                di.setPickupDelay(100);
            }

            if (excess > 0) {
                ItemStack off = inv.getItemInOffHand();
                if (matches(off, l)) {
                    int take = Math.min(excess, off.getAmount());
                    ItemStack drop = off.clone();
                    drop.setAmount(take);
                    if (take >= off.getAmount()) {
                        inv.setItemInOffHand(null);
                    } else {
                        off.setAmount(off.getAmount() - take);
                        inv.setItemInOffHand(off);
                    }
                    dropped += take;
                    Item di = p.getWorld().dropItem(p.getLocation(), drop);
                    di.setPickupDelay(100);
                }
            }

            if (dropped > 0) warn(p, l);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        Player p = e.getPlayer();
        if (limits.isEmpty() || isExempt(p)) return;
        ItemStack stack = e.getItem().getItemStack();

        for (Limit l : limits) {
            if (!matches(stack, l)) continue;
            int allowed = l.max - countItems(p, l);
            if (allowed <= 0) {
                e.setCancelled(true);
                warn(p, l);
                return;
            }
            if (stack.getAmount() > allowed) {
                // Pick up only what fits under the limit, leave the rest on the ground.
                e.setCancelled(true);
                ItemStack part = stack.clone();
                part.setAmount(allowed);
                Map<Integer, ItemStack> left = p.getInventory().addItem(part);
                int given = allowed;
                for (ItemStack r : left.values()) {
                    given -= r.getAmount();
                }
                if (given > 0) {
                    stack.setAmount(stack.getAmount() - given);
                    e.getItem().setItemStack(stack);
                }
                warn(p, l);
                return;
            }
        }
    }

    // =====================================================================
    // Inventory re-checks (enchants + item limits)
    // =====================================================================

    private void checkPlayer(Player p) {
        if (isExempt(p)) return;
        if (enchantCapActive()) scanEnchants(p);
        if (!limits.isEmpty()) enforceLimits(p);
    }

    private void checkLater(HumanEntity h) {
        if (!(h instanceof Player)) return;
        final Player p = (Player) h;
        getServer().getScheduler().runTask(this, () -> {
            if (p.isOnline()) checkPlayer(p);
        });
    }

    private void inventoryTick() {
        if (!enchantCapActive() && limits.isEmpty()) return;
        for (Player p : getServer().getOnlinePlayers()) {
            checkPlayer(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        checkLater(e.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent e) {
        checkLater(e.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        checkLater(e.getPlayer());
    }

    // =====================================================================
    // Per-player speed and regen
    // =====================================================================

    /** Applies saved attribute edits, then the saved speed multiplier. */
    private void applySpeed(Player p) {
        applyAttributes(p);
        Double m = speedMult.get(p.getUniqueId());
        if (m == null) return;
        p.setWalkSpeed((float) Math.min(1.0, 0.2 * m));
        p.setFlySpeed((float) Math.min(1.0, 0.1 * m));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applySpeed(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        applySpeed(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            applySpeed(p);
            // Respawn at full health when max health has been edited.
            AttributeInstance hp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (hp != null && maxHealthMap.containsKey(p.getUniqueId())) {
                p.setHealth(hp.getValue());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastCombatHit.remove(e.getPlayer().getUniqueId());
        lastWarn.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        RegainReason r = e.getRegainReason();
        if (r != RegainReason.SATIATED && r != RegainReason.REGEN) return;

        Double m = regenMult.get(e.getEntity().getUniqueId());
        double mult = m != null ? m : regenDefault;
        if (mult == 1.0) return;
        if (mult <= 0) {
            e.setCancelled(true);
            return;
        }
        e.setAmount(e.getAmount() * mult);
    }

    private void applyAttributes(Player p) {
        UUID id = p.getUniqueId();
        setAttr(p, Attribute.GENERIC_ATTACK_SPEED, attackSpeedMap.get(id));
        setAttr(p, Attribute.GENERIC_MAX_HEALTH, maxHealthMap.get(id));
        setAttr(p, Attribute.GENERIC_ARMOR, armorMap.get(id));
    }

    private void setAttr(Player p, Attribute attr, Double value) {
        if (value == null) return;
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        try {
            inst.setBaseValue(value);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Could not set " + attr + " for " + p.getName()
                    + " (blocked by a spigot.yml limit?)");
            return;
        }
        clampHealth(p);
    }

    private void clampHealth(Player p) {
        AttributeInstance hp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (hp != null && p.getHealth() > hp.getValue()) {
            p.setHealth(hp.getValue());
        }
    }

    // =====================================================================
    // Commands
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("serverconfig.admin")) {
            s.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (a.length == 0) {
            help(s);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload":
                loadSettings();
                loadData();
                for (Player p : getServer().getOnlinePlayers()) applySpeed(p);
                ok(s, "ServerConfig reloaded.");
                break;
            case "info":
            case "list":
                info(s);
                break;
            case "damage":
                cmdDamage(s, a);
                break;
            case "knockback":
                cmdKnockback(s, a);
                break;
            case "potion":
                cmdPotion(s, a);
                break;
            case "enchant":
                cmdEnchant(s, a);
                break;
            case "limit":
                cmdLimit(s, a);
                break;
            case "speed":
                cmdSpeed(s, a);
                break;
            case "regen":
                cmdRegen(s, a);
                break;
            case "attackspeed":
                cmdAttribute(s, a, "attack speed", Attribute.GENERIC_ATTACK_SPEED, attackSpeedMap, 1.0, false);
                break;
            case "maxhealth":
                cmdAttribute(s, a, "max health", Attribute.GENERIC_MAX_HEALTH, maxHealthMap, 2.0, true);
                break;
            case "armor":
            case "armour":
                cmdAttribute(s, a, "armour", Attribute.GENERIC_ARMOR, armorMap, 1.0, false);
                break;
            case "exempt":
                cmdExempt(s, a);
                break;
            default:
                help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "--- ServerConfig commands (0 = off where it makes sense) ---");
        s.sendMessage(ChatColor.YELLOW + "/sc info");
        s.sendMessage(ChatColor.YELLOW + "/sc reload");
        s.sendMessage(ChatColor.YELLOW + "/sc damage <hearts>");
        s.sendMessage(ChatColor.YELLOW + "/sc damage cause <cause> <hearts>");
        s.sendMessage(ChatColor.YELLOW + "/sc damage players <on|off>   /sc damage mobs <on|off>");
        s.sendMessage(ChatColor.YELLOW + "/sc knockback <horizontal> [vertical]");
        s.sendMessage(ChatColor.YELLOW + "/sc knockback mobs <on|off>");
        s.sendMessage(ChatColor.YELLOW + "/sc potion <effect|all> <level>");
        s.sendMessage(ChatColor.YELLOW + "/sc enchant <enchant|all> <level>");
        s.sendMessage(ChatColor.YELLOW + "/sc limit <item[@data]> <max|off>   /sc limit list");
        s.sendMessage(ChatColor.YELLOW + "/sc speed <player> <multiplier|reset>");
        s.sendMessage(ChatColor.YELLOW + "/sc regen <player|default> <multiplier|reset>");
        s.sendMessage(ChatColor.YELLOW + "/sc attackspeed <player> <value|reset>   (normal = 4)");
        s.sendMessage(ChatColor.YELLOW + "/sc maxhealth <player> <hearts|reset>   (normal = 10)");
        s.sendMessage(ChatColor.YELLOW + "/sc armor <player> <points|reset>   (normal = 0)");
        s.sendMessage(ChatColor.YELLOW + "/sc exempt <player> [on|off]   /sc exempt list");
    }

    private void info(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "--- ServerConfig ---");
        s.sendMessage(ChatColor.YELLOW + "Damage cap: " + hearts(dmgCapHp)
                + " (players: " + dmgPlayers + ", mobs: " + dmgMobs + ")");
        for (Map.Entry<DamageCause, Double> e : causeCaps.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  " + e.getKey() + ": " + hearts(e.getValue()));
        }
        s.sendMessage(ChatColor.YELLOW + "Knockback cap: horizontal " + off(kbH) + ", vertical " + off(kbV)
                + " (mobs: " + kbMobs + ")");
        s.sendMessage(ChatColor.YELLOW + "Potion max level: " + off(potionMax));
        for (Map.Entry<PotionEffectType, Integer> e : potionOverrides.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  " + e.getKey().getName() + ": " + e.getValue());
        }
        s.sendMessage(ChatColor.YELLOW + "Enchant max level: " + off(enchMax));
        for (Map.Entry<Enchantment, Integer> e : enchOverrides.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  " + e.getKey().getName() + ": " + e.getValue());
        }
        s.sendMessage(ChatColor.YELLOW + "Item limits: " + limits.size() + " (use /sc limit list)");
        s.sendMessage(ChatColor.YELLOW + "Default regen multiplier: " + fmt(regenDefault));
        s.sendMessage(ChatColor.YELLOW + "Exempt players: " + exempt.size() + " (use /sc exempt list)");
        for (Map.Entry<UUID, Double> e : attackSpeedMap.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  attack speed " + name(e.getKey()) + ": " + fmt(e.getValue()));
        }
        for (Map.Entry<UUID, Double> e : maxHealthMap.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  max health " + name(e.getKey()) + ": " + fmt(e.getValue() / 2.0) + " hearts");
        }
        for (Map.Entry<UUID, Double> e : armorMap.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  armour " + name(e.getKey()) + ": " + fmt(e.getValue()));
        }
        for (Map.Entry<UUID, Double> e : speedMult.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  speed " + name(e.getKey()) + ": x" + fmt(e.getValue()));
        }
        for (Map.Entry<UUID, Double> e : regenMult.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  regen " + name(e.getKey()) + ": x" + fmt(e.getValue()));
        }
    }

    private void cmdDamage(CommandSender s, String[] a) {
        if (a.length == 2) {
            Double v = num(a[1]);
            if (v == null) {
                bad(s);
                return;
            }
            setConfig("damage.max-hearts", v);
            ok(s, "Max damage per hit is now " + (v <= 0 ? "unlimited" : fmt(v) + " hearts") + ".");
        } else if (a.length == 4 && a[1].equalsIgnoreCase("cause")) {
            DamageCause cause;
            try {
                cause = DamageCause.valueOf(a[2].toUpperCase());
            } catch (IllegalArgumentException e) {
                s.sendMessage(ChatColor.RED + "Unknown cause. Examples: ENTITY_ATTACK, PROJECTILE, FALL, "
                        + "ENTITY_EXPLOSION, BLOCK_EXPLOSION, FALLING_BLOCK, LIGHTNING, MAGIC, FIRE, LAVA");
                return;
            }
            Double v = num(a[3]);
            if (v == null) {
                bad(s);
                return;
            }
            setConfig("damage.cause-caps-hearts." + cause.name(), v <= 0 ? null : v);
            ok(s, cause.name() + " cap is now " + (v <= 0 ? "the default" : fmt(v) + " hearts") + ".");
        } else if (a.length == 3 && (a[1].equalsIgnoreCase("players") || a[1].equalsIgnoreCase("mobs"))) {
            Boolean b = bool(a[2]);
            if (b == null) {
                s.sendMessage(ChatColor.RED + "Use on or off.");
                return;
            }
            setConfig("damage.apply-to-" + a[1].toLowerCase(), b);
            ok(s, "Damage cap for " + a[1].toLowerCase() + ": " + (b ? "on" : "off") + ".");
        } else {
            help(s);
        }
    }

    private void cmdKnockback(CommandSender s, String[] a) {
        if (a.length == 3 && a[1].equalsIgnoreCase("mobs")) {
            Boolean b = bool(a[2]);
            if (b == null) {
                s.sendMessage(ChatColor.RED + "Use on or off.");
                return;
            }
            setConfig("knockback.apply-to-mobs", b);
            ok(s, "Knockback cap for mobs: " + (b ? "on" : "off") + ".");
            return;
        }
        if (a.length < 2 || a.length > 3) {
            help(s);
            return;
        }
        Double h = num(a[1]);
        Double v = a.length == 3 ? num(a[2]) : Double.valueOf(kbV);
        if (h == null || v == null) {
            bad(s);
            return;
        }
        setConfig("knockback.max-horizontal", h);
        setConfig("knockback.max-vertical", v);
        ok(s, "Knockback cap: horizontal " + off(h) + ", vertical " + off(v) + ".");
    }

    private void cmdPotion(CommandSender s, String[] a) {
        if (a.length != 3) {
            help(s);
            return;
        }
        Integer lvl = whole(a[2]);
        if (lvl == null) {
            bad(s);
            return;
        }
        if (a[1].equalsIgnoreCase("all")) {
            setConfig("potions.max-level", lvl);
            ok(s, "Potion max level for all effects: " + off(lvl) + ".");
            return;
        }
        PotionEffectType t = effectByName(a[1]);
        if (t == null) {
            s.sendMessage(ChatColor.RED + "Unknown effect. Try STRENGTH, SPEED, REGENERATION, JUMP_BOOST...");
            return;
        }
        setConfig("potions.per-effect." + t.getName(), lvl <= 0 ? null : lvl);
        ok(s, t.getName() + " max level: " + (lvl <= 0 ? "the default" : String.valueOf(lvl)) + ".");
    }

    private void cmdEnchant(CommandSender s, String[] a) {
        if (a.length != 3) {
            help(s);
            return;
        }
        Integer lvl = whole(a[2]);
        if (lvl == null) {
            bad(s);
            return;
        }
        if (a[1].equalsIgnoreCase("all")) {
            setConfig("enchants.max-level", lvl);
            ok(s, "Enchant max level for all enchantments: " + off(lvl) + ".");
            return;
        }
        Enchantment en = enchantByName(a[1]);
        if (en == null) {
            s.sendMessage(ChatColor.RED + "Unknown enchantment. Try SHARPNESS, PROTECTION, UNBREAKING, KNOCKBACK...");
            return;
        }
        setConfig("enchants.per-enchant." + en.getName(), lvl <= 0 ? null : lvl);
        ok(s, en.getName() + " max level: " + (lvl <= 0 ? "the default" : String.valueOf(lvl)) + ".");
    }

    private void cmdLimit(CommandSender s, String[] a) {
        if (a.length == 2 && a[1].equalsIgnoreCase("list")) {
            if (limits.isEmpty()) {
                s.sendMessage(ChatColor.YELLOW + "No item limits set.");
            }
            for (Limit l : limits) {
                s.sendMessage(ChatColor.YELLOW + l.key() + ": " + l.max);
            }
            return;
        }
        if (a.length != 3) {
            help(s);
            return;
        }
        Limit l = parseItem(a[1], 0);
        if (l == null) {
            s.sendMessage(ChatColor.RED + "Unknown item. Use a material name like ENDER_PEARL, or "
                    + "MATERIAL@DATA (enchanted golden apple = GOLDEN_APPLE@1).");
            return;
        }
        if (a[2].equalsIgnoreCase("off")) {
            setConfig("item-limits." + l.key(), null);
            ok(s, "Removed the limit for " + l.key() + ".");
            return;
        }
        Integer max = whole(a[2]);
        if (max == null) {
            bad(s);
            return;
        }
        setConfig("item-limits." + l.key(), max);
        ok(s, "Players can now carry at most " + max + " of " + l.key() + ".");
    }

    private void cmdSpeed(CommandSender s, String[] a) {
        if (a.length != 3) {
            help(s);
            return;
        }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) {
            s.sendMessage(ChatColor.RED + "That player must be online.");
            return;
        }
        if (a[2].equalsIgnoreCase("reset")) {
            speedMult.remove(p.getUniqueId());
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
            saveData();
            ok(s, p.getName() + "'s speed is back to normal.");
            return;
        }
        Double m = num(a[2]);
        if (m == null) {
            bad(s);
            return;
        }
        speedMult.put(p.getUniqueId(), m);
        applySpeed(p);
        saveData();
        ok(s, p.getName() + "'s speed is now x" + fmt(m) + " (maximum effect is x5).");
    }

    private void cmdRegen(CommandSender s, String[] a) {
        if (a.length != 3) {
            help(s);
            return;
        }
        if (a[1].equalsIgnoreCase("default")) {
            Double m = num(a[2]);
            if (m == null) {
                bad(s);
                return;
            }
            setConfig("regen.default-multiplier", m);
            ok(s, "Default regen multiplier is now x" + fmt(m) + ".");
            return;
        }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) {
            s.sendMessage(ChatColor.RED + "That player must be online.");
            return;
        }
        if (a[2].equalsIgnoreCase("reset")) {
            regenMult.remove(p.getUniqueId());
            saveData();
            ok(s, p.getName() + " now uses the default regen.");
            return;
        }
        Double m = num(a[2]);
        if (m == null) {
            bad(s);
            return;
        }
        regenMult.put(p.getUniqueId(), m);
        saveData();
        ok(s, p.getName() + "'s regen is now x" + fmt(m) + ".");
    }

    /** Shared handler for /sc attackspeed, maxhealth and armor. factor converts typed units to attribute units. */
    private void cmdAttribute(CommandSender s, String[] a, String what, Attribute attr,
                              Map<UUID, Double> map, double factor, boolean mustBePositive) {
        if (a.length != 3) {
            help(s);
            return;
        }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) {
            s.sendMessage(ChatColor.RED + "That player must be online.");
            return;
        }
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) {
            s.sendMessage(ChatColor.RED + "Can't edit " + what + " for that player.");
            return;
        }
        if (a[2].equalsIgnoreCase("reset")) {
            map.remove(p.getUniqueId());
            try {
                inst.setBaseValue(inst.getDefaultValue());
            } catch (IllegalArgumentException ignored) {
                // leave as is
            }
            clampHealth(p);
            saveData();
            ok(s, p.getName() + "'s " + what + " is back to normal.");
            return;
        }
        Double v = num(a[2]);
        if (v == null || (mustBePositive && v <= 0)) {
            bad(s);
            return;
        }
        double raw = v * factor;
        try {
            inst.setBaseValue(raw);
        } catch (IllegalArgumentException e) {
            s.sendMessage(ChatColor.RED + "The server won't allow that value. Spigot has its own upper limits "
                    + "in spigot.yml (attribute section). Try a lower number.");
            return;
        }
        map.put(p.getUniqueId(), raw);
        clampHealth(p);
        saveData();
        ok(s, p.getName() + "'s " + what + " is now " + fmt(v) + ".");
    }

    private void cmdExempt(CommandSender s, String[] a) {
        if (a.length == 2 && a[1].equalsIgnoreCase("list")) {
            if (exempt.isEmpty()) {
                s.sendMessage(ChatColor.YELLOW + "No players are exempt.");
            }
            for (UUID id : exempt) {
                s.sendMessage(ChatColor.YELLOW + "  " + name(id));
            }
            return;
        }
        if (a.length < 2 || a.length > 3) {
            help(s);
            return;
        }
        UUID id;
        String shown;
        Player online = Bukkit.getPlayerExact(a[1]);
        if (online != null) {
            id = online.getUniqueId();
            shown = online.getName();
        } else {
            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(a[1]);
            if (!off.hasPlayedBefore()) {
                s.sendMessage(ChatColor.RED + "I don't know a player called " + a[1] + ".");
                return;
            }
            id = off.getUniqueId();
            shown = off.getName() != null ? off.getName() : a[1];
        }
        Boolean want;
        if (a.length == 3) {
            want = bool(a[2]);
            if (want == null) {
                s.sendMessage(ChatColor.RED + "Use on or off.");
                return;
            }
        } else {
            want = !exempt.contains(id);   // no on/off given: toggle
        }
        if (want) {
            exempt.add(id);
        } else {
            exempt.remove(id);
        }
        saveData();
        ok(s, shown + " is now " + (want ? "exempt from" : "affected by") + " all caps and limits.");
    }

    // =====================================================================
    // Tab completion
    // =====================================================================

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("serverconfig.admin")) return out;
        if (a.length == 1) {
            add(out, a[0], "info", "reload", "damage", "knockback", "potion", "enchant", "limit", "speed", "regen", "attackspeed", "maxhealth", "armor", "exempt");
        } else if (a.length == 2) {
            switch (a[0].toLowerCase()) {
                case "damage":
                    add(out, a[1], "cause", "players", "mobs");
                    break;
                case "knockback":
                    add(out, a[1], "mobs");
                    break;
                case "potion":
                    add(out, a[1], "all");
                    for (PotionEffectType t : PotionEffectType.values()) {
                        if (t != null) add(out, a[1], t.getName());
                    }
                    break;
                case "enchant":
                    add(out, a[1], "all");
                    for (Enchantment en : Enchantment.values()) {
                        add(out, a[1], en.getName());
                    }
                    break;
                case "limit":
                    add(out, a[1], "list", "GOLDEN_APPLE@1", "ENDER_PEARL", "TNT", "TOTEM");
                    break;
                case "exempt":
                    add(out, a[1], "list");
                    for (Player p : getServer().getOnlinePlayers()) {
                        add(out, a[1], p.getName());
                    }
                    break;
                case "attackspeed":
                case "maxhealth":
                case "armor":
                case "armour":
                    for (Player p : getServer().getOnlinePlayers()) {
                        add(out, a[1], p.getName());
                    }
                    break;
                case "speed":
                case "regen":
                    if (a[0].equalsIgnoreCase("regen")) add(out, a[1], "default");
                    for (Player p : getServer().getOnlinePlayers()) {
                        add(out, a[1], p.getName());
                    }
                    break;
                default:
                    break;
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase()) {
                case "speed":
                case "regen":
                case "attackspeed":
                case "maxhealth":
                case "armor":
                case "armour":
                    add(out, a[2], "reset");
                    break;
                case "limit":
                    add(out, a[2], "off");
                    break;
                case "exempt":
                    add(out, a[2], "on", "off");
                    break;
                case "damage":
                    if (a[1].equalsIgnoreCase("cause")) {
                        for (DamageCause c : DamageCause.values()) add(out, a[2], c.name());
                    } else {
                        add(out, a[2], "on", "off");
                    }
                    break;
                case "knockback":
                    add(out, a[2], "on", "off");
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private void add(List<String> out, String typed, String... options) {
        for (String o : options) {
            if (o.toLowerCase().startsWith(typed.toLowerCase())) out.add(o);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** True if the player has the bypass permission or was exempted with /sc exempt. */
    private boolean isExempt(Player p) {
        return p.hasPermission(BYPASS) || exempt.contains(p.getUniqueId());
    }

    private void setConfig(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        loadSettings();
    }

    private void ok(CommandSender s, String msg) {
        s.sendMessage(ChatColor.GREEN + msg);
    }

    private void bad(CommandSender s) {
        s.sendMessage(ChatColor.RED + "That's not a valid number (it must be 0 or higher).");
    }

    private Double num(String str) {
        try {
            double d = Double.parseDouble(str);
            if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) return null;
            return d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer whole(String str) {
        try {
            int i = Integer.parseInt(str);
            return i < 0 ? null : i;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean bool(String str) {
        switch (str.toLowerCase()) {
            case "on":
            case "true":
            case "yes":
                return Boolean.TRUE;
            case "off":
            case "false":
            case "no":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private String fmt(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private String off(double d) {
        return d <= 0 ? "off" : fmt(d);
    }

    private String hearts(double hp) {
        return hp <= 0 ? "off" : fmt(hp / 2.0) + " hearts";
    }

    private String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString();
    }

    private PotionEffectType effectByName(String in) {
        String up = in.toUpperCase();
        String real = EFFECT_ALIASES.get(up);
        return PotionEffectType.getByName(real != null ? real : up);
    }

    private Enchantment enchantByName(String in) {
        String up = in.toUpperCase();
        String real = ENCHANT_ALIASES.get(up);
        return Enchantment.getByName(real != null ? real : up);
    }

    /** Parses "MATERIAL" or "MATERIAL@DATA" (plus a few friendly names). Returns null if unknown. */
    private Limit parseItem(String in, int max) {
        String up = in.toUpperCase().replace(' ', '_');
        if (up.equals("ENCHANTED_GOLDEN_APPLE") || up.equals("EGAPPLE") || up.equals("NOTCH_APPLE")) {
            up = "GOLDEN_APPLE@1";
        }
        String matName = up;
        int data = -1;
        int at = up.indexOf('@');
        if (at >= 0) {
            matName = up.substring(0, at);
            try {
                data = Integer.parseInt(up.substring(at + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Material m = Material.matchMaterial(matName);
        if (m == null) return null;
        Limit l = new Limit();
        l.mat = m;
        l.data = data;
        l.max = max;
        return l;
    }
}
