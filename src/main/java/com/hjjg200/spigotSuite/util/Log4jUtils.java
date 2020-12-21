package com.hjjg200.spigotSuite.util;

import java.io.Serializable;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.Logger;

public final class Log4jUtils {

    // getLayout Expects all the appenders use the same layout
    public static final Layout<? extends Serializable> getLayout() {
        final Logger logger = (Logger)LogManager.getRootLogger();
        final Map<String, Appender> appenders = logger.getAppenders();
        return appenders.get(appenders.keySet().iterator().next()).getLayout();
    }

}
