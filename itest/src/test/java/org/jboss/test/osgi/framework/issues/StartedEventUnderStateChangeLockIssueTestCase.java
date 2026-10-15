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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;

/**
 * The framework holds the bundle's "state change" lock while it delivers BundleEvent.STARTED to
 * SynchronousBundleListeners; the specification requires that this lock is NOT held for STARTED.
 *
 * <h3>Severity</h3>
 * minor - edge-case spec deviation. A SynchronousBundleListener (for example a BundleTracker customizer of an extender)
 * that reacts to STARTED by handing a lifecycle operation on that bundle to another thread and waiting for it blocks
 * until the start returns; with a wait longer than the LockManager timeout the other thread fails with a lock timeout.
 * Equinox 3.18 and Felix 7 let the other thread proceed immediately.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin#startBundle} /
 * {@code UserBundleState#startInternal} / {@code AbstractBundleState#changeState}.
 *
 * <h3>Found by</h3>
 * compendium-gaps workstream (closing UNVERIFIED rows framework.api#212/#213 of the spec matrix), Equinox/Felix
 * differential probe {@code explore/compendium-gaps/h/hsrc/FwHarness.java} ({@code s212}): a SynchronousBundleListener
 * starts another thread that calls {@code Bundle.stop()} (or {@code start()}) on the event's bundle and joins it.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 10.1.31 {@code SynchronousBundleListener}
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.SynchronousBundleListener):
 * "For BundleEvent types STARTED and LAZY_ACTIVATION, the Framework must not hold the referenced bundle's "state
 * change" lock when the BundleEvent is delivered to a SynchronousBundleListener. For the other BundleEvent types, the
 * Framework must hold the referenced bundle's "state change" lock when the BundleEvent is delivered to a
 * SynchronousBundleListener." (matrix ids framework.api#212, #213)
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:605-618}
 * ({@code startBundle} takes {@code lockManager.lockItems(Method.START, items)} and releases it only after
 * {@code startInternal} returned) and {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:650-652}
 * ({@code changeState(Bundle.ACTIVE)} fires STARTED synchronously inside {@code startInternal}, i.e. under the lock;
 * {@code :589} does the same for LAZY_ACTIVATION). Unchanged since commit d4c9fc53 (2012, AS7-6056).
 *
 * <h3>Root cause</h3>
 * The bundle lock of the START operation spans the complete start algorithm including the final state change, and the
 * state change publishes the bundle event synchronously. Nothing releases the lock before STARTED (or LAZY_ACTIVATION)
 * is delivered.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=StartedEventUnderStateChangeLockIssueTestCase} (in jbosgi-framework, after
 * {@code mvn -Dmaven.compiler.proc=full install -DskipTests}).
 *
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: STARTED must be delivered without the bundle's state change lock: a Bundle.stop() on
 *   another thread must be able to complete while the SynchronousBundleListener handles STARTED, but it was still
 *   blocked after 5000 ms expected:&lt;[complet]ed&gt; but was:&lt;[block]ed&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The concurrent {@code stop()} completes while the listener is still running (the start operation has released the
 * state change lock before STARTED is delivered).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.600 and Felix 7.0.5 (same probe, JDK 25): the other thread's {@code stop()} completes during STARTED.
 * For LAZY_ACTIVATION all three frameworks (jbosgi, Equinox, Felix) still hold the lock, so it is not asserted here.
 * For STOPPED (lock must be held) jbosgi is correct while Equinox and Felix do not hold it.
 *
 * <h3>Suggested fix</h3>
 * Split the state change for ACTIVE from the event: set the state to ACTIVE under the lock, release the START lock in
 * {@code BundleManagerPlugin.startBundle}, then fire STARTED (and analogously deliver LAZY_ACTIVATION after the lock is
 * released). Asynchronous BundleListeners are unaffected.
 *
 * <h3>Regression vs WF41</h3>
 * no - identical code on WF41 (pre-existing).
 *
 * @since 14-Oct-2026
 */
public class StartedEventUnderStateChangeLockIssueTestCase extends OSGiFrameworkTest {

    private static final long WAIT_MILLIS = 5000;

    @Test
    public void testStartedDeliveredWithoutStateChangeLock() throws Exception {
        final Bundle bundle = getSystemContext().installBundle("started-lock-probe",
                new ByteArrayInputStream(jar("started-lock-probe")));
        final AtomicReference<Thread> stopper = new AtomicReference<Thread>();
        final AtomicReference<String> verdict = new AtomicReference<String>();
        SynchronousBundleListener listener = new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle() != bundle || event.getType() != BundleEvent.STARTED || stopper.get() != null)
                    return;
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            bundle.stop();
                        } catch (Exception ex) {
                            verdict.compareAndSet(null, "stop failed: " + ex);
                        }
                    }
                }, "concurrent-stop");
                stopper.set(thread);
                thread.start();
                try {
                    thread.join(WAIT_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                verdict.compareAndSet(null, thread.isAlive() ? "blocked" : "completed");
            }
        };
        getSystemContext().addBundleListener(listener);
        try {
            bundle.start();
            Thread thread = stopper.get();
            assertFalse("Precondition: STARTED was delivered to the SynchronousBundleListener", thread == null);
            thread.join(60000);
            assertEquals("STARTED must be delivered without the bundle's state change lock: a Bundle.stop() on another thread"
                    + " must be able to complete while the SynchronousBundleListener handles STARTED, but it was still"
                    + " blocked after " + WAIT_MILLIS + " ms", "completed", verdict.get());
        } finally {
            getSystemContext().removeBundleListener(listener);
            Thread thread = stopper.get();
            if (thread != null)
                thread.join(60000);
            bundle.uninstall();
        }
    }

    private static byte[] jar(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Bundle-ManifestVersion", "2");
        attributes.putValue("Bundle-SymbolicName", symbolicName);
        attributes.putValue("Bundle-Version", "1.0.0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JarOutputStream(out, manifest).close();
        return out.toByteArray();
    }
}
