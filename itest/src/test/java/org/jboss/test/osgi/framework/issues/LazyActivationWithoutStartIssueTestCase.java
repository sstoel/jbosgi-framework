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

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.simple.bundleA.SimpleActivator;
import org.jboss.test.osgi.framework.simple.bundleA.SimpleService;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle with Bundle-ActivationPolicy: lazy is activated (its activator runs, the bundle becomes ACTIVE) by the
 * first class load although it was never started, or after it was stopped: the lazy trigger depends on the manifest
 * only, not on the bundle having been started with START_ACTIVATION_POLICY.
 *
 * <h3>Severity</h3>
 * major - lazy bundles (most Declarative Services, Blueprint and Equinox-built bundles declare the lazy policy) start
 * themselves as soon as anybody loads one of their classes: an administrator's Bundle.stop() does not keep them
 * stopped (the next class load from an importer brings them back, with their services), and installed bundles that
 * were deliberately not started (start level, provisioning that starts bundles later, WildFly osgi subsystem
 * activation policy) run their activators early.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState} (awaitLazyActivation /
 * activateLazily) and {@code HostBundleClassLoader} / {@code LazyActivationTracker}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 7): seeded start-level differential {@code fa.StartLevelModel} against
 * Felix 7.0.5 and Equinox 3.21, seed 2 step 56: {@code loadClass} from the RESOLVED (never started) lazy bundle s2
 * activated it on jbosgi (STARTING, STARTED), Felix left it RESOLVED. Probe {@code fa.ProbeLazyResolved} (lazy bundle
 * with activator: resolved only / started with START_ACTIVATION_POLICY and stopped / transiently started and then
 * stopped by a start level decrease; then loadClass).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.6.2 Lazy Activation Policy
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "A lazy activation policy indicates
 * that the bundle, once started, must not be activated until it receives the first request to load a class." and
 * "When a bundle is started using a lazy activation policy, the following steps must be taken: ... The bundle state
 * is moved to the STARTING state. The LAZY_ACTIVATION event is fired. The system waits for a class load from the
 * bundle to occur. The normal STARTING event is fired. The bundle is activated." - the class load completes a start
 * that was requested; it does not start a bundle that is RESOLVED.<br>
 * Bundle.stop(int) javadoc: "If the STOP_TRANSIENT option is not set then set this bundle's persistent autostart
 * setting to Stopped. When the Framework is restarted and this bundle's autostart setting is Stopped, this bundle
 * must not be automatically started." / Bundle.start(int): the lazy path is taken "If the START_ACTIVATION_POLICY
 * option is set and this bundle's declared activation policy is lazy".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:138-142}
 * ({@code initLazyActivation}: {@code awaitLazyActivation.set(isActivationLazy())} - from the manifest, when the
 * bundle is created/resolved, independent of any start) and {@code :158-169} ({@code activateLazily} starts the bundle
 * whenever the flag is set and the start level allows, regardless of the bundle state and autostart setting);
 * {@code HostBundleClassLoader.java:91-111} / {@code LazyActivationTracker.java:68-100} call it on the first class
 * definition. stop() does not reset the flag. Pre-existing (2c04f383, 2013); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * "Awaiting lazy activation" is modelled as a property of the bundle's manifest instead of the state reached by
 * start(START_ACTIVATION_POLICY) (STARTING).
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=LazyActivationWithoutStartIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testClassLoadDoesNotStartResolvedLazyBundle: Loading a class from a lazy bundle that was never started must not
 *   activate it expected:&lt;[RESOLVED]&gt; but was:&lt;[ACTIVE]&gt;
 * testClassLoadDoesNotStartStoppedLazyBundle: Loading a class from a lazy bundle that was stopped must not activate it
 *   expected:&lt;[RESOLVED]&gt; but was:&lt;[ACTIVE]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The bundle stays RESOLVED; only a bundle in STARTING (started with START_ACTIVATION_POLICY) is activated by a class
 * load.
 *
 * <h3>Reference behaviour</h3>
 * Felix 7.0.5: RESOLVED in both cases. Equinox 3.21: RESOLVED after start(START_ACTIVATION_POLICY) + stop() and a class
 * load (the never-started case does not apply to Equinox, which marks lazy bundles as started with declared
 * activation at install).
 *
 * <h3>Suggested fix</h3>
 * Set awaitLazyActivation only in startInternalNow when the bundle enters STARTING with the activation policy (step
 * 5), reset it in stop/uninstall/refresh, and let activateLazily act only on a bundle in STARTING.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class LazyActivationWithoutStartIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testClassLoadDoesNotStartResolvedLazyBundle() throws Exception {
        Bundle bundle = installBundle(getLazyBundle("lazy-never-started"));
        try {
            getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle));
            assertEquals(Bundle.RESOLVED, bundle.getState());
            bundle.loadClass(SimpleService.class.getName());
            assertEquals("Loading a class from a lazy bundle that was never started must not activate it", "RESOLVED", stateName(bundle.getState()));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testClassLoadDoesNotStartStoppedLazyBundle() throws Exception {
        Bundle bundle = installBundle(getLazyBundle("lazy-stopped"));
        try {
            bundle.start(Bundle.START_ACTIVATION_POLICY);
            assertEquals(Bundle.STARTING, bundle.getState());
            bundle.stop();
            assertEquals(Bundle.RESOLVED, bundle.getState());
            bundle.loadClass(SimpleService.class.getName());
            assertEquals("Loading a class from a lazy bundle that was stopped must not activate it", "RESOLVED", stateName(bundle.getState()));
        } finally {
            bundle.uninstall();
        }
    }

    private static String stateName(int state) {
        switch (state) {
        case Bundle.INSTALLED: return "INSTALLED";
        case Bundle.RESOLVED: return "RESOLVED";
        case Bundle.STARTING: return "STARTING";
        case Bundle.ACTIVE: return "ACTIVE";
        default: return "STATE" + state;
        }
    }

    private JavaArchive getLazyBundle(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(SimpleActivator.class, SimpleService.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addBundleActivator(SimpleActivator.class);
                builder.addBundleActivationPolicy("lazy");
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive;
    }
}
