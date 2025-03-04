package com.hibiscusmc.hmccosmetics.user;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.api.events.*;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.config.Wardrobe;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticArmorType;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBackpackType;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticBalloonType;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticMainhandType;
import com.hibiscusmc.hmccosmetics.database.UserData;
import com.hibiscusmc.hmccosmetics.gui.Menus;
import com.hibiscusmc.hmccosmetics.user.manager.UserBackpackManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserBalloonManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserEmoteManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserWardrobeManager;
import com.hibiscusmc.hmccosmetics.util.HMCCInventoryUtils;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.util.InventoryUtils;
import me.lojosho.hibiscuscommons.util.packets.PacketManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;

public class CosmeticUser implements CosmeticHolder {
    @Getter
    private final UUID uniqueId;
    private int taskId = -1;
    private final HashMap<CosmeticSlot, Cosmetic> playerCosmetics = new HashMap<>();
    private UserWardrobeManager userWardrobeManager;
    private UserBalloonManager userBalloonManager;
    @Getter
    private UserBackpackManager userBackpackManager;
    @Getter
    private final UserEmoteManager userEmoteManager;

    // Cosmetic Settings/Toggles
    private final ArrayList<HiddenReason> hiddenReason = new ArrayList<>();
    private final HashMap<CosmeticSlot, Color> colors = new HashMap<>();

    /**
     * Use {@link #CosmeticUser(UUID)} instead and use {@link #initialize(UserData)} to populate the user with data.
     * @param uuid
     * @param data
     */
    @Deprecated(forRemoval = true, since = "2.7.5")
    public CosmeticUser(UUID uuid, UserData data) {
        this(uuid);
        initialize(data);
    }

    public CosmeticUser(@NotNull UUID uuid) {
        this.uniqueId = uuid;
        this.userEmoteManager = new UserEmoteManager(this);
    }

    /**
     * Initialize the {@link CosmeticUser}.
     * @param userData the associated {@link UserData}
     * @return the {@link CosmeticUser}
     * @apiNote Initialize is called after {@link CosmeticUserProvider#createCosmeticUser(UUID)} so it is possible to
     * populate an extending version of {@link CosmeticUser} with data then override this method to apply your
     * own state.
     */
    public CosmeticUser initialize(final @Nullable UserData userData) {
        if(userData != null) {
            // CosmeticSlot -> Entry<Cosmetic, Integer>
            for(final Map.Entry<CosmeticSlot, Map.Entry<Cosmetic, Integer>> entry : userData.getCosmetics().entrySet()) {
                final Cosmetic cosmetic = entry.getValue().getKey();
                final Integer colorRGBInt = entry.getValue().getValue();

                if (!this.canApplyCosmetic(cosmetic)) {
                    MessagesUtil.sendDebugMessages("Cannot apply cosmetic[id=" + cosmetic.getId() + "]");
                    continue;
                }

                Color color = null;
                if (colorRGBInt != -1) color = Color.fromRGB(colorRGBInt); // -1 is defined as no color; anything else is a color

                this.addPlayerCosmetic(cosmetic, color);
            }
            this.applyHiddenState(userData.getHiddenReasons());
        }

        return this;
    }

    /**
     * This method is only called from {@link #initialize(UserData)} and can't be called directly.
     * This is used to help hooking plugins apply custom logic to the user.
     */
    protected boolean applyCosmetic(@NotNull Cosmetic cosmetic, @Nullable Color color) {
        this.addPlayerCosmetic(cosmetic, color);
        return true;
    }

    /**
     * This method is only called from {@link #initialize(UserData)} and can't be called directly.
     * This is used to help hooking plugins apply custom logic to the user.
     */
    protected boolean canApplyCosmetic(@NotNull Cosmetic cosmetic) {
        return canEquipCosmetic(cosmetic, false);
    }

