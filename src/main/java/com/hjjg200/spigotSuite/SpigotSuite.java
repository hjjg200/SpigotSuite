package com.hjjg200.spigotSuite;

import java.util.ArrayList;
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
        for(final Module m : modules) m.enable();
    }

    @Override
    public void onDisable() {
        // Disable modules and clear
        for(final Module m : modules) m.disable();
        modules.clear();
    }

}