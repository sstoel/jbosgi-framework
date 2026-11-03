/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues.hooks;

import java.util.function.Consumer;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * The activator of the test bundles: it calls the hook that the test put for the symbolic name of the bundle with
 * {@link ReleaseCheckSupport#hook(String, String, Consumer)}. The bundle has its own copy of this class, the hook is
 * shared through the system properties.
 */
public class HookActivator implements BundleActivator {

    public void start(BundleContext context) {
        call("start", context);
    }

    public void stop(BundleContext context) {
        call("stop", context);
    }

    @SuppressWarnings("unchecked")
    private static void call(String kind, BundleContext context) {
        Object hook = System.getProperties().get(key(kind, context.getBundle().getSymbolicName()));
        if (hook != null)
            ((Consumer<BundleContext>) hook).accept(context);
    }

    static String key(String kind, String symbolicName) {
        return "issues.hook." + kind + "." + symbolicName;
    }
}