    /**
     * This method is only called from {@link #initialize(UserData)} and can't be called directly.
     * This is used to help hooking plugins apply custom logic to the user.
     */
    protected void applyHiddenState(@NotNull List<HiddenReason> hiddenReasons) {
        if(!hiddenReason.isEmpty()) {
            for(final HiddenReason reason : this.hiddenReason) {
                this.silentlyAddHideFlag(reason);
            }
            return;
        }

        Player bukkitPlayer = getPlayer();
        if (bukkitPlayer != null && Settings.isDisabledGamemodesEnabled() && Settings.getDisabledGamemodes().contains(bukkitPlayer.getGameMode().toString())) {
            MessagesUtil.sendDebugMessages("Hiding cosmetics due to gamemode");
            hideCosmetics(HiddenReason.GAMEMODE);
        } else if (this.isHidden(HiddenReason.GAMEMODE)) {
            MessagesUtil.sendDebugMessages("Showing cosmetics for gamemode");
            showCosmetics(HiddenReason.GAMEMODE);
        }

        if (bukkitPlayer != null && Settings.getDisabledWorlds().contains(getEntity().getLocation().getWorld().getName())) {
            MessagesUtil.sendDebugMessages("Hiding Cosmetics due to world");
            hideCosmetics(CosmeticUser.HiddenReason.WORLD);
        } else if (this.isHidden(HiddenReason.WORLD)) {
            MessagesUtil.sendDebugMessages("Showing Cosmetics due to world");
            showCosmetics(HiddenReason.WORLD);
        }
        if (Settings.isAllPlayersHidden()) {
            hideCosmetics(HiddenReason.DISABLED);
        }

        for (final HiddenReason reason : hiddenReasons) {
            this.silentlyAddHideFlag(reason);
        }
    }

    /**
     * Start ticking against the {@link CosmeticUser}.
     * @implNote The tick-rate is determined by the tick period specified in the configuration, if it is less-than or equal to 0
     * there will be no {@link BukkitTask} created, and the {@link CosmeticUser#taskId} will be -1
     */
    public final void startTicking() {
        int tickPeriod = Settings.getTickPeriod();
        if(tickPeriod <= 0) {
            MessagesUtil.sendDebugMessages("CosmeticUser tick is disabled.");
            return;
        }

        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(HMCCosmeticsPlugin.getInstance(), this::tick, 0, tickPeriod);
        this.taskId = task.getTaskId();
    }

    /**
     * Dispatch an operation to happen against this {@link CosmeticUser}
     * at a pre-determined tick-rate.
     * The tick-rate is determined by the tick period specified in the configuration.
     */
    protected void tick() {
        MessagesUtil.sendDebugMessages("Tick[uuid=" + uniqueId + "]", Level.INFO);

        if (Hooks.isInvisible(uniqueId)) {
            this.hideCosmetics(HiddenReason.VANISH);
        } else {
            this.showCosmetics(HiddenReason.VANISH);
        }

        this.updateCosmetic();

        if(isHidden() && !getUserEmoteManager().isPlayingEmote() && !getCosmetics().isEmpty()) {
            MessagesUtil.sendActionBar(getPlayer(), "hidden-cosmetics");
        }
    }

    public void destroy() {
        if(this.taskId != -1) { // ensure we're actually ticking this user.
            Bukkit.getScheduler().cancelTask(taskId);
        }

        despawnBackpack();
        despawnBalloon();
    }

    @Override
    public Cosmetic getCosmetic(@NotNull CosmeticSlot slot) {
        return playerCosmetics.get(slot);
    }

    @Override
    public @NotNull ImmutableCollection<Cosmetic> getCosmetics() {
        return ImmutableList.copyOf(playerCosmetics.values());
    }

    @Override
    public void addCosmetic(@NotNull Cosmetic cosmetic, @Nullable Color color) {
        // API
        PlayerCosmeticEquipEvent event = new PlayerCosmeticEquipEvent(this, cosmetic);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        cosmetic = event.getCosmetic();
        // Internal
        if (playerCosmetics.containsKey(cosmetic.getSlot())) {
            removeCosmeticSlot(cosmetic.getSlot());
        }

        playerCosmetics.put(cosmetic.getSlot(), cosmetic);
        if (color != null) colors.put(cosmetic.getSlot(), color);
        MessagesUtil.sendDebugMessages("addPlayerCosmetic[id=" + cosmetic.getId() + "]");
        if (!isHidden()) {
            if (cosmetic.getSlot() == CosmeticSlot.BACKPACK) {
                CosmeticBackpackType backpackType = (CosmeticBackpackType) cosmetic;
                spawnBackpack(backpackType);
                MessagesUtil.sendDebugMessages("addPlayerCosmetic[spawnBackpack,id=" + cosmetic.getId() + "]");
            }
            if (cosmetic.getSlot() == CosmeticSlot.BALLOON) {
                CosmeticBalloonType balloonType = (CosmeticBalloonType) cosmetic;
                spawnBalloon(balloonType);
            }
        }
        // API
        PlayerCosmeticPostEquipEvent postEquipEvent = new PlayerCosmeticPostEquipEvent(this, cosmetic);
        Bukkit.getPluginManager().callEvent(postEquipEvent);
    }

