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

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * Bundle.start(int) of an ACTIVE bundle returns without setting the autostart setting: a bundle that was started
 * transiently (or with another activation option) and is then started persistently stays "Stopped" (or keeps the old
 * activation option), so it is not started again when the start level goes down and up or the framework restarts.
 *
 * <h3>Severity</h3>
 * major - "make this running bundle permanent" is exactly what Bundle.start() is for (management consoles, deployers,
 * the WildFly osgi subsystem's bundle :start on an already running bundle); the call succeeds silently but the bundle
 * does not come back after the next start level change or restart. isPersistentlyStarted()/isActivationPolicyUsed()
 * report the old setting.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#startInternalNow}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 7): seeded start-level differential {@code fa.StartLevelModel}
 * (7 bundles incl. lazy ones and one without activator; start with all four option combinations, stop (transient),
 * bundle and framework start levels, initial start level, lazy class loads, uninstall) against Equinox 3.21 and
 * Felix 7.0.5, seed 5, 200 steps: step 2 {@code start(START_TRANSIENT)}, step 6 {@code start(0)} of the ACTIVE
 * bundle: Equinox and Felix isPersistentlyStarted() true, jbosgi false.
 *
 * <h3>Spec reference</h3>
 * Bundle.start(int) javadoc (OSGi Core R8, 10.1.5.36,
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.start-int-):
 * "Otherwise, the following steps are required to start this bundle: 1. If this bundle is in the process of being
 * activated or deactivated then this method must wait ... 2. If the START_TRANSIENT option is not set then set this
 * bundle's autostart setting to Started with declared activation if the START_ACTIVATION_POLICY option is set or
 * Started with eager activation if not set. When the Framework is restarted and this bundle's autostart setting is
 * not Stopped, this bundle must be automatically started. 3. If this bundle's state is ACTIVE then this method
 * returns immediately." (the autostart setting is written before the ACTIVE check).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:549-556}:
 * {@code // #2 If this bundle's state is ACTIVE then this method returns immediately. if (getState() == ACTIVE)
 * return;} comes before {@code // #3 Set this bundle's autostart setting persistAutoStartSettings(options);} - the
 * steps are numbered and ordered differently from the javadoc. Pre-existing (2c04f383/c93e370f, 2013); unchanged by
 * the R8 branch.
 *
 * <h3>Root cause</h3>
 * Steps 2 and 3 of Bundle.start are swapped.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=StartActiveBundleAutostartIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testStartOfActiveBundleIsPersisted: start() of the ACTIVE (transiently started) bundle must set the autostart
 *   setting: isPersistentlyStarted()
 * testStartOfActiveBundleChangesActivationOption: start() without START_ACTIVATION_POLICY of the ACTIVE bundle must
 *   set the autostart setting to eager activation: isActivationPolicyUsed() must be false
 * testStartOfActiveBundleSurvivesStartLevelCycle: The persistently started bundle must be started again when the
 *   start level is raised again expected:&lt;[ACTIVE]&gt; but was:&lt;[RESOLVED]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * After start() the bundle is persistently started with eager activation, and it is started again when its start
 * level is reached again.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 (probe {@code fa.ProbeStartActive}): isPersistentlyStarted() true after start() of the
 * ACTIVE transiently started bundle; isActivationPolicyUsed() true after start(START_ACTIVATION_POLICY) and false after
 * the following start(); the bundle is ACTIVE again after the start level went down and up.
 *
 * <h3>Suggested fix</h3>
 * Call {@code persistAutoStartSettings(options)} before the {@code getState() == ACTIVE} check.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class StartActiveBundleAutostartIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testStartOfActiveBundleIsPersisted() throws Exception {
        Bundle bundle = installBundle(getBundle("start-active-persist"));
        try {
            bundle.start(Bundle.START_TRANSIENT);
            assertEquals(Bundle.ACTIVE, bundle.getState());
            assertFalse(bundle.adapt(BundleStartLevel.class).isPersistentlyStarted());
            bundle.start();
            assertTrue("start() of the ACTIVE (transiently started) bundle must set the autostart setting: isPersistentlyStarted()",
                    bundle.adapt(BundleStartLevel.class).isPersistentlyStarted());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testStartOfActiveBundleChangesActivationOption() throws Exception {
        Bundle bundle = installBundle(getBundle("start-active-policy"));
        try {
            bundle.start(Bundle.START_ACTIVATION_POLICY);
            assertEquals(Bundle.ACTIVE, bundle.getState());
            assertTrue(bundle.adapt(BundleStartLevel.class).isActivationPolicyUsed());
            bundle.start();
            assertFalse("start() without START_ACTIVATION_POLICY of the ACTIVE bundle must set the autostart setting to eager activation: isActivationPolicyUsed() must be false",
                    bundle.adapt(BundleStartLevel.class).isActivationPolicyUsed());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testStartOfActiveBundleSurvivesStartLevelCycle() throws Exception {
        FrameworkStartLevel frameworkStartLevel = getFramework().adapt(FrameworkStartLevel.class);
        int initialLevel = frameworkStartLevel.getStartLevel();
        Bundle bundle = installBundle(getBundle("start-active-cycle"));
        try {
            bundle.adapt(BundleStartLevel.class).setStartLevel(initialLevel + 1);
            setFrameworkStartLevel(frameworkStartLevel, initialLevel + 1);
            bundle.start(Bundle.START_TRANSIENT);
            assertEquals(Bundle.ACTIVE, bundle.getState());
            bundle.start();

            setFrameworkStartLevel(frameworkStartLevel, initialLevel);
            assertEquals(Bundle.RESOLVED, bundle.getState());
            setFrameworkStartLevel(frameworkStartLevel, initialLevel + 1);
            assertEquals("The persistently started bundle must be started again when the start level is raised again", "ACTIVE", stateName(bundle.getState()));
        } finally {
            setFrameworkStartLevel(frameworkStartLevel, initialLevel);
            bundle.uninstall();
        }
    }

    private void setFrameworkStartLevel(FrameworkStartLevel frameworkStartLevel, int level) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        frameworkStartLevel.setStartLevel(level, new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED)
                    latch.countDown();
            }
        });
        assertTrue("Start level change to " + level + " completed", latch.await(20, TimeUnit.SECONDS));
    }

    private static String stateName(int state) {
        switch (state) {
        case Bundle.INSTALLED: return "INSTALLED";
        case Bundle.RESOLVED: return "RESOLVED";
        case Bundle.ACTIVE: return "ACTIVE";
        default: return "STATE" + state;
        }
    }

    private JavaArchive getBundle(final String name) {
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
