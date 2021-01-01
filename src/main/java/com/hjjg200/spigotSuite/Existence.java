package com.hjjg200.spigotSuite;

import java.net.SocketAddress;
import java.io.IOException;
import java.net.Socket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import org.bukkit.Location;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.craftbukkit.v1_16_R3.CraftServer;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;

import net.minecraft.server.v1_16_R3.AttributeModifiable;
import net.minecraft.server.v1_16_R3.GenericAttributes;
import net.minecraft.server.v1_16_R3.EntitySlime;
import net.minecraft.server.v1_16_R3.EntityTypes;
import net.minecraft.server.v1_16_R3.ControllerMove;
import net.minecraft.server.v1_16_R3.EnumProtocolDirection;
import net.minecraft.server.v1_16_R3.NetworkManager;
import net.minecraft.server.v1_16_R3.Packet;
import net.minecraft.server.v1_16_R3.PlayerConnection;
import net.minecraft.server.v1_16_R3.EnumGamemode;
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_16_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_16_R3.PlayerInteractManager;
import net.minecraft.server.v1_16_R3.EntityPlayer;
import net.minecraft.server.v1_16_R3.MinecraftServer;
import net.minecraft.server.v1_16_R3.WorldServer;

import io.netty.util.concurrent.GenericFutureListener;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;

class EmptyChannel extends AbstractChannel {
    private final ChannelConfig config = new DefaultChannelConfig(this);

    public EmptyChannel(Channel parent) {
        super(parent);
    }

    @Override
    public ChannelConfig config() {
        config.setAutoRead(true);
        return config;
    }

    @Override
    protected void doBeginRead() throws Exception {
    }

    @Override
    protected void doBind(SocketAddress arg0) throws Exception {
    }

    @Override
    protected void doClose() throws Exception {
    }

    @Override
    protected void doDisconnect() throws Exception {
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer arg0) throws Exception {
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    protected boolean isCompatible(EventLoop arg0) {
        return true;
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    protected SocketAddress localAddress0() {
        return null;
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(true);
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }
}

class EmptySocket extends Socket {
    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(EMPTY);
    }

    @Override
    public OutputStream getOutputStream() {
        return new ByteArrayOutputStream(10);
    }

    private static final byte[] EMPTY = new byte[50];
}

class EmptyNetworkManager extends NetworkManager {
    public EmptyNetworkManager(EnumProtocolDirection flag) throws IOException {
        super(flag);
        this.channel = new EmptyChannel(null);
        SocketAddress socketAddress = new SocketAddress() {
            private static final long serialVersionUID = 8207338859896320185L;
        };
        this.socketAddress = socketAddress;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void sendPacket(Packet packet, GenericFutureListener genericfuturelistener) {
    }
}

class EmptyNetHandler extends PlayerConnection {
    public EmptyNetHandler(MinecraftServer minecraftServer, NetworkManager networkManager, EntityPlayer entityPlayer) {
        super(minecraftServer, networkManager, entityPlayer);
    }

    @Override
    public void sendPacket(Packet<?> packet) {
    }
}

class DummyNetworkManager extends NetworkManager {
    public DummyNetworkManager(EnumProtocolDirection enumprotocoldirection) {
        super(enumprotocoldirection);
    }
}

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
    public final void onPlayerJoin(final PlayerJoinEvent e) {
        final Player player = e.getPlayer();
        final Location loc = player.getLocation();

        MinecraftServer nmsServer = ((CraftServer) ss.getServer()).getServer();
        WorldServer nmsWorld = ((CraftWorld) loc.getWorld()).getHandle();

        PlayerInteractManager interactManager = new PlayerInteractManager(nmsWorld);
        EntityPlayer entity = new EntityPlayer(nmsServer,
                                               nmsWorld,
                                               new GameProfile(UUID.randomUUID(), "dummy"),
                                               //new GameProfile(player.getUniqueId(), player.getName()),
                                               interactManager);

        entity.setLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        interactManager.setGameMode(EnumGamemode.SURVIVAL);
        // Mock
      /*  Socket socket = new EmptySocket();
        NetworkManager conn = null;
        try {
            conn = new EmptyNetworkManager(EnumProtocolDirection.CLIENTBOUND);
            entity.playerConnection = new EmptyNetHandler(nmsServer, conn, entity);
            conn.setPacketListener(entity.playerConnection);
            socket.close();
        } catch (IOException ex) {
            // swallow
        } */
        entity.playerConnection = new PlayerConnection(nmsServer, new DummyNetworkManager(EnumProtocolDirection.CLIENTBOUND), entity);
        entity.invulnerableTicks = 0;
        //nmsWorld.addEntity(entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
        //entity.spawnIn(nmsWorld);

        nmsWorld.addEntity(entity);
        nmsWorld.getPlayers().remove(entity);

        new BukkitRunnable() {
            public void run() {
                entity.playerTick();
            }
        }.runTaskTimer(ss, 0, 1);

        //ControllerMove controllerMove = new ControllerMove(new EntitySlime(EntityTypes.SLIME, nmsWorld));

        PlayerConnection connection = ((CraftPlayer) player).getHandle().playerConnection;
        connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, entity));
        connection.sendPacket(new PacketPlayOutNamedEntitySpawn(entity));
        connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, entity));

        // Show it to player by sending ADD_PLAYER packet
        //PlayerConnection connection = ((CraftPlayer) player).getHandle().playerConnection;
        //connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, entity));
        //connection.sendPacket(new PacketPlayOutNamedEntitySpawn(entity));
    }

    public final String getName() {
        return NAME;
    }

}
