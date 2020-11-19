package com.hjjg200.spigotSuite.chatBridge;

import java.util.function.Consumer;

public interface Plugin {

    public interface IEvent {
        public String getDisplayName();
        public String getContent();
    }
    public void enable();
    public void disable();
    public void subscribeEvent(final Consumer<IEvent> listener);
    public void sendMessage(String name, String message);
    public void alertAdmin(String alert);
    public String getName();

}
