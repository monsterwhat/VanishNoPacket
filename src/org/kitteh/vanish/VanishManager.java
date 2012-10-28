package org.kitteh.vanish;

import java.util.*;
import net.minecraft.server.Block;
import net.minecraft.server.Packet29DestroyEntity;
import net.minecraft.server.Packet60Explosion;

import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.kitteh.vanish.event.VanishStatusChangeEvent;
import org.kitteh.vanish.metrics.MetricsOverlord;

/**
 * It's the vanishing manager!
 * 
 * @author mbaxter
 * 
 */
public class VanishManager {

    private class ShowPlayerEntry {
        private final Player player;
        private final Player target;

        public ShowPlayerEntry(Player player, Player target) {
            this.player = player;
            this.target = target;
        }

        public Player getPlayer() {
            return this.player;
        }

        public Player getTarget() {
            return this.target;
        }
    }

    private class ShowPlayerHandler implements Runnable {

        HashSet<ShowPlayerEntry> entries = new HashSet<ShowPlayerEntry>();
        HashSet<ShowPlayerEntry> next = new HashSet<ShowPlayerEntry>();

        public void add(ShowPlayerEntry player) {
            this.entries.add(player);
        }

        @Override
        public void run() {
            for (final ShowPlayerEntry entry : this.next) {
                final Player player = entry.getPlayer();
                final Player target = entry.getTarget();
                if ((player != null) && player.isOnline() && (target != null) && target.isOnline()) {
                    player.showPlayer(target);
                }
            }
            this.next.clear();
            this.next.addAll(this.entries);
            this.entries.clear();
        }

    }

    private final VanishPlugin plugin;

    private final Set<String> vanishedPlayerNames = Collections.synchronizedSet(new HashSet<String>());

    private final Map<String, Boolean> sleepIgnored = new HashMap<String, Boolean>();

    private final VanishAnnounceManipulator announceManipulator;

    private final Random random = new Random();

    private final ShowPlayerHandler showPlayer = new ShowPlayerHandler();

