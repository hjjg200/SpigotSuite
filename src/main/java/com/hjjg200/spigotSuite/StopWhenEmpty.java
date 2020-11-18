package com.hjjg200.spigotSuite;

import java.util.Timer;
import java.util.TimerTask;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.Listener;

public final class StopWhenEmpty implements Module, Listener {

    public final static String NAME = StopWhenEmpty.class.getSimpleName();

    private final static long MINUTE = 60L * 1000L;
    private final SpigotSuite ss;
    private int wait;
    private int emergencyThreshold;
    private List<String> scriptArgs;
    private Timer timer;

    public StopWhenEmpty(SpigotSuite ss) {
        this.ss = ss;
    }

    private final class ScriptTask extends Thread {
        @Override
        public void run() {
            try {
                Runtime.getRuntime().exec(scriptArgs.toArray(new String[0]));
            } catch(Exception ex) {
                ex.printStackTrace();
                // TODO: send Discord messages on severe logs
            }
        }
    }

    private final class EmergencyTask extends TimerTask {
        @Override
        public void run() {
            ss.LOGGER.severe("DOING AN EMERGENCY EXIT!");
            Runtime.getRuntime().halt(1);
        }
    }

    private final class ShutdownTask extends TimerTask {
        @Override
        public void run() {
            timer = null;
            Runtime.getRuntime().addShutdownHook(new ScriptTask());
            if(emergencyThreshold > 0) {
                (new Timer()).schedule(new EmergencyTask(), emergencyThreshold * MINUTE);
            }
            ss.getServer().shutdown();
        }
    }

    public final void enable() {
        // Configuration
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        wait = config.getInt("wait");
        assert wait > 0 : "One cannot wait 0 or less minutes";
        emergencyThreshold = config.getInt("emergencyThreshold");
        scriptArgs = config.getStringList("scriptArgs");
        // Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
        // Schedule shutdown if there are no players
        if(getPlayerCount() == 0) scheduleShutdown();
    }

    public final void disable() {
        killTimer();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        killTimer();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if(getPlayerCount() == 1) scheduleShutdown();
    }

    private final synchronized void scheduleShutdown() {
        timer = new Timer();
        timer.schedule(new ShutdownTask(), wait * MINUTE);
        ss.LOGGER.info(String.format("SERVER WILL BE SHUT DOWN IN ( %d ) MINUTES", wait));
        if(emergencyThreshold > 0) ss.LOGGER.info(String.format("EMERGENCY HALT IS IN ( %d ) MINUTES", wait + emergencyThreshold));
    }

    private final synchronized void killTimer() {
        if(timer != null) {
            ss.LOGGER.info("Server shutdown was cancled");
            timer.cancel();
            timer = null;
        }
    }

    private final int getPlayerCount() {
        return ss.getServer().getOnlinePlayers().size();
    }

}
