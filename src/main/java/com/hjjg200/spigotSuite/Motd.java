package com.hjjg200.spigotSuite;

import java.io.File;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServerListPingEvent;

import com.hjjg200.spigotSuite.util.Resource;

public final class Motd implements Module, Listener {

    private final static String NAME = Motd.class.getSimpleName();
    private final static String KEY_LOAD_TIME = "loadTime";
    private final SpigotSuite ss;
    private final File dataFile;
    private FileConfiguration data;
    private LocalDateTime loadStart;
    private long loadTime = 0;
    private volatile boolean loaded = false;
    private String anteLoad;
    private String timeFormat;
    private String postLoad;

    public Motd(final SpigotSuite ss) {
        this.ss = ss;
        dataFile = Resource.get(ss, NAME + ".yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadTime = data.getLong(KEY_LOAD_TIME);
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void enable() {
        if(!loaded) {
            loadStart = LocalDateTime.now();
        }

        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        anteLoad = config.getString("anteLoad");
        timeFormat = config.getString("timeFormat");
        postLoad = config.getString("postLoad");
    }
    public final void disable() {
    }

    public final String getName() {
        return NAME;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        if(!loaded) {
            loaded = true;
            final LocalDateTime loadEnd = LocalDateTime.now();
            loadTime = loadStart.until(loadEnd, ChronoUnit.SECONDS);
            data.set(KEY_LOAD_TIME, loadTime);
            try {
                data.save(dataFile);
            } catch(Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    @EventHandler
    public void onServerListPing(ServerListPingEvent e) {
        if(loaded == false) {
            String time = "";
            if(loadTime > 0) {
                final long past = loadStart.until(LocalDateTime.now(), ChronoUnit.SECONDS);
                final long remain = Math.max(0, loadTime - past);
                time = String.format(time, remain / 60L, remain % 60L);
            }
            e.setMotd(ChatColor.YELLOW.toString() + String.format(anteLoad, time) + ChatColor.RESET.toString());
        } else {
            e.setMotd(ChatColor.GREEN.toString() + postLoad + ChatColor.RESET.toString());
        }
    }

}
