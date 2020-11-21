package com.hjjg200.spigotSuite;

public interface Module {
    public String getName();
    public void enable() throws Exception;
    public void disable();
}