    /**
     * @deprecated Use {@link #addCosmetic(Cosmetic)} instead
     */
    @Deprecated
    public void addPlayerCosmetic(@NotNull Cosmetic cosmetic) {
        addCosmetic(cosmetic);
    }

    /**
     * @deprecated Use {@link #addCosmetic(Cosmetic, Color)} instead
     */
    @Deprecated
    public void addPlayerCosmetic(@NotNull Cosmetic cosmetic, @Nullable Color color) {
        addCosmetic(cosmetic, color);
    }

    @Override
    public void removeCosmeticSlot(@NotNull CosmeticSlot slot) {
        // API
        PlayerCosmeticRemoveEvent event = new PlayerCosmeticRemoveEvent(this, getCosmetic(slot));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        // Internal
        if (slot == CosmeticSlot.BACKPACK) {
            despawnBackpack();
        }
        if (slot == CosmeticSlot.BALLOON) {
            despawnBalloon();
        }
        if (slot == CosmeticSlot.EMOTE) {
            if (getUserEmoteManager().isPlayingEmote()) getUserEmoteManager().stopEmote(UserEmoteManager.StopEmoteReason.UNEQUIP);
        }
        colors.remove(slot);
        playerCosmetics.remove(slot);
        removeArmor(slot);
    }

    @Override
    public boolean hasCosmeticInSlot(@NotNull CosmeticSlot slot) {
        return playerCosmetics.containsKey(slot);
    }

    public Set<CosmeticSlot> getSlotsWithCosmetics() {
        return Set.copyOf(playerCosmetics.keySet());
    }

    @Override
    public void updateCosmetic(@NotNull CosmeticSlot slot) {
        if (getCosmetic(slot) == null) {
            return;
        }
        getCosmetic(slot).update(this);
        return;
    }

    public void updateCosmetic(Cosmetic cosmetic) {
        updateCosmetic(cosmetic.getSlot());
    }

    public void updateCosmetic() {
        MessagesUtil.sendDebugMessages("updateCosmetic (All) - start");
        HashMap<EquipmentSlot, ItemStack> items = new HashMap<>();

        for (Cosmetic cosmetic : getCosmetics()) {
            if (cosmetic instanceof CosmeticArmorType armorType) {
                if (getUserEmoteManager().isPlayingEmote() || isInWardrobe()) return;
                if (!(getEntity() instanceof HumanEntity humanEntity)) return;

                boolean requireEmpty = Settings.getSlotOption(armorType.getEquipSlot()).isRequireEmpty();
                boolean isAir = humanEntity.getInventory().getItem(armorType.getEquipSlot()).getType().isAir();
                MessagesUtil.sendDebugMessages("updateCosmetic (All) - " + armorType.getId() + " - " + requireEmpty + " - " + isAir);
                if (requireEmpty && !isAir) continue;

                items.put(HMCCInventoryUtils.getEquipmentSlot(armorType.getSlot()), armorType.getItem(this));
            } else {
                updateCosmetic(cosmetic.getSlot());
            }
        }
        if (items.isEmpty() || getEntity() == null) return;
        PacketManager.equipmentSlotUpdate(getEntity().getEntityId(), items, HMCCPacketManager.getViewers(getEntity().getLocation()));
        MessagesUtil.sendDebugMessages("updateCosmetic (All) - end - " + items.size());
    }

    public ItemStack getUserCosmeticItem(CosmeticSlot slot) {
        Cosmetic cosmetic = getCosmetic(slot);
        if (cosmetic == null) return new ItemStack(Material.AIR);
        return getUserCosmeticItem(cosmetic);
    }

