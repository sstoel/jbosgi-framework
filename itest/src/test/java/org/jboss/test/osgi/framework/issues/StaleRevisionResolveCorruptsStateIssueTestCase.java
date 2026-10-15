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
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle that was updated while INSTALLED keeps its old, never resolved revision in the resolver environment. When
 * a later resolve operation resolves that stale revision (any resolve of a bundle with an optional import does), the framework fires RESOLVED for the bundle and sets the
 * bundle's state to RESOLVED although the bundle is ACTIVE: its activator is never stopped, Bundle.stop() becomes a
 * no-op and the services it registered stay registered (also after it is uninstalled).
 *
 * <h3>Severity</h3>
 * major - silent corruption of the bundle state machine reachable with ordinary API calls (install, update before the
 * first start, start, resolve another bundle that has an optional import); the bundle can no longer be stopped and leaks its services.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkResolverImpl#setBundleStatesToResolved} /
 * {@code #sendBundleResolvedEvents}, and the update of an unresolved bundle ({@code UserBundleState#updateInternal}),
 * which keeps the old revision in the environment; jbosgi-resolver api
 * {@code org.jboss.osgi.resolver.spi.AbstractResolver#appendOptionalHostBundles}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful API fuzzer ({@code Stateful}, seeds 108, 110, 114, 128, 156; 1 thread, 400 operations):
 * the event state machine oracle reported "event RESOLVED after STARTED" and the quiescent check reported "bundle sg in
 * state UNINSTALLED still uses service 19" (a service it registered itself). A verbose replay showed an ACTIVE bundle
 * receiving two RESOLVED events and becoming RESOLVED during the resolve of an unrelated bundle; the bundle had been
 * updated twice while INSTALLED. Minimized to one update; compared with Equinox 3.21 and WF41.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.2 Bundle State / Figure 4.2: an ACTIVE bundle leaves ACTIVE only through STOPPING (Bundle.stop,
 * update, uninstall, refresh). Bundle.stop javadoc (10.1.5.35): "If this bundle's state is not STARTING or ACTIVE
 * then this method returns immediately." - which is what happens on the corrupted bundle. BundleEvent.RESOLVED
 * (10.1.8.3): "The bundle has been resolved." Bundle.update (10.1.5.38) step: "This bundle's state is set to
 * INSTALLED"; the old revision of a bundle that was not resolved is not in use and must not be resolved later.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkResolverImpl.java:346-355}
 * ({@code setBundleStatesToResolved}: {@code bundleState.changeState(Bundle.RESOLVED, 0)} for the bundle of every
 * revision in the wire map, without checking that the revision is the bundle's current revision or that the bundle
 * is INSTALLED) and {@code :357-369} ({@code sendBundleResolvedEvents}, same for the RESOLVED event). After
 * {@code Bundle.update} of an INSTALLED bundle, {@code BundleRevisions.getRevisions()} still returns 2 revisions and
 * the old one stays resolvable in the environment. jbosgi-resolver
 * {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResolver.java:143-158}
 * ({@code appendOptionalHostBundles}) adds every bundle revision of the environment that has no wiring - including
 * such stale revisions - as an optional resource as soon as one resource has an optional package import, so the
 * stale revision is resolved by an unrelated resolve. Same code on WF41.
 *
 * <h3>Root cause</h3>
 * Stale, never resolved revisions stay in the environment after an update, and the resolve result is applied to the
 * bundle (state and event) instead of only to the revision.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=StaleRevisionResolveCorruptsStateIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testActiveBundleStaysActive: Resolving another bundle must not change the state of an ACTIVE bundle
 *   expected:&lt;32&gt; but was:&lt;4&gt;
 * testStopUnregistersServices: Bundle.stop must unregister the services of the bundle, still registered:
 *   [ServiceState{service.id=12, objectClass=[java.lang.Runnable], ...}], bundle state 4 expected null, but was:&lt;[...]&gt;
 * Probe event log: [other INSTALLED, other RESOLVED, stale-bundle RESOLVED] (two RESOLVED events after two updates).
 * </pre>
 *
 * <h3>Expected</h3>
 * The ACTIVE bundle stays ACTIVE, receives no RESOLVED event, and Bundle.stop() stops it and unregisters its services.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: one revision after the update, the bundle stays ACTIVE, stop() fires STOPPING/STOPPED and unregisters
 * the service.
 *
 * <h3>Suggested fix</h3>
 * Remove (uninstall from the environment) the previous revision when a bundle that is not resolved is updated, and in
 * setBundleStatesToResolved/sendBundleResolvedEvents only handle the bundle's current revision when the bundle is
 * INSTALLED.
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class StaleRevisionResolveCorruptsStateIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testActiveBundleStaysActive() throws Exception {
        Bundle bundle = install("stale-revision-state");
        Bundle other = installOther("stale-revision-other-1");
        try {
            bundle.update(new ByteArrayInputStream(jar("stale-revision-state")));
            bundle.start();
            assertEquals("Precondition: ACTIVE", Bundle.ACTIVE, bundle.getState());

            getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(other));
            assertEquals("Resolving another bundle must not change the state of an ACTIVE bundle", Bundle.ACTIVE, bundle.getState());
        } finally {
            other.uninstall();
            bundle.uninstall();
        }
    }

    @Test
    public void testStopUnregistersServices() throws Exception {
        Bundle bundle = install("stale-revision-services");
        Bundle other = installOther("stale-revision-other-2");
        try {
            bundle.update(new ByteArrayInputStream(jar("stale-revision-services")));
            bundle.start();
            bundle.getBundleContext().registerService(Runnable.class.getName(), new Runnable() {
                public void run() {
                }
            }, new Hashtable<String, Object>());

            getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(other));
            bundle.stop();
            ServiceReference<?>[] registered = bundle.getRegisteredServices();
            assertNull("Bundle.stop must unregister the services of the bundle, still registered: "
                    + (registered != null ? Arrays.asList(registered) : null) + ", bundle state " + bundle.getState(), registered);
        } finally {
            other.uninstall();
            bundle.uninstall();
        }
    }

    private Bundle install(String symbolicName) throws Exception {
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(jar(symbolicName)));
    }

    // The other bundle has an optional import that no bundle exports
    private Bundle installOther(String symbolicName) throws Exception {
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(jar(symbolicName, "org.acme.missing;resolution:=optional")));
    }

    private static byte[] jar(String symbolicName) throws Exception {
        return jar(symbolicName, null);
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
