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
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * {@code BundleContext.removeBundleListener(l)} and {@code removeFrameworkListener(l)} for a listener that the
 * bundle never added remove the bundle's only registered listener: if the context bundle has exactly one
 * Bundle/Framework listener, any remove call, whatever its argument, drops it.
 *
 * <h3>Severity</h3>
 * major. A real bundle can hit it: cleanup code that removes listeners defensively (e.g. a component's deactivate,
 * a tracker's close after a failed open, a library that removes "its" listener on the shared bundle context), or two
 * independent components of one bundle with one listener each where one removes a listener twice. The surviving
 * component then silently stops receiving bundle events (extender patterns miss INSTALLED/STARTED/STOPPED) or
 * framework events (refresh/start level completion listeners never fire). Removing an unknown listener is required to
 * do nothing.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl.removeBundleListener} and
 * {@code removeFrameworkListener} (called by {@code AbstractBundleContext.removeBundleListener/removeFrameworkListener}).
 *
 * <h3>Found by</h3>
 * static workstream: SpotBugs 4.9.8 / Error Prone triage of FrameworkEventsImpl (JLM_JSR166_UTILCONCURRENT_MONITORENTER
 * on the listener maps, ModifyCollectionInEnhancedForLoop at FrameworkEventsImpl.java:592) - manual review of the
 * synchronized listener-list code found the size-based shortcut.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.BundleContext.removeBundleListener(BundleListener)} javadoc (osgi.core 8.0.0): "Removes
 * the specified BundleListener object from the context bundle's list of listeners. If listener is not contained in
 * the context bundle's list of listeners, this method does nothing."<br>
 * {@code BundleContext.removeFrameworkListener(FrameworkListener)}: "Removes the specified FrameworkListener object
 * from the context bundle's list of listeners. If listener is not contained in the context bundle's list of
 * listeners, this method does nothing."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:131-152}
 * ({@code removeBundleListener}: {@code if (registrations.size() > 1) { ...remove the matching one... } else
 * removeBundleListeners(bundle);}) and lines 182-193 ({@code removeFrameworkListener}: {@code if (listeners.size() > 1)
 * listeners.remove(listener); else removeFrameworkListeners(bundleState);}). The else branch removes the whole list
 * without checking that its single entry is the given listener. Since 2012/2013 (Thomas Diesler, 7dfb6fdc /
 * 798a1ca7); identical on WF41.
 *
 * <h3>Root cause</h3>
 * Shortcut "one entry left: drop the list" assumes the entry is the listener being removed.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=RemoveUnknownListenerDropsListenerIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testRemoveUnknownBundleListenerKeepsRegisteredListener: org.junit.ComparisonFailure: removeBundleListener(listener
 *   not added) must do nothing; the registered listener must still get the INSTALLED event
 *   expected:&lt;[[remove-unknown-other]]&gt; but was:&lt;[[]]&gt;
 * testRemoveUnknownFrameworkListenerKeepsRegisteredListener: java.lang.AssertionError: removeFrameworkListener(listener
 *   not added) must do nothing; the registered listener must still get PACKAGES_REFRESHED (waited 10 s)
 * </pre>
 * Control: the same two tests without the remove call pass (the listeners get the events).
 * <pre>
 * </pre>
 *
 * <h3>Expected</h3>
 * The registered listener keeps receiving events: removing a listener that is not registered does nothing.
 *
 * <h3>Reference behaviour</h3>
 * Standalone JDK 25 program with the same two scenarios: Equinox 3.21.0 and Felix 7.0.5 deliver the INSTALLED event
 * and PACKAGES_REFRESHED to the registered listener after the remove call for the unknown listener.
 *
 * <h3>Suggested fix</h3>
 * Remove the matching entry (identity) and drop the list only when it became empty, in both methods.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class RemoveUnknownListenerDropsListenerIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRemoveUnknownBundleListenerKeepsRegisteredListener() throws Exception {
        Bundle owner = installBundle(getArchive("remove-unknown-bundle-listener"));
        Bundle other = null;
        try {
            owner.start();
            BundleContext context = owner.getBundleContext();
            final List<String> received = Collections.synchronizedList(new ArrayList<String>());
            SynchronousBundleListener registered = new SynchronousBundleListener() {
                public void bundleChanged(BundleEvent event) {
                    if (event.getType() == BundleEvent.INSTALLED)
                        received.add(event.getBundle().getSymbolicName());
                }
            };
            BundleListener neverAdded = new BundleListener() {
                public void bundleChanged(BundleEvent event) {
                }
            };
            context.addBundleListener(registered);
            context.removeBundleListener(neverAdded);

            other = installBundle(getArchive("remove-unknown-other"));
            assertEquals("removeBundleListener(listener not added) must do nothing; the registered listener must still get the INSTALLED event",
                    "[remove-unknown-other]", received.toString());
        } finally {
            if (other != null)
                other.uninstall();
            owner.uninstall();
        }
    }

    @Test
    public void testRemoveUnknownFrameworkListenerKeepsRegisteredListener() throws Exception {
        Bundle owner = installBundle(getArchive("remove-unknown-framework-listener"));
        Bundle refreshed = installBundle(getArchive("remove-unknown-refreshed"));
        try {
            owner.start();
            refreshed.start();
            BundleContext context = owner.getBundleContext();
            final CountDownLatch packagesRefreshed = new CountDownLatch(1);
            FrameworkListener registered = new FrameworkListener() {
                public void frameworkEvent(FrameworkEvent event) {
                    if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED)
                        packagesRefreshed.countDown();
                }
            };
            FrameworkListener neverAdded = new FrameworkListener() {
                public void frameworkEvent(FrameworkEvent event) {
                }
            };
            context.addFrameworkListener(registered);
            context.removeFrameworkListener(neverAdded);

            // PACKAGES_REFRESHED is delivered to every framework listener; refresh an unrelated bundle so that the
            // owner (and its listener) is not stopped by the refresh
            getSystemContext().getBundle().adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(refreshed));
            assertTrue("removeFrameworkListener(listener not added) must do nothing; the registered listener must still get PACKAGES_REFRESHED (waited 10 s)",
                    packagesRefreshed.await(10, TimeUnit.SECONDS));
        } finally {
            refreshed.uninstall();
            owner.uninstall();
        }
    }

    private JavaArchive getArchive(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return archive;
    }
}