    public ItemStack getUserCosmeticItem(@NotNull Cosmetic cosmetic) {
        ItemStack item = null;
        if (!hiddenReason.isEmpty()) {
            if (cosmetic instanceof CosmeticBackpackType || cosmetic instanceof CosmeticBalloonType) return new ItemStack(Material.AIR);
            return getPlayer().getInventory().getItem(HMCCInventoryUtils.getEquipmentSlot(cosmetic.getSlot()));
        }
        if (cosmetic instanceof CosmeticArmorType armorType) {
            item = armorType.getItem(this, cosmetic.getItem());
        }
        if (cosmetic instanceof CosmeticBackpackType || cosmetic instanceof CosmeticMainhandType) {
            item = cosmetic.getItem();
        }
        if (cosmetic instanceof CosmeticBalloonType) {
            if (cosmetic.getItem() == null) {
                item = new ItemStack(Material.LEATHER_HORSE_ARMOR);
            } else {
                item = cosmetic.getItem();
            }
        }
        return getUserCosmeticItem(cosmetic, item);
    }

    @SuppressWarnings("deprecation")
    public ItemStack getUserCosmeticItem(@NotNull Cosmetic cosmetic, @Nullable ItemStack item) {
        if (item == null) {
            //MessagesUtil.sendDebugMessages("GetUserCosemticUser Item is null");
            return new ItemStack(Material.AIR);
        }
        if (item.hasItemMeta()) {
            ItemMeta itemMeta = item.getItemMeta();

            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta skullMeta = (SkullMeta) itemMeta;
                if (skullMeta.getPersistentDataContainer().has(InventoryUtils.getSkullOwner(), PersistentDataType.STRING)) {
                    String owner = skullMeta.getPersistentDataContainer().get(InventoryUtils.getSkullOwner(), PersistentDataType.STRING);

                    owner = Hooks.processPlaceholders(getPlayer(), owner);

                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                    //skullMeta.getPersistentDataContainer().remove(InventoryUtils.getSkullOwner()); // Don't really need this?
                }
                if (skullMeta.getPersistentDataContainer().has(InventoryUtils.getSkullTexture(), PersistentDataType.STRING)) {
                    String texture = skullMeta.getPersistentDataContainer().get(InventoryUtils.getSkullTexture(), PersistentDataType.STRING);

                    texture = Hooks.processPlaceholders(getPlayer(), texture);

                    Bukkit.getUnsafe().modifyItemStack(item, "{SkullOwner:{Id:[I;0,0,0,0],Properties:{textures:[{Value:\""
                            + texture + "\"}]}}}");
                    //skullMeta.getPersistentDataContainer().remove(InventoryUtils.getSkullTexture()); // Don't really need this?
                }

                itemMeta = skullMeta;
            }

            if (Settings.isItemProcessingDisplayName()) {
                if (itemMeta.hasDisplayName()) {
                    String displayName = itemMeta.getDisplayName();
                    itemMeta.setDisplayName(Hooks.processPlaceholders(getPlayer(), displayName));
                }
            }
            if (Settings.isItemProcessingLore()) {
                List<String> processedLore = new ArrayList<>();
                if (itemMeta.hasLore()) {
                    for (String loreLine : itemMeta.getLore()) {
                        processedLore.add(Hooks.processPlaceholders(getPlayer(), loreLine));
                    }
                }
                itemMeta.setLore(processedLore);
            }


            if (colors.containsKey(cosmetic.getSlot())) {
                Color color = colors.get(cosmetic.getSlot());
                if (itemMeta instanceof LeatherArmorMeta leatherMeta) {
                    leatherMeta.setColor(color);
                } else if (itemMeta instanceof PotionMeta potionMeta) {
                    potionMeta.setColor(color);
                } else if (itemMeta instanceof MapMeta mapMeta) {
                    mapMeta.setColor(color);
                } else if (itemMeta instanceof FireworkEffectMeta fireworkMeta) {
                    fireworkMeta.setEffect(
                            FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL)
                            .withColor(color)
                            .trail(false)
                            .flicker(false)
                            .build()
                    );
                }
            }
            itemMeta.getPersistentDataContainer().set(HMCCInventoryUtils.getCosmeticKey(), PersistentDataType.STRING, cosmetic.getId());
            itemMeta.getPersistentDataContainer().set(InventoryUtils.getOwnerKey(), PersistentDataType.STRING, getEntity().getUniqueId().toString());

