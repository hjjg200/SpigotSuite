package com.hjjg200.spigotSuite;

import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.Configuration;

import com.hjjg200.spigotSuite.InventoryGrave;

public final class SpigotSuite extends JavaPlugin {

    public static final String NAME = "SpigotSuite";
    public static final Logger LOGGER = Logger.getLogger(NAME);

    public SpigotSuite() {
    }

    @Override
    public void onEnable() {

        final Configuration config = getConfig();
        saveDefaultConfig();

        final InventoryGrave ig = new InventoryGrave(this);
        ig.enable();

    }

}
