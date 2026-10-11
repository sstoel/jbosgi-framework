package org.jboss.test.osgi.framework.launch.support;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * The activator of a framework extension bundle. It records what it sees in a system property,
 * because the test and the extension do not share this class.
 */
public class ExtensionActivator implements BundleActivator {

    public static final String EVENTS = "org.jboss.test.osgi.framework.launch.extension.events";
    public static final String FAIL_ON_START = "org.jboss.test.osgi.framework.launch.extension.fail";

    private ServiceRegistration<String> registration;

    @SuppressWarnings("unchecked")
    public static List<String> getEvents() {
        synchronized (System.getProperties()) {
            List<String> events = (List<String>) System.getProperties().get(EVENTS);
            if (events == null) {
                events = Collections.synchronizedList(new ArrayList<String>());
                System.getProperties().put(EVENTS, events);
            }
            return events;
        }
    }

    @Override
    public void start(BundleContext context) throws Exception {
        Bundle bundle = context.getBundle();
        getEvents().add("start:bundle=" + bundle.getBundleId() + ",state=" + bundle.getState());
        if (Boolean.getBoolean(FAIL_ON_START))
            throw new IllegalStateException("start failed");
        registration = context.registerService(String.class, "extension", null);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        Bundle bundle = context.getBundle();
        getEvents().add("stop:bundle=" + bundle.getBundleId() + ",state=" + bundle.getState());
        registration.unregister();
    }
}
