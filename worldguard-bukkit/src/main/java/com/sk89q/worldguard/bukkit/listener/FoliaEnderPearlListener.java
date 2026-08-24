/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.listener;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.UUID;

/**
 * Enforces {@link Flags#ENDERPEARL} on Folia-based servers.
 *
 * <p>Folia teleports the thrower of an ender pearl through its own asynchronous teleport
 * path, which does not fire {@link org.bukkit.event.player.PlayerTeleportEvent} at all: the
 * pearl and its owner may be ticked by two different region threads, so the event cannot be
 * fired where the teleport is decided. That leaves
 * {@link WorldGuardPlayerListener#onPlayerTeleport} with nothing to cancel, which is why the
 * flag silently does nothing on Folia.</p>
 *
 * <p>This listener enforces the flag on the projectile instead, splitting the check across
 * the two threads that may legally touch each end of the teleport:</p>
 *
 * <ul>
 *     <li>the throw is stopped, without consuming the pearl, when the region the thrower is
 *     standing in denies the flag -- the "exit" half of the check, reported with
 *     {@link Flags#EXIT_DENY_MESSAGE};</li>
 *     <li>the pearl is destroyed just before it would teleport anybody when the region it
 *     landed in denies the flag -- the "entry" half, reported with
 *     {@link Flags#ENTRY_DENY_MESSAGE}.</li>
 * </ul>
 *
 * <p>The exit half is therefore evaluated where the pearl is thrown rather than where it
 * lands. A player who throws a pearl inside a denying region and then leaves that region
 * before the pearl lands is stopped here, whereas the regular teleport listener would have
 * let that pearl through. Reading the thrower's position from the pearl's region thread is
 * not safe on Folia, so this is as close as the flag can get without doing so.</p>
 */
public class FoliaEnderPearlListener extends AbstractListener {

    /**
     * Construct the listener.
     *
     * @param plugin an instance of WorldGuardPlugin
     */
    public FoliaEnderPearlListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerLaunchProjectile(PlayerLaunchProjectileEvent event) {
        if (!(event.getProjectile() instanceof EnderPearl)) {
            return;
        }

        Player player = event.getPlayer();
        Location origin = player.getLocation();
        if (!isEnabled(origin.getWorld()) || Entities.isNPC(player)) {
            return;
        }

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
            return;
        }

        ApplicableRegionSet from = queryRegions(origin);
        if (!from.testState(localPlayer, Flags.ENDERPEARL)) {
            String message = from.queryValue(localPlayer, Flags.EXIT_DENY_MESSAGE);
            if (message != null && !message.isEmpty()) {
                player.sendMessage(message);
            }
            // Hand the pearl back: the player never got to use it.
            event.setShouldConsume(false);
            event.setCancelled(true);
        }
    }

    /**
     * Deny pearls that land in a region that does not allow them.
     *
     * <p>This deliberately does not ignore cancelled events. Cancelling a
     * {@link ProjectileHitEvent} only calls off the hit against an entity, so a pearl that
     * another plugin cancelled against a block would still teleport its owner. Removing the
     * pearl is what actually denies the teleport, because Folia only teleports the owner if
     * the pearl is still alive once the hit has been processed.</p>
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }

        Location destination = pearl.getLocation();
        World world = destination.getWorld();
        if (!isEnabled(world)) {
            return;
        }

        // getShooter() resolves the owning entity, which Folia refuses to hand over from
        // another region's thread. The owner's UUID is a plain field read, so it always works.
        UUID ownerId = pearl.getOwnerUniqueId();
        if (ownerId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(ownerId);
        if (player == null || Entities.isNPC(player)) {
            return;
        }

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(world))) {
            return;
        }

        ApplicableRegionSet to = queryRegions(destination);
        if (!to.testState(localPlayer, Flags.ENDERPEARL)) {
            String message = to.queryValue(localPlayer, Flags.ENTRY_DENY_MESSAGE);
            if (message != null && !message.isEmpty()) {
                player.sendMessage(message);
            }
            event.setCancelled(true);
            pearl.remove();
        }
    }

    /**
     * Return whether the ender pearl flag can apply in the given world.
     *
     * @param world the world, which may be null
     * @return true if both region support and player teleport handling are enabled
     */
    private boolean isEnabled(World world) {
        return world != null && isRegionSupportEnabled(world) && getConfig().usePlayerTeleports;
    }

    /**
     * Query the regions that contain the given location.
     *
     * @param location the location
     * @return the applicable regions
     */
    private ApplicableRegionSet queryRegions(Location location) {
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.getApplicableRegions(BukkitAdapter.adapt(location));
    }
}
