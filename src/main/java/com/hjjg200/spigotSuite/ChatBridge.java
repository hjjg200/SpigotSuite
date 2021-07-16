package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Collection;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import org.bukkit.command.CommandSender;
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
import org.bukkit.entity.Player;
import org.bukkit.event.server.BroadcastMessageEvent;

import com.hjjg200.spigotSuite.chatBridge.*;
import com.hjjg200.spigotSuite.util.Log4jUtils;

public final class ChatBridge implements Listener, Module {

    private final static String NAME = ChatBridge.class.getSimpleName();
    private final static String CHAT_NAME_FORMAT = "[%s] ";

    private final SpigotSuite ss;
    private final HashMap<String, Void> ignoreMap = new HashMap<String, Void>();
    private Plugin plugin;
    private Logger logger = null;
    private LogListener logListener = null;
    private Appender logAppender = null;
    private Thread shutdownHook = null;

    public ChatBridge(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() throws Exception {
        // If reloaded, run the shutdown hook and remove it
        if(shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdownHook.run();
            shutdownHook = null;
        }
        // Configuration
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        final String plName = config.getString("plugin");
        if(plName.equals("none")) {
            throw new Module.DisabledException();
        }
        final File dataFolder = new File(ss.getDataFolder(), NAME);
        dataFolder.mkdirs();
        // * Plugin
        final ConfigurationSection plConfig = config.getConfigurationSection(plName);
        // * Instantiate plugin
        switch(plName) {
        case "discord":
            plugin = new DiscordPlugin(plConfig);
            break;
        default:
            throw new Exception("Attempted to use unknown chat plugin");
        }
        plugin.subscribeEvent(ev -> {
            String formatted = String.format(CHAT_NAME_FORMAT + "%s", ev.getDisplayName(),
                                            ev.getContent());
            ignoreMap.put(formatted, null);
            ss.getServer().broadcastMessage(formatted);
        });
        plugin.enable();
        // * Admin log appender
        logger = (Logger)LogManager.getRootLogger();
        logAppender = plugin.createLogAppender(Log4jUtils.getLayout());
        logAppender.start();
        logger.addAppender(logAppender);
        // * Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
        logListener = new LogListener();
        logListener.start();
        logger.addAppender(logListener);
    }

    public final void disable() {
        ignoreMap.clear();
        shutdownHook = new Thread() {
            @Override
            public void run() {
                // Disable plugin
                logger.removeAppender(logAppender);
                logAppender.stop();
                plugin.disable();
                // Disable listener
                logger.removeAppender(logListener);
                logListener.stop();
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public final String getName() {
        return NAME;
    }

    // Handling events
    private final class LogListener extends AbstractAppender {
        public LogListener() {
            super(NAME + ".LogListener", null, null, true, null);
        }
        @Override
        public void append(LogEvent e) {
            if(e.getLevel().equals(Level.INFO)) {
                final String message = e.getMessage().getFormattedMessage();
                if(message.contains("has made the advancement")) {
                    final String[] args = message.split(" ");
                    for(final Player player : ss.getServer().getOnlinePlayers()) {
                        if(player.getDisplayName().equals(args[0])) {
                            plugin.sendMessage(null, message);
                            return;
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onBroadcastMessage(final BroadcastMessageEvent e) {
        final String message = e.getMessage();
        if(ignoreMap.containsKey(message)) {
            ignoreMap.remove(message);
            return;
        }

        final List<Player> players = new ArrayList<Player>(ss.getServer().getOnlinePlayers());
        for(final CommandSender recipient : e.getRecipients()) {
            if(recipient instanceof Player)
                players.remove((Player)recipient);
        }
        if(players.size() == 0) {
            plugin.sendMessage(null, ChatColor.stripColor(message));
        }
    }

    @EventHandler
    public void onPlayerChat(final AsyncPlayerChatEvent e) {
        plugin.sendMessage(e.getPlayer().getDisplayName(), ChatColor.stripColor(e.getMessage()));
    }

    @EventHandler
    public void onPlayerDeath(final PlayerDeathEvent e) {
        plugin.sendMessage(null, ChatColor.stripColor(e.getDeathMessage()));
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