            item.setItemMeta(itemMeta);
        }
        return item;
    }

    public UserBalloonManager getBalloonManager() {
        return this.userBalloonManager;
    }

    public UserWardrobeManager getWardrobeManager() {
        return userWardrobeManager;
    }

    /**
     * Use {@link #enterWardrobe(Wardrobe, boolean)} instead.
     * @param ignoreDistance
     * @param wardrobe
     */
    @Deprecated(forRemoval = true, since = "2.7.5")
    public void enterWardrobe(boolean ignoreDistance, @NotNull Wardrobe wardrobe) {
        enterWardrobe(wardrobe, ignoreDistance);
    }

    /**
     * This method is used to enter a wardrobe. You can listen to the {@link PlayerWardrobeEnterEvent} to cancel the event or modify any data.
     * @param wardrobe The wardrobe to enter. Use {@link WardrobeSettings#getWardrobe(String)} to get pre-existing wardrobe or use your own by {@link Wardrobe}.
     * @param ignoreDistance If true, the player can enter the wardrobe from any distance. If false, the player must be within the distance set in the wardrobe (If wardrobe has a distance of 0 or lower, the player can enter from any distance).
     */
    public void enterWardrobe(@NotNull Wardrobe wardrobe, boolean ignoreDistance) {
        if (wardrobe.hasPermission() && !getPlayer().hasPermission(wardrobe.getPermission())) {
            MessagesUtil.sendMessage(getPlayer(), "no-permission");
            return;
        }
        if (!wardrobe.canEnter(this) && !ignoreDistance) {
            MessagesUtil.sendMessage(getPlayer(), "not-near-wardrobe");
            return;
        }
        if (!wardrobe.getLocation().hasAllLocations()) {
            MessagesUtil.sendMessage(getPlayer(), "wardrobe-not-setup");
            return;
        }
        PlayerWardrobeEnterEvent event = new PlayerWardrobeEnterEvent(this, wardrobe);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        wardrobe = event.getWardrobe();

        if (userWardrobeManager == null) {
            userWardrobeManager = new UserWardrobeManager(this, wardrobe);
            userWardrobeManager.start();
        }
    }

    /**
     * Use {@link #leaveWardrobe(boolean)} instead.
     */
    @Deprecated(forRemoval = true, since = "2.7.5")
    public void leaveWardrobe() {
        leaveWardrobe(false);
    }

    /**
     * Causes the player to leave the wardrobe. If a player is not in the wardrobe, this will do nothing, use (@{@link #isInWardrobe()} to check if they are).
     * @param ejected If true, the player was ejected from the wardrobe (Skips transition). If false, the player left the wardrobe normally.
     */
    public void leaveWardrobe(boolean ejected) {
        PlayerWardrobeLeaveEvent event = new PlayerWardrobeLeaveEvent(this);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        MessagesUtil.sendDebugMessages("Leaving Wardrobe");
        if (!getWardrobeManager().getWardrobeStatus().equals(UserWardrobeManager.WardrobeStatus.RUNNING)) return;

        getWardrobeManager().setWardrobeStatus(UserWardrobeManager.WardrobeStatus.STOPPING);
        getWardrobeManager().setLastOpenMenu(Menus.getDefaultMenu());

        if (WardrobeSettings.isEnabledTransition() && !ejected) {
            MessagesUtil.sendTitle(
                    getPlayer(),
                    WardrobeSettings.getTransitionText(),
                    WardrobeSettings.getTransitionFadeIn(),
                    WardrobeSettings.getTransitionStay(),
                    WardrobeSettings.getTransitionFadeOut()
            );
            Bukkit.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), () -> {
                userWardrobeManager.end();
                userWardrobeManager = null;
            }, WardrobeSettings.getTransitionDelay());
        } else {
            userWardrobeManager.end();
            userWardrobeManager = null;
        }
    }

    /**
     * This checks if the player is in a wardrobe. If they are, it will return true, else false. See {@link #getWardrobeManager()} to get the wardrobe manager.
     * @return If the player is in a wardrobe.
     */
    public boolean isInWardrobe() {
        return userWardrobeManager != null;
    }

    public void spawnBackpack(CosmeticBackpackType cosmeticBackpackType) {
        if (this.userBackpackManager != null) return;
        this.userBackpackManager = new UserBackpackManager(this);
        userBackpackManager.spawnBackpack(cosmeticBackpackType);
    }

    public void despawnBackpack() {
        if (userBackpackManager == null) return;
        userBackpackManager.despawnBackpack();
        userBackpackManager = null;
    }

    public boolean isBackpackSpawned() {
        return this.userBackpackManager != null;
    }

    public boolean isBalloonSpawned() {
        return this.userBalloonManager != null;
    }

    public void spawnBalloon(CosmeticBalloonType cosmeticBalloonType) {
        if (this.userBalloonManager != null) return;

        org.bukkit.entity.Entity entity = getEntity();

        UserBalloonManager userBalloonManager1 = new UserBalloonManager(this, entity.getLocation());
        userBalloonManager1.getModelEntity().teleport(entity.getLocation().add(cosmeticBalloonType.getBalloonOffset()));

        userBalloonManager1.spawnModel(cosmeticBalloonType, getCosmeticColor(cosmeticBalloonType.getSlot()));
        userBalloonManager1.addPlayerToModel(this, cosmeticBalloonType, getCosmeticColor(cosmeticBalloonType.getSlot()));

        this.userBalloonManager = userBalloonManager1;
        //this.userBalloonManager = NMSHandlers.getHandler().spawnBalloon(this, cosmeticBalloonType);
    }

    public void despawnBalloon() {
        if (this.userBalloonManager == null) return;
        this.userBalloonManager.remove();
        this.userBalloonManager = null;
    }

    public void respawnBackpack() {
        if (!hasCosmeticInSlot(CosmeticSlot.BACKPACK)) return;
        final Cosmetic cosmetic = getCosmetic(CosmeticSlot.BACKPACK);
        despawnBackpack();
        if (!hiddenReason.isEmpty()) return;
        spawnBackpack((CosmeticBackpackType) cosmetic);
        MessagesUtil.sendDebugMessages("Respawned Backpack for " + getEntity().getName());
    }

    public void respawnBalloon() {
        if (!hasCosmeticInSlot(CosmeticSlot.BALLOON)) return;
        final Cosmetic cosmetic = getCosmetic(CosmeticSlot.BALLOON);
        despawnBalloon();
        if (!hiddenReason.isEmpty()) return;
        spawnBalloon((CosmeticBalloonType) cosmetic);
        MessagesUtil.sendDebugMessages("Respawned Balloon for " + getEntity().getName());
    }

    public void removeArmor(CosmeticSlot slot) {
        EquipmentSlot equipmentSlot = HMCCInventoryUtils.getEquipmentSlot(slot);
        if (equipmentSlot == null) return;
        if (getPlayer() != null) {
            PacketManager.equipmentSlotUpdate(getEntity().getEntityId(), equipmentSlot, getPlayer().getInventory().getItem(equipmentSlot), HMCCPacketManager.getViewers(getEntity().getLocation()));
        } else {
            HMCCPacketManager.equipmentSlotUpdate(getEntity().getEntityId(), this, slot, HMCCPacketManager.getViewers(getEntity().getLocation()));
        }
    }

    /**
     * This returns the player associated with the user. Some users may not have a player attached, ie, they are npcs
     * wearing cosmetics through an addon. If you need to get locations, use getEntity instead.
     * @return Player
     */
    @Nullable
    public Player getPlayer() {
        return Bukkit.getPlayer(uniqueId);
    }

    /**
     * This gets the entity associated with the user.
     * @return Entity
     */
    public Entity getEntity() {
        return Bukkit.getEntity(uniqueId);
    }

    public Color getCosmeticColor(CosmeticSlot slot) {
        return colors.get(slot);
    }

    public List<CosmeticSlot> getDyeableSlots() {
        ArrayList<CosmeticSlot> dyableSlots = new ArrayList<>();

        for (Cosmetic cosmetic : getCosmetics()) {
            if (cosmetic.isDyable()) dyableSlots.add(cosmetic.getSlot());
        }

        return dyableSlots;
    }

    @Override
    public boolean canEquipCosmetic(@NotNull Cosmetic cosmetic, boolean ignoreWardrobe) {
        if (!cosmetic.requiresPermission()) return true;
        if (isInWardrobe() && !ignoreWardrobe) {
            if (WardrobeSettings.isTryCosmeticsInWardrobe() && userWardrobeManager.getWardrobeStatus().equals(UserWardrobeManager.WardrobeStatus.RUNNING)) return true;
        }
        return getEntity().hasPermission(cosmetic.getPermission());
    }

    public void hidePlayer() {
        Player player = getPlayer();
        if (player == null) return;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.hidePlayer(HMCCosmeticsPlugin.getInstance(), player);
            player.hidePlayer(HMCCosmeticsPlugin.getInstance(), p);
        }
    }

    public void showPlayer() {
        Player player = getPlayer();
        if (player == null) return;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.showPlayer(HMCCosmeticsPlugin.getInstance(), player);
            player.showPlayer(HMCCosmeticsPlugin.getInstance(), p);
        }
    }

    public void hideCosmetics(HiddenReason reason) {
        PlayerCosmeticHideEvent event = new PlayerCosmeticHideEvent(this, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        if (!hiddenReason.contains(reason)) hiddenReason.add(reason);
        if (hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
            despawnBalloon();
            //getBalloonManager().removePlayerFromModel(getPlayer());
            //getBalloonManager().sendRemoveLeashPacket();
        }
        if (hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
            despawnBackpack();
        }
        updateCosmetic();
        MessagesUtil.sendDebugMessages("HideCosmetics");
    }

    /**
     * This is used to silently add a hidden flag to the user. This will not trigger any events or checks, nor do anything else
     * @param reason
     */
    public void silentlyAddHideFlag(HiddenReason reason) {
        if (!hiddenReason.contains(reason)) hiddenReason.add(reason);
    }

    public void showCosmetics(HiddenReason reason) {
        if (hiddenReason.isEmpty()) return;

        PlayerCosmeticShowEvent event = new PlayerCosmeticShowEvent(this);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        hiddenReason.remove(reason);
        if (isHidden()) return;
        if (hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
            if (!isBalloonSpawned()) respawnBalloon();
            CosmeticBalloonType balloonType = (CosmeticBalloonType) getCosmetic(CosmeticSlot.BALLOON);
            getBalloonManager().addPlayerToModel(this, balloonType);
            List<Player> viewer = HMCCPacketManager.getViewers(getEntity().getLocation());
            HMCCPacketManager.sendLeashPacket(getBalloonManager().getPufferfishBalloonId(), getPlayer().getEntityId(), viewer);
        }
        if (hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
            if (!isBackpackSpawned()) respawnBackpack();
            CosmeticBackpackType cosmeticBackpackType = (CosmeticBackpackType) getCosmetic(CosmeticSlot.BACKPACK);
            ItemStack item = getUserCosmeticItem(cosmeticBackpackType);
            userBackpackManager.setItem(item);
        }
        updateCosmetic();
        MessagesUtil.sendDebugMessages("ShowCosmetics");
    }


    /**
     * This method is deprecated and will be removed in the future. Use {@link #isHidden()} instead.
     * @return
     */
    @Deprecated(since = "2.7.2-DEV", forRemoval = true)
    public boolean getHidden() {
        return !hiddenReason.isEmpty();
    }

    public boolean isHidden() {
        return !hiddenReason.isEmpty();
    }

    public boolean isHidden(HiddenReason reason) {
        return hiddenReason.contains(reason);
    }

    public List<HiddenReason> getHiddenReasons() {
        return hiddenReason;
    }

    public void clearHiddenReasons() {
        hiddenReason.clear();
    }

    public enum HiddenReason {
        NONE,
        WORLDGUARD,
        PLUGIN,
        VANISH,
        POTION,
        ACTION,
        COMMAND,
        EMOTE,
        GAMEMODE,
        WORLD,
        DISABLED
    }
}
