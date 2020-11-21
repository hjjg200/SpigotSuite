package com.hjjg200.spigotSuite.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.bukkit.plugin.Plugin;

public final class Resource {

    private final static File getResourceFile(Plugin plugin, String ...elements) {
        plugin.getDataFolder().mkdirs();
        File resource = null;
        for(int i = 0; i < elements.length; i++) {
            resource = resource != null
                ? new File(resource, elements[i])
                : new File(elements[i]);
            if(i == elements.length - 2) resource.mkdirs();
        }
        if(plugin.getResource(resource.getPath()) != null) plugin.saveResource(resource.getPath(), false);
        return resource;
    }

    public final static File get(Plugin plugin, String ...elements) {
        return new File(plugin.getDataFolder(), getResourceFile(plugin, elements).getPath());
    }

    public final static InputStream getInputStream(Plugin plugin, String ...elements) {
        return plugin.getResource(getResourceFile(plugin, elements).getPath());
    }

    public final static Reader getReader(Plugin plugin, String ...elements) {
        return new InputStreamReader(getInputStream(plugin, elements), StandardCharsets.UTF_8);
    }

}
