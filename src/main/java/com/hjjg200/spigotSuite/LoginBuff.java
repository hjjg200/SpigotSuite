package com.hjjg200.spigotSuite;

import java.sql.Connection;
import java.sql.DriveManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.hjjg200.spigotSuite.util.Resource;

public final class LoginBuff implements Module, Listener {

    private final static String NAME = LoginBuff.class.getSimpleName();
    private final SpigotSuite ss;
    private final String jdbcAddr = "jdbc:sqlite:" + Resource.get(ss, NAME, "data.db").toString();

    public LoginBuff(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        // Open Sqlite db
        Connection conn = null;
        try {
            conn = DriveManager.getConnection(jdbcAddr);
            Statement stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            stmt.executeUpdate("create table person (id integer, name string)"):
        }
    } catch(SQLException e) {

    } finally {
        try {
            if(conn != null) {
                conn.close();
            }
        } catch(SQLException e) {

        }
    }

    public final void disable() {
    }

}

