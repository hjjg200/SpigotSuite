package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.OutputStream;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.AbstractAppender;

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
import com.hjjg200.spigotSuite.util.Log4jUtils;

public final class ChatBridge implements Listener, Module {

    private final static String NAME = ChatBridge.class.getSimpleName();
    private final static String CHAT_NAME_FORMAT = "[%s] ";

    private final SpigotSuite ss;
    private Plugin plugin;
    private Logger logger = null;
    private Appender logAppender = null;
    private Thread shutdownHook = null;

    public ChatBridge(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        // If reloaded, run the shutdown hook and remove it
        if(shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdownHook.run();
            shutdownHook = null;
        }
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
            ss.getServer().broadcastMessage(String.format(CHAT_NAME_FORMAT + "%s", ev.getDisplayName(),
                                            ev.getContent()));
        });
        plugin.enable();
        // * Admin log appender
        logger = (Logger)LogManager.getRootLogger();
        logAppender = plugin.createLogAppender(Log4jUtils.getLayout());
        logger.addAppender(logAppender);
        // * Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void disable() {
        if(plugin != null) {
            shutdownHook = new Thread() {
                @Override
                public void run() {
                    plugin.disable();
                    if(logAppender != null) {
                        logger.removeAppender(logAppender);
                        logAppender = null;
                    }
                }
            };
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    public final String getName() {
        return NAME;
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
    public void onPlayerJoin(final PlayerJoinEvent e) {
        plugin.sendMessage(null, ChatColor.stripColor(e.getJoinMessage()));
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent e) {
        plugin.sendMessage(null, ChatColor.stripColor(e.getQuitMessage()));
    }

}
