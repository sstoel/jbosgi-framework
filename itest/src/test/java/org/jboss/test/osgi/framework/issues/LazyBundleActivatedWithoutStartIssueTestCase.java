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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Loading a class from a bundle with {@code Bundle-ActivationPolicy: lazy} that was never started (INSTALLED or RESOLVED)
 * activates it: the framework starts it transiently and calls its BundleActivator, although lazy activation only applies
 * to a bundle that was started with its declared activation policy and waits in STARTING.
 *
 * <h2>Severity</h2>
 * major (raised from minor by the coordinator: this is the single root cause of ee/24, verified with a trial fix, so it
 * carries that consequence's severity). Spec deviation, pre-existing since AS7 and asserted by the baseline test
 * {@code bundle.activation.BundleActivationTestCase.testLoadClassActivation}); its consequence in WildFly is major: the
 * EE deployment processors load bundle classes while a deployment is processed, so every lazy bundle deployed through
 * management is activated by the container itself before it is started - its activator then runs without resource
 * injection (ee/24 LazyActivatorNotInjectedIssueTestCase) and a lazy WAB with classes is ACTIVE but not served
 * (lifecycle/03 note). Any class load by a management agent, a resolver hook, a debugger or a tool that inspects an
 * installed bundle starts it.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleRevisionFactory} / {@code UserBundleState.initLazyActivation} (lazy trigger
 * armed at install), {@code HostBundleClassLoader.preDefine/postDefine} + {@code LazyActivationTracker} +
 * {@code UserBundleState.activateLazily} (activates without checking that the bundle is waiting in STARTING).
 *
 * <h2>Found by</h2>
 * wildfly-ee-2 workstream (deep 2), root-causing the missing injection of lazy activators deployed in WildFly (ee/24);
 * Felix/Equinox differential (below).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.6.2 Lazy Activation Policy
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.lazy): "A lazy
 * activation policy indicates that the bundle, once started, must not be activated until it receives the first request
 * to load a class. ... When a bundle is started using a lazy activation policy, the following steps must be taken: A
 * Bundle Context is created for the bundle. The bundle state is moved to the STARTING state. The LAZY_ACTIVATION event
 * is fired. The system waits for a class load from the bundle to occur. ... The bundle is activated." 4.4.5 / Bundle.start:
 * only start (or the start level of a persistently started bundle) activates a bundle; Bundle.loadClass javadoc only
 * resolves an INSTALLED bundle ("If this bundle's state is INSTALLED, this method must attempt to resolve this bundle
 * before attempting to load the class").
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevisionFactory.java:98 ({@code userBundle.initLazyActivation()}
 * right after install), UserBundleState.java:138-141 ({@code awaitLazyActivation.set(isActivationLazy())}) and 158-169
 * ({@code activateLazily}: starts the bundle with START_TRANSIENT if the trigger is armed, whatever its state),
 * HostBundleClassLoader.java:91-111. Unchanged since the AS7 import (git blame: Thomas Diesler 2012).
 *
 * <h2>Root cause</h2>
 * The lazy trigger is armed when the bundle is installed instead of when it is started with START_ACTIVATION_POLICY
 * (step 5 of Bundle.start, UserBundleState.java:579-590), so the first class load of a bundle that nobody started starts it.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=LazyBundleActivatedWithoutStartIssueTestCase
 * </pre>
 * Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.AssertionError: Expected a lazy bundle that was never started to stay RESOLVED (4) when a class is loaded
 *   from it, but it is in state 32 expected:&lt;4&gt; but was:&lt;32&gt;
 * java.lang.AssertionError: Expected the activator of a lazy bundle that was never started not to be called when a class
 *   is loaded from it, but it was called expected null, but was:&lt;true&gt;
 * </pre>
 *
 * <h2>Expected</h2>
 * The class is loaded, the bundle is RESOLVED and not activated; after start(START_ACTIVATION_POLICY) it is STARTING and
 * the next class load activates it.
 *
 * <h2>Reference behaviour</h2>
 * Apache Felix 7.0.5: after loadClass the never-started lazy bundle is RESOLVED (4), activator not called. Eclipse Equinox
 * 3.21.0 with {@code osgi.compatibility.eagerStart.LazyActivation=false}: RESOLVED, not activated; start(POLICY) gives
 * STARTING (8), the next loadClass ACTIVE. Equinox's default ({@code osgi.compatibility.eagerStart.LazyActivation=true},
 * an Eclipse legacy option that marks lazy bundles as started when they are installed) activates it like jbosgi - a
 * deliberate, configurable compatibility mode, not the spec behaviour. (Differential program:
 * explore/wildfly-ee-2/lazydiff, JDK 25.)
 *
 * <h2>Suggested fix (not applied)</h2>
 * Arm the lazy trigger only in step 5 of startInternal (START_ACTIVATION_POLICY with a lazy policy, state set to STARTING)
 * and when the start level starts a bundle persistently marked "started with declared activation"; clear it on stop.
 * If the old behaviour is wanted for compatibility, make it an opt-in framework property like Equinox does. The baseline
 * test BundleActivationTestCase.testLoadClassActivation (and the ...WithInclude/WithExclude variants) asserts the current
 * behaviour and must be changed with the fix.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing).
 *
 * <h2>Trial fix</h2>
 * Verified (reverted): returning from {@code UserBundleState.activateLazily} unless the bundle is STARTING (issue folder
 * trial-fix.diff, trial.log) makes this test pass and also makes ee/24 LazyActivatorNotInjectedIssueTestCase pass on the
 * patched server (ee/24 is a consequence of this root cause); ee/22 still fails (independent). Baseline tests that assert
 * the old behaviour then fail as expected: BundleActivationTestCase.testLoadClassActivation, testLoadClassActivationWithInclude,
 * testLoadClassActivationWithExclude, testTransitiveActivation.
 */
