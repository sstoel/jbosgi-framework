package org.jboss.osgi.framework.internal;

import java.security.PrivilegedAction;

class PropertyReadAction implements PrivilegedAction<String> {

    private final String key;
    private final String defVal;

    PropertyReadAction(final String key) {
        this(key, null);
    }

    PropertyReadAction(final String key, final String defVal) {
        this.key = key;
        this.defVal = defVal;
    }

    public String run() {
        return System.getProperty(key, defVal);
    }
}