package com.hjjg200.spigotSuite.chatBridge;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.io.OutputStream;
import java.io.Serializable;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;

public interface Plugin {

    static class Event {
        private final String display;
        private final String content;
        public Event(final String display, final String content) {
            this.display = display;
            this.content = content;
        }
        public final String getDisplayName() {
            return display;
        }
        public final String getContent() {
            return content;
        }
    }
    public void enable();
    public void disable();
    public void subscribeEvent(final Consumer<Event> listener);
    public void sendMessage(String name, String message);
    public Appender createLogAppender(Layout<? extends Serializable> layout);
    public String getName();

}