public class LazyBundleActivatedWithoutStartIssueTestCase extends OSGiTest {

    private Framework framework;

    @Before
    public void setUp() throws Exception {
        System.clearProperty(LazyActivator.STARTED);
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(LazyActivator.STARTED);
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testLoadClassDoesNotStartLazyBundle() throws Exception {
        Bundle lazy = installLazyBundle();
        lazy.loadClass(Trigger.class.getName());
        assertEquals("Expected a lazy bundle that was never started to stay RESOLVED (4) when a class is loaded from it, but it is in state " + lazy.getState(),
                Bundle.RESOLVED, lazy.getState());
    }

    @Test
    public void testActivatorNotCalledWithoutStart() throws Exception {
        Bundle lazy = installLazyBundle();
        lazy.loadClass(Trigger.class.getName());
        assertNull("Expected the activator of a lazy bundle that was never started not to be called when a class is loaded from it, but it was called",
                System.getProperty(LazyActivator.STARTED));
    }

    private Bundle installLazyBundle() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "lazy-not-started");
        archive.addClasses(LazyActivator.class, Trigger.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("lazy-not-started");
                builder.addBundleActivator(LazyActivator.class);
                builder.addImportPackages("org.osgi.framework");
                builder.addBundleActivationPolicy(Constants.ACTIVATION_LAZY);
                return builder.openStream();
            }
        });
        Bundle bundle = framework.getBundleContext().installBundle("lazy-not-started", archive.as(ZipExporter.class).exportAsInputStream());
        assertEquals("Precondition: installed", Bundle.INSTALLED, bundle.getState());
        return bundle;
    }

    /** The activator of the lazy bundle, it reports through a system property (the bundle has its own copy of the class) */
    public static class LazyActivator implements BundleActivator {

        static final String STARTED = "issues.lazy.notstarted.activated";

        @Override
        public void start(BundleContext context) {
            System.setProperty(STARTED, "true");
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** A class of the lazy bundle */
    public static class Trigger {
    }
}
