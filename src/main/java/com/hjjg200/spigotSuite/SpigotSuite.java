package com.hjjg200.spigotSuite;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.Configuration;

import com.hjjg200.spigotSuite.InventoryGrave;
import com.hjjg200.spigotSuite.StopWhenEmpty;

public final class SpigotSuite extends JavaPlugin {

    private static final String NAME = SpigotSuite.class.getSimpleName();
    private final ArrayList<Module> modules = new ArrayList<Module>();

    public SpigotSuite() {
    }

    @Override
    public void onEnable() {
        // Configuration
        final Configuration config = getConfig();
        saveDefaultConfig();
        // Enable modules
        modules.add(new InventoryGrave(this));
        modules.add(new StopWhenEmpty(this));
        modules.add(new ChatBridge(this));
        modules.add(new Motd(this));
        modules.add(new Backup(this));
        for(final Module m : modules) {
            try {
                m.enable();
                getLogger().log(Level.INFO, "Successfully enabled {0}", m.getName());
            } catch(Exception ex) {
                modules.remove(m);
                getLogger().log(Level.SEVERE, "Failed to enable {0}\n{1}", new Object[]{m.getName(), ex});
            }
        }
    }

    @Override
    public void onDisable() {
        // Disable modules and clear
        for(final Module m : modules) m.disable();
        modules.clear();
    }

}
