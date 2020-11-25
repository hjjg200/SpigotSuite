package com.hjjg200.spigotSuite.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import org.bukkit.plugin.Plugin;

public final class Resource {

    public final static File directory(Plugin plugin, String start, String ...more) {
        plugin.getDataFolder().mkdirs();
        final File dir = new File(plugin.getDataFolder(), Paths.get(start, more).toString());
        dir.mkdirs();
        return dir;
    }

    private final static File getResourceFile(Plugin plugin, String start, String ...more) {
        plugin.getDataFolder().mkdirs();
        final File resource = Paths.get(start, more).toFile();
        if(plugin.getResource(resource.getPath()) != null) plugin.saveResource(resource.getPath(), false);
        return resource;
    }

    public final static File get(Plugin plugin, String start, String ...more) {
        final File file = new File(plugin.getDataFolder(), getResourceFile(plugin, start, more).getPath());
        file.getParentFile().mkdirs();
        return file;
    }

}
