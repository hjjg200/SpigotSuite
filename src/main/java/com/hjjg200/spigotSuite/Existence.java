package com.hjjg200.spigotSuite;

import com.mojang.authlib.GameProfile;

import org.bukkit.Location;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

import org.bukkit.craftbukkit.v1_16_R3.CraftServer;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;

import net.minecraft.server.v1_16_R3.PlayerConnection;
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_16_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_16_R3.PlayerInteractManager;
import net.minecraft.server.v1_16_R3.EntityPlayer;
import net.minecraft.server.v1_16_R3.MinecraftServer;
import net.minecraft.server.v1_16_R3.WorldServer;

public final class Existence implements Module, Listener {

    private final static String NAME = Existence.class.getSimpleName();
    private final SpigotSuite ss;

    public Existence(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void disable() {

    }

    @EventHandler
    public final void onPlayerQuit(final PlayerQuitEvent e) {
        final Player player = e.getPlayer();
        final Location loc = player.getLocation();

        MinecraftServer nmsServer = ((CraftServer) ss.getServer()).getServer();
        WorldServer nmsWorld = ((CraftWorld) loc.getWorld()).getHandle();

        EntityPlayer entity = new EntityPlayer(nmsServer, nmsWorld,
                new GameProfile(player.getUniqueId(), player.getName()),
                new PlayerInteractManager(nmsWorld));

        entity.setLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());

        PlayerConnection connection = ((CraftPlayer) player).getHandle().playerConnection;
            connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, entity));
            connection.sendPacket(new PacketPlayOutNamedEntitySpawn(entity));
    }

    public final String getName() {
        return NAME;
    }

}
