package com.hjjg200.spigotSuite.chatBridge;

import java.util.function.Consumer;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.configuration.Configuration;

public final class DiscordPlugin implements Plugin {

    private final static String PLUGIN_NAME = "discord";
    private transient JDA jda;
    private transient String botToken;
    private transient String channelId;
    private transient String adminId;
    private Consumer<IEvent> listener;

    private final class Event implements IEvent {
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

    private final class MessageListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            final User author = e.getAuthor();
            final Message message = e.getMessage();
            final MessageChannel from = e.getChannel();
            if(jda.getSelfUser().getId().equals(author.getId())) return;

            switch(from.getType()) {
            case TEXT:
                if(!from.getId().equals(channelId)) return;
                if(listener == null) return;
                final Member member = e.getMember();
                listener.accept(new Event(member.getEffectiveName(), message.getContentStripped()));
                break;
            case PRIVATE:
                final String line = message.getContentRaw();
                if("!id".equals(line)) {
                    from.sendMessage(author.getId()).queue();
                }
                break;
            }
        }
    }

    public final String getName() {
        return PLUGIN_NAME;
    }

    public DiscordPlugin(final Configuration config) {
        botToken = config.getString("botToken");
        channelId = config.getString("channelId");
        assert !channelId.equals("") : "Invalid channelId supplied";
        adminId = config.getString("adminId");
    }

    public void enable() {
        JDABuilder builder = JDABuilder.createDefault(botToken);
        builder.addEventListeners(new MessageListener());
        try {
            jda = builder.build();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public void disable() {
        jda.shutdown();
        jda = null;
    }

    public final void subscribeEvent(final Consumer<IEvent> listener) {
        this.listener = listener;
    }
    public final void sendMessage(final String name, final String message) {
        final String n = name == null ? "" : "**[" + name + "]** ";
        final MessageChannel to = jda.getTextChannelById(channelId);
        to.sendMessage(String.format("%s%s", n, message)).queue();
    }
    public final void alertAdmin(String alert) {
        if(!adminId.equals("")) {
            final User admin = jda.getUserById(adminId);
            admin.openPrivateChannel().queue(to -> {
                to.sendMessage(alert).queue();
            });
        }
    }

}
