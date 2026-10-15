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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A failed Bundle.update leaves a RESOLVED bundle in the INSTALLED state and fires UNRESOLVED, although the original
 * revision stays current and wired; afterwards FrameworkWiring.resolveBundles returns true for the bundle while it
 * keeps reporting INSTALLED.
 *
 * <h3>Severity</h3>
 * major - any failing update (unreadable/corrupt content, invalid manifest, symbolic name/version collision) of a
 * resolved bundle corrupts its lifecycle state: the bundle is reported INSTALLED (management consoles, JMX, the
 * WildFly osgi subsystem show it unresolved), listeners and BundleTrackers get an UNRESOLVED event without a later
 * RESOLVED event, resolveBundles cannot repair the state, and the next start() goes from INSTALLED to ACTIVE without
 * a RESOLVED event. Its importers stay wired to it (they are not refreshed).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#updateInternal}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 1): bundle-hook differential {@code fa.BundleHookModel}
 * (bsnversion=managed and single, seeds 1 and 2) against Equinox 3.21 and Felix 7.0.5: after an update that failed
 * with DUPLICATE_BUNDLE_ERROR jbosgi fired UNRESOLVED and the bundle diverged in all later steps. Minimised with
 * the probes {@code fa.ProbeBundleHooks} (three failure kinds: collision, invalid manifest, not a zip) and
 * {@code fa.ProbeFailedUpdate} (resolveBundles/start/refresh afterwards).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.9 Updating Bundles and Bundle.update(InputStream) javadoc
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.update-InputStream-):
 * "3. The updated version of this bundle is read from the input stream and installed. If the Framework is unable to
 * install the updated version of this bundle, the original version of this bundle must be restored and a
 * BundleException must be thrown after completion of the remaining steps. 4. This bundle's state is set to INSTALLED.
 * 5. If the updated version of this bundle was successfully installed, a bundle event of type BundleEvent.UPDATED is
 * fired." The original version that must be restored includes its RESOLVED state.<br>
 * FrameworkWiring.resolveBundles javadoc: "@return true if all specified bundles are resolved; false otherwise."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:278}
 * ({@code changeState(Bundle.INSTALLED, BundleEvent.UNRESOLVED);} before {@code createUpdateRevision(input)} at
 * :286, which throws for unreadable content, manifest errors and the uniqueness check at :366); the catch block
 * (:292-298) only restarts an ACTIVE bundle and never restores RESOLVED. Pre-existing (3c8ccc02, 2011); unchanged
 * by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The state change to INSTALLED (with its UNRESOLVED event) is done before the new revision has been created, and
 * the failure path does not restore the previous state.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FailedUpdateStateIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testFailedUpdateKeepsResolvedState: After a failed update the original version must be restored: state
 *   expected:&lt;[RESOLVED]&gt; but was:&lt;[INSTALLED]&gt;
 * testFailedUpdateFiresNoEvent: A failed update must not fire bundle events expected:&lt;[]&gt; but
 *   was:&lt;[UNRESOLVED]&gt;
 * testResolveBundlesAfterFailedUpdate: resolveBundles returned true, so the bundle must be RESOLVED expected:&lt;[RESOLVED]&gt;
 *   but was:&lt;[INSTALLED]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * After the failed update the bundle is RESOLVED as before, no event was fired, and resolveBundles agrees with the
 * bundle state.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: state RESOLVED after the failed update, no event (an ACTIVE bundle is stopped and
 * restarted: STOPPING STOPPED STARTING STARTED, no UNRESOLVED; jbosgi fires UNRESOLVED there too).
 *
 * <h3>Suggested fix</h3>
 * Create the update revision (content, metadata, uniqueness check) first; only when that succeeded change the state
 * to INSTALLED with the UNRESOLVED event (only for a bundle that was resolved, see fuzz/14) and fire UPDATED. On
 * failure leave the state as it was (restart if it was active).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class FailedUpdateStateIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFailedUpdateKeepsResolvedState() throws Exception {
        Bundle bundle = installBundle(getExporter("failed-update-state"));
        try {
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle)));
            assertEquals(Bundle.RESOLVED, bundle.getState());
            failUpdate(bundle);
            assertEquals("After a failed update the original version must be restored: state", "RESOLVED", stateName(bundle.getState()));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFailedUpdateFiresNoEvent() throws Exception {
        final Bundle bundle = installBundle(getExporter("failed-update-events"));
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        SynchronousBundleListener listener = new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle() == bundle)
                    events.add(eventName(event.getType()));
            }
        };
        try {
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle)));
            getSystemContext().addBundleListener(listener);
            failUpdate(bundle);
            assertEquals("A failed update must not fire bundle events", "[]", events.toString());
        } finally {
            getSystemContext().removeBundleListener(listener);
            bundle.uninstall();
        }
    }

    @Test
    public void testResolveBundlesAfterFailedUpdate() throws Exception {
        Bundle bundle = installBundle(getExporter("failed-update-resolve"));
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(bundle)));
            failUpdate(bundle);
            boolean resolved = frameworkWiring.resolveBundles(Arrays.asList(bundle));
            assertTrue("resolveBundles must succeed for the unchanged bundle", resolved);
            assertEquals("resolveBundles returned true, so the bundle must be RESOLVED", "RESOLVED", stateName(bundle.getState()));
        } finally {
            bundle.uninstall();
        }
    }

    private void failUpdate(Bundle bundle) {
        try {
            bundle.update(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
            fail("Update with content that is not a JAR must fail");
        } catch (BundleException ex) {
            // expected
        }
    }

    private static String stateName(int state) {
        switch (state) {
        case Bundle.INSTALLED: return "INSTALLED";
        case Bundle.RESOLVED: return "RESOLVED";
        case Bundle.ACTIVE: return "ACTIVE";
        case Bundle.UNINSTALLED: return "UNINSTALLED";
        default: return "STATE" + state;
        }
    }

    private static String eventName(int type) {
        switch (type) {
        case BundleEvent.UNRESOLVED: return "UNRESOLVED";
        case BundleEvent.RESOLVED: return "RESOLVED";
        case BundleEvent.UPDATED: return "UPDATED";
        case BundleEvent.INSTALLED: return "INSTALLED";
        default: return "EVENT" + type;
        }
    }

    private JavaArchive getExporter(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(A.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addExportPackages(A.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}
