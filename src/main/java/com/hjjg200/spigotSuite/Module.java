package com.hjjg200.spigotSuite;

public interface Module {
    public final class DisabledException extends Exception {
    }

    public String getName();
    public void enable() throws Exception;
    public void disable() throws Exception;
}
