package com.hjjg200.spigotSuite;

import java.io.File;
import java.util.function.Consumer;

import org.bukkit.ChatColor;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import com.hjjg200.spigotSuite.chatBridge.*;

public final class ChatBridge implements Listener, Module {

    private final static String NAME = ChatBridge.class.getSimpleName();
    private final static String CHAT_NAME_FORMAT = "[%s] ";

    private final SpigotSuite ss;
    private Plugin plugin;

    public ChatBridge(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        // Configuration
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        final String plName = config.getString("plugin");
        if(plName.equals("none")) return;
        final File dataFolder = new File(ss.getDataFolder(), NAME);
        dataFolder.mkdirs();
        // * Plugin
        final String plConfigBase = plName + ".yml";
        final String plConfigRel = new File(NAME, plConfigBase).getPath();
        final File plConfigFile = new File(ss.getDataFolder(), plConfigRel);
        if(!plConfigFile.exists()) {
            ss.saveResource(plConfigRel, false);
            // Set mode 600
            plConfigFile.setReadable(false);
            plConfigFile.setWritable(false);
            plConfigFile.setReadable(true, true);
            plConfigFile.setWritable(true, true);
        }
        final Configuration plConfig = YamlConfiguration.loadConfiguration(plConfigFile);
        // * Instantiate plugin
        switch(plName) {
        case "discord":
            plugin = new DiscordPlugin(plConfig);
            break;
        default:
            ss.getLogger().severe("Attempt to use unknown chat plugin");
            return;
        }
        plugin.subscribeEvent(ev -> {
            ss.getServer().broadcastMessage(String.format(CHAT_NAME_FORMAT + "%s", ev.getDisplayName(), ev.getContent()));
        });
        plugin.enable();
        // * Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void disable() {
        if(plugin != null) plugin.disable();
    }

    public final String getName() {
        return NAME;
    }

    public final void alertAdmin(final String alert) {
        plugin.alertAdmin(alert);
    }

    @EventHandler
    public void onPlayerChat(final AsyncPlayerChatEvent e) {
        plugin.sendMessage(e.getPlayer().getDisplayName(), e.getMessage());
    }

    @EventHandler
    public void onPlayerDeath(final PlayerDeathEvent e) {
        plugin.sendMessage(null, e.getDeathMessage());
    }

    @EventHandler
    public void onPlayerAdvancementDone(final PlayerAdvancementDoneEvent e) {
        String advancement = String.join("->", e.getAdvancement().getCriteria());
        plugin.sendMessage(null, String.format("%s has achieved **%s**", e.getPlayer().getDisplayName(), advancement));
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent e) {
        plugin.sendMessage(null, ChatColor.stripColor(e.getJoinMessage()));
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent e) {
        plugin.sendMessage(null, ChatColor.stripColor(e.getQuitMessage()));
    }

}
