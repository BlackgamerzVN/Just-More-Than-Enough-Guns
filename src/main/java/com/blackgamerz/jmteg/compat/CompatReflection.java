package com.blackgamerz.jmteg.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Small reflection helpers for use by compat classes.
 */
public final class CompatReflection {
    private static final Logger LOGGER = LogManager.getLogger("JMTJEG-CompatReflection");

    private CompatReflection() {}

    public static Optional<Class<?>> findClass(String fqcn) {
        try {
            return Optional.of(Class.forName(fqcn));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        } catch (Throwable t) {
            LOGGER.error("Unexpected reflection error when looking for {}", fqcn, t);
            return Optional.empty();
        }
    }

    public static Optional<Method> findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method m = clazz.getMethod(name, params);
            return Optional.of(m);
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        } catch (Throwable t) {
            LOGGER.error("Unexpected reflection error when finding method {} on {}", name, clazz.getName(), t);
            return Optional.empty();
        }
    }
}
