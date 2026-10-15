package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Refreshing a bundle fires a second INSTALLED event for it, and refreshing or updating a bundle that is not resolved
 * fires an UNRESOLVED event although the bundle never left the RESOLVED state.
 *
 * <h3>Severity</h3>
 * minor - listeners that react to INSTALLED (extenders, deployers, audit) see an already installed bundle as newly
 * installed after every refresh; UNRESOLVED without a preceding RESOLVED confuses state tracking listeners.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#refresh} and {@code #updateInternal}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful API fuzzer ({@code Stateful}, seed 1): the per-bundle event state machine oracle reported
 * "event INSTALLED after UNRESOLVED" and "event UNRESOLVED after INSTALLED"; minimized and compared with Equinox 3.21
 * (which fires neither) and the WF41 framework (same as the branch).
 *
 * <h3>Spec reference</h3>
 * BundleEvent.INSTALLED (OSGi Core R8, 10.1.8.1): "The bundle has been installed. See Also
 * BundleContext.installBundle(String)". BundleEvent.UNRESOLVED (10.1.8.9): "The bundle has been unresolved."
 * FrameworkWiring.refreshBundles (10.4.8.4): "Each bundle in the dependency closure that is in the RESOLVED state is
 * unresolved and thus moved to the INSTALLED state." - only RESOLVED bundles are unresolved, and no step installs a
 * bundle. Bundle.update (10.1.5.39): the steps fire only BundleEvent.UPDATED for a bundle that is INSTALLED.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:429-432}
 * ({@code refresh}: {@code fireBundleEvent(this, BundleEvent.UNRESOLVED)} unconditionally, then
 * {@code changeState(Bundle.INSTALLED)}, which {@code AbstractBundleState.changeState(int)} maps to
 * {@code BundleEvent.INSTALLED}); {@code :278} ({@code update}: {@code changeState(Bundle.INSTALLED,
 * BundleEvent.UNRESOLVED)} also when the bundle was INSTALLED). Same code on WF41.
 *
 * <h3>Root cause</h3>
 * The refresh/update paths fire events unconditionally instead of only for an actual RESOLVED to INSTALLED
 * transition, and the INSTALLED state change is published as an install event.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RefreshBundleEventsIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testRefreshOfResolvedBundleFiresNoInstalled: Refresh must not fire INSTALLED for an installed bundle,
 *   events: [UNRESOLVED, INSTALLED]
 * testRefreshOfUnresolvedBundleFiresNothing: Refresh of a bundle that is not resolved must fire no bundle event
 *   expected:&lt;[[]]&gt; but was:&lt;[[UNRESOLVED, INSTALLED]]&gt;
 * testUpdateOfInstalledBundleFiresOnlyUpdated: Update of an INSTALLED bundle must fire only UPDATED
 *   expected:&lt;[U[]PDATED]&gt; but was:&lt;[U[NRESOLVED, U]PDATED]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Refresh of a resolved bundle: UNRESOLVED (and possibly RESOLVED again), never INSTALLED. Refresh of an INSTALLED
 * bundle: no event. Update of an INSTALLED bundle: only UPDATED.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: refresh of an unresolved bundle fires nothing, update of an INSTALLED bundle fires [UPDATED], refresh of
 * a resolved bundle fires UNRESOLVED (and RESOLVED when it is resolved again), never INSTALLED.
 *
 * <h3>Suggested fix</h3>
 * In refresh fire UNRESOLVED only if the bundle was RESOLVED (or had a wiring) and change the state with
 * {@code changeState(Bundle.INSTALLED, 0)}; in update fire UNRESOLVED only for a RESOLVED bundle.
 *
 * <h3>Regression vs WF41</h3>
 * no - same events on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class RefreshBundleEventsIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRefreshOfResolvedBundleFiresNoInstalled() throws Exception {
        Bundle bundle = install("refresh-events-resolved", null);
        try {
            assertTrue("Precondition: resolved", frameworkWiring().resolveBundles(Collections.singleton(bundle)));
            List<String> events = recordEvents(bundle, true, false);
            assertFalse("Refresh must not fire INSTALLED for an installed bundle, events: " + events, events.contains("INSTALLED"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testRefreshOfUnresolvedBundleFiresNothing() throws Exception {
        Bundle bundle = install("refresh-events-unresolved", "org.acme.does.not.exist");
        try {
            assertEquals("Precondition: not resolved", Bundle.INSTALLED, bundle.getState());
            List<String> events = recordEvents(bundle, true, false);
            assertEquals("Refresh of a bundle that is not resolved must fire no bundle event", "[]", events.toString());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testUpdateOfInstalledBundleFiresOnlyUpdated() throws Exception {
        Bundle bundle = install("update-events-unresolved", "org.acme.does.not.exist");
        try {
            assertEquals("Precondition: not resolved", Bundle.INSTALLED, bundle.getState());
            List<String> events = recordEvents(bundle, false, true);
            assertEquals("Update of an INSTALLED bundle must fire only UPDATED", "[UPDATED]", events.toString());
        } finally {
            bundle.uninstall();
        }
    }

    private List<String> recordEvents(final Bundle bundle, boolean refresh, boolean update) throws Exception {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        SynchronousBundleListener listener = new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle().getBundleId() == bundle.getBundleId())
                    events.add(name(event.getType()));
            }
        };
        getSystemContext().addBundleListener(listener);
        try {
            if (update)
                bundle.update(new ByteArrayInputStream(jar(bundle.getSymbolicName(), "org.acme.does.not.exist")));
            if (refresh) {
                final CountDownLatch latch = new CountDownLatch(1);
                frameworkWiring().refreshBundles(Collections.singleton(bundle), new FrameworkListener() {
                    public void frameworkEvent(FrameworkEvent event) {
                        latch.countDown();
                    }
                });
                assertTrue("Refresh completes", latch.await(30, TimeUnit.SECONDS));
            }
        } finally {
            getSystemContext().removeBundleListener(listener);
        }
        return new ArrayList<String>(events);
    }

    private FrameworkWiring frameworkWiring() throws Exception {
        return getSystemContext().getBundle().adapt(FrameworkWiring.class);
    }

    private static String name(int type) {
        switch (type) {
        case BundleEvent.INSTALLED:
            return "INSTALLED";
        case BundleEvent.RESOLVED:
            return "RESOLVED";
        case BundleEvent.UNRESOLVED:
            return "UNRESOLVED";
        case BundleEvent.UPDATED:
            return "UPDATED";
        case BundleEvent.STARTING:
            return "STARTING";
        case BundleEvent.STARTED:
            return "STARTED";
        case BundleEvent.STOPPING:
            return "STOPPING";
        case BundleEvent.STOPPED:
            return "STOPPED";
        case BundleEvent.UNINSTALLED:
            return "UNINSTALLED";
        default:
            return "EVENT" + type;
        }
    }

    private Bundle install(String symbolicName, String importPackage) throws Exception {
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(jar(symbolicName, importPackage)));
    }

    private static byte[] jar(String symbolicName, String importPackage) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (importPackage != null)
            attributes.putValue(Constants.IMPORT_PACKAGE, importPackage);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return baos.toByteArray();
    }
}
