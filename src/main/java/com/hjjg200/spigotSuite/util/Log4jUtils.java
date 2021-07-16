package com.hjjg200.spigotSuite.util;

import java.io.Serializable;
import java.util.Map;

import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.Layout;

public final class Log4jUtils {

    // getLayout Expects all the appenders use the same layout
    public static final Layout<? extends Serializable> getLayout() {
        Layout<? extends Serializable> layout = null;

        return PatternLayout.newBuilder().withPattern(
            "[%d{HH:mm:ss}] [%t/%level]: %msg%n"
        ).build();
    }

}