    public VanishManager(VanishPlugin plugin) {
        this.plugin = plugin;
        this.announceManipulator = new VanishAnnounceManipulator(this.plugin);
        this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, this.showPlayer, 4, 4);
    }

    /**
     * Called by JSONAPI
     * 
     * @return the Announce Manipulator
     */
    public VanishAnnounceManipulator getAnnounceManipulator() {
        return this.announceManipulator;
    }

    /**
     * Is the player vanished?
     * 
     * @param player
     * @return true if vanished
     */
    public boolean isVanished(Player player) {
        return this.vanishedPlayerNames.contains(player.getName());
    }

    /**
     * @param playerName
     * @return if the named player is currently vanished
     */
    public boolean isVanished(String playerName) {
        final Player player = this.plugin.getServer().getPlayer(playerName);
        if (player != null) {
            Debuggle.log("Testing vanished status of " + player.getName() + ": " + this.isVanished(player));
            return this.isVanished(player);
        }
        Debuggle.log("Testing vanished status of " + playerName + ": null");
        return false;
    }

    /**
     * @return the number of players currently vanished
     */
    public int numVanished() {
        return this.vanishedPlayerNames.size();
    }

    /**
     * Only call this when disabling the plugin
     */
    void onPluginDisable() {
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            for (final Player player2 : this.plugin.getServer().getOnlinePlayers()) {
                if ((player != null) && (player2 != null) && !player.equals(player2)) {
                    if (this.isVanished(player2) && player.canSee(player2)) {
                        player.hidePlayer(player2);
                        ((CraftPlayer) player).getHandle().netServerHandler.sendPacket(new Packet29DestroyEntity(player2.getEntityId()));
                        ((CraftPlayer) player).getHandle().removeQueue.remove(Integer.valueOf(player2.getEntityId()));
                    }
                    player.showPlayer(player2);
                }
            }
        }
    }

    public void playerQuit(Player player) {
        Debuggle.log("Quitting: " + player.getName());
        this.resetSleepingIgnored(player);
        VanishPerms.userQuit(player);
        this.removeVanished(player.getName());
    }

    /**
     * Reset the player's info
     * 
     * @param player
     */
    public void playerRefresh(Player player) {
        this.resetSeeing(player);
        if (this.isVanished(player) && !VanishPerms.canVanish(player)) {
            this.toggleVanish(player);
        }
    }

    /**
     * Force a refresh of who a player can or can't see.
     * 
     * @param player
     */
    public void resetSeeing(Player player) {
        Debuggle.log("Resetting visibility on " + player.getName());
        if (VanishPerms.canSeeAll(player)) {
            this.showVanished(player);
            Debuggle.log("Showing all to " + player.getName());
        } else {
            this.hideVanished(player);
            Debuggle.log("Hiding all to " + player.getName());
        }
    }

    /**
     * Reset SleepingIgnored to its old value for a player.
     * 
     * @param player
     */
    void resetSleepingIgnored(Player player) {
        if (this.sleepIgnored.containsKey(player.getName())) {
            player.setSleepingIgnored(this.sleepIgnored.remove(player.getName()));
        }
    }

    /**
     * Set SleepingIgnored to true for a player, and save the old
     * value.
     * 
     * @param player
     */
    void setSleepingIgnored(Player player) {
        if (!this.sleepIgnored.containsKey(player.getName())) {
            this.sleepIgnored.put(player.getName(), player.isSleepingIgnored());
        }
        player.setSleepingIgnored(true);
    }

    /**
     * Toggle a player's visibility
     * Called when a player calls /vanish
     * Talks to the player and everyone with vanish.see
     * 
     * @param togglingPlayer
     *            The player disappearing
     */
    public void toggleVanish(Player togglingPlayer) {
        this.toggleVanishQuiet(togglingPlayer);
        final String vanishingPlayerName = togglingPlayer.getName();
        String messageBit;
        final String base = ChatColor.YELLOW + vanishingPlayerName + " has ";
        if (this.isVanished(togglingPlayer)) {
            Debuggle.log("LoudVanishToggle Vanishing " + togglingPlayer.getName());
            this.plugin.hooksVanish(togglingPlayer);
            messageBit = "vanished. Poof.";

        } else {
            Debuggle.log("LoudVanishToggle Revealing " + togglingPlayer.getName());
            this.plugin.hooksUnvanish(togglingPlayer);
            messageBit = "become visible.";
            this.announceManipulator.vanishToggled(togglingPlayer);
        }
        final String message = base + messageBit;
        togglingPlayer.sendMessage(ChatColor.DARK_AQUA + "You have " + messageBit);
        this.plugin.messageStatusUpdate(message, togglingPlayer);
    }

    /**
     * Handle vanishing or unvanishing for a player
     * Does not say anything.
     * Will call effects
     * Called by toggleVanish(Player)
     * 
     * @param vanishingPlayer
     */
    public void toggleVanishQuiet(Player vanishingPlayer) {
        this.toggleVanishQuiet(vanishingPlayer, true);
    }

    /**
     * Handle vanishing or unvanishing for a player
     * Does not say anything.
     * 
     * @param vanishingPlayer
     * @param effects
     *            if true, do effects
     */
    public void toggleVanishQuiet(Player vanishingPlayer, boolean effects) {
        final boolean vanishing = !this.isVanished(vanishingPlayer);
        final String vanishingPlayerName = vanishingPlayer.getName();
        if (vanishing) {
            Debuggle.log("It's invisible time! " + vanishingPlayer.getName());
            this.setSleepingIgnored(vanishingPlayer);
            if (VanishPerms.canNotFollow(vanishingPlayer)) {
                for (final Entity entity : vanishingPlayer.getNearbyEntities(100, 100, 100)) {
                    if ((entity != null) && (entity instanceof Creature)) {
                        final Creature creature = ((Creature) entity);
                        if ((creature != null) && (creature.getTarget() != null) && creature.getTarget().equals(vanishingPlayer)) {
                            creature.setTarget(null);
                        }
                    }
                }
            }
            this.vanishedPlayerNames.add(vanishingPlayerName);
            MetricsOverlord.getVanishTracker().increment();
            this.plugin.getLogger().info(vanishingPlayerName + " disappeared.");
        } else {
            Debuggle.log("It's visible time! " + vanishingPlayer.getName());
            this.resetSleepingIgnored(vanishingPlayer);
            this.removeVanished(vanishingPlayerName);
            MetricsOverlord.getUnvanishTracker().increment();
            this.plugin.getLogger().info(vanishingPlayerName + " reappeared.");
        }
        if (effects) {
            if (VanishPerms.canSmoke(vanishingPlayer)) {
                this.smokeScreenEffect(vanishingPlayer.getLocation());
            }
            if (VanishPerms.canExplode(vanishingPlayer)) {
                this.explosionEffect(vanishingPlayer);
            }
            if (VanishPerms.canLightning(vanishingPlayer)) {
                this.lightningBarrage(vanishingPlayer.getLocation());
            }
        }
        this.plugin.getServer().getPluginManager().callEvent(new VanishStatusChangeEvent(vanishingPlayer, vanishing));
        final Player[] playerList = this.plugin.getServer().getOnlinePlayers();
        for (final Player otherPlayer : playerList) {
            if (vanishingPlayer.equals(otherPlayer)) {
                continue;
            }
            Debuggle.log("Determining what to do about " + vanishingPlayer.getName() + " for " + otherPlayer.getName());
            if (vanishing) {
                if (!VanishPerms.canSeeAll(otherPlayer)) {
                    if (otherPlayer.canSee(vanishingPlayer)) {
                        Debuggle.log("Hiding " + vanishingPlayer.getName() + " from " + otherPlayer.getName());
                        otherPlayer.hidePlayer(vanishingPlayer);
                    }
                } else {
                    otherPlayer.hidePlayer(vanishingPlayer);
                    this.showPlayer.add(new ShowPlayerEntry(otherPlayer, vanishingPlayer));
                }
            } else {
                if (VanishPerms.canSeeAll(otherPlayer)) {
                    otherPlayer.hidePlayer(vanishingPlayer);
                }
                if (!otherPlayer.canSee(vanishingPlayer)) {
                    Debuggle.log("Showing " + vanishingPlayer.getName() + " to " + otherPlayer.getName());
                    this.showPlayer.add(new ShowPlayerEntry(otherPlayer, vanishingPlayer));
                }
            }
        }
    }

    private void explosionEffect(Player player) {
        final Location loc = player.getLocation();
        final Packet60Explosion boom = new Packet60Explosion(loc.getX(), loc.getY(), loc.getZ(), 3, new ArrayList<Block>(), null);
        for (final Player plr : this.plugin.getServer().getOnlinePlayers()) {
            if (plr.getLocation().getWorld().equals(loc.getWorld())) {
                if (plr.getLocation().distance(loc) < 256) {
                    ((CraftPlayer) plr).getHandle().netServerHandler.sendPacket(boom);
                }
            }
        }
    }

    private void hideVanished(Player player) {
        for (final Player otherPlayer : this.plugin.getServer().getOnlinePlayers()) {
            if (!player.equals(otherPlayer) && this.isVanished(otherPlayer) && player.canSee(otherPlayer)) {
                player.hidePlayer(otherPlayer);
            }
        }
    }

    private void lightningBarrage(Location location) {
        final int x = location.getBlockX();
        final double y = location.getBlockY();
        final int z = location.getBlockZ();
        for (int i = 0; i < Settings.getLightningCount(); i++) {
            double xToStrike;
            double zToStrike;
            if (this.random.nextBoolean()) {
                xToStrike = x + this.random.nextInt(6);
            } else {
                xToStrike = x - this.random.nextInt(6);
            }
            if (this.random.nextBoolean()) {
                zToStrike = z + this.random.nextInt(6);
            } else {
                zToStrike = z - this.random.nextInt(6);
            }
            final Location toStrike = new Location(location.getWorld(), xToStrike, y, zToStrike);
            location.getWorld().strikeLightningEffect(toStrike);
        }
    }

    private void removeVanished(String name) {
        this.vanishedPlayerNames.remove(name);
    }

    private void showVanished(Player player) {
        for (final Player otherPlayer : this.plugin.getServer().getOnlinePlayers()) {
            if (this.isVanished(otherPlayer) && !player.canSee(otherPlayer)) {
                this.showPlayer.add(new ShowPlayerEntry(player, otherPlayer));
            }
        }
    }

    private void smokeScreenEffect(Location location) {
        for (int i = 0; i < 10; i++) {
            location.getWorld().playEffect(location, Effect.SMOKE, this.random.nextInt(9));
        }
    }

}