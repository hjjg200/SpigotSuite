package com.hjjg200.spigotSuite.chatBridge;

import java.util.Arrays;
import java.util.function.Consumer;
import java.io.Serializable;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;

import org.bukkit.configuration.ConfigurationSection;

public abstract class AbstractPlugin implements Plugin {

    protected final ConfigurationSection config;
    protected Consumer<Event> listener;

    protected AbstractPlugin(final ConfigurationSection config) {
        this.config = config;
    }

    public void enable() {
    }

    public void disable() {
    }

    public void subscribeEvent(final Consumer<Event> listener) {
        this.listener = listener;
    }
    public abstract void sendMessage(String name, String message);
    public abstract Appender createLogAppender(Layout<? extends Serializable> layout);
    public abstract String getName();

}
