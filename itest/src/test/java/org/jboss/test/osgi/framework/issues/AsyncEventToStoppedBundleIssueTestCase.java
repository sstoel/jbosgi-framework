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
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;

/**
 * An asynchronous BundleEvent that was published while a listener's bundle was active is still delivered to that
 * listener after the bundle has been stopped (its BundleContext is invalid) - scenario three of 4.7.2.
 *
 * <h3>Severity</h3>
 * minor. A stopped bundle's listener code runs after its activator's stop method returned, typically using
 * services/state the bundle already released (and its BundleContext throws IllegalStateException). The window is
 * the asynchronous delivery delay, which grows with slow listeners of other bundles.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl.fireBundleEvent} (the
 * asynchronous Runnable).
 *
 * <h3>Found by</h3>
 * manifest workstream, round 4 (second opinion on the PASS-code matrix row framework.lifecycle#165, previously
 * verified by code reading "FrameworkEventsImpl skips listeners whose bundle context is destroyed"):
 * {@code explore/passcode PassCodeFrameworkTestCase.testAsynchronousDeliverySnapshot}. Related but different:
 * wildfly/22 (service/framework listeners of a stopping bundle are not removed); bundle listeners are removed on
 * stop, but the snapshot taken at publication is delivered without checking the bundle again.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.7.2 Delivering Events (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * "If the Framework delivers an event asynchronously, it must: Collect a snapshot of the listener list at the time
 * the event is published ... Ensure, at the time the snapshot is taken, that listeners on the list still belong to
 * active bundles at the time the event is delivered. ... Scenario three event sequence: Listener 3 is registered.
 * Event C is published. The bundle that registered Listener 3 is stopped. Asynchronous delivery of Event C is
 * attempted. Expected Behavior: Listener 3 must not receive Event C, because its Bundle Context object is invalid."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:402-421}: the Runnable calls
 * {@code listener.bundleChanged(event)} for every registration of the snapshot without checking that the
 * registering bundle is still active / its context still valid.
 *
 * <h3>Root cause</h3>
 * Only the snapshot is taken; the delivery-time check of 4.7.2 is missing.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=AsyncEventToStoppedBundleIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testListenerOfStoppedBundle: The listener of a bundle that was stopped before the asynchronous delivery must not
 *   receive the event expected:&lt;[]&gt; but was:&lt;[1:async-event-target (owner state 4)]&gt;
 * </pre>
 * (1 = INSTALLED, delivered on the "Framework Events Thread" about 1 s after stop() returned; owner state 4 = RESOLVED.)
 *
 * <h3>Expected</h3>
 * No event for the listener of the stopped bundle.
 *
 * <h3>Reference behaviour</h3>
 * Not checked against Equinox/Felix (the scenario is the spec's own example).
 *
 * <h3>Suggested fix</h3>
 * In the asynchronous Runnable skip a registration whose bundle is no longer STARTING/ACTIVE/STOPPING (or whose
 * BundleContext has been invalidated); the system bundle's listeners are always delivered. Verified: with that check
 * the test passes, and BundleEventTestCase and BundleLifecycleTestCase still pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (the asynchronous delivery is unchanged since WF41).
 */
public class AsyncEventToStoppedBundleIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testListenerOfStoppedBundle() throws Exception {
        // A slow asynchronous listener (registered first) delays the delivery of the INSTALLED event
        final CountDownLatch slowEntered = new CountDownLatch(1);
        BundleListener slow = new BundleListener() {
            public void bundleChanged(BundleEvent event) {
                if ("async-event-target".equals(event.getBundle().getSymbolicName()) && event.getType() == BundleEvent.INSTALLED) {
                    slowEntered.countDown();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        // ignore
                    }
                }
            }
        };
        final Bundle owner = install("async-event-owner");
        owner.start();
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        getSystemContext().addBundleListener(slow);
        owner.getBundleContext().addBundleListener(new BundleListener() {
            public void bundleChanged(BundleEvent event) {
                if ("async-event-target".equals(event.getBundle().getSymbolicName()))
                    received.add(event.getType() + ":" + event.getBundle().getSymbolicName() + " (owner state " + owner.getState() + ")");
            }
        });
        Bundle target = null;
        try {
            // the INSTALLED event is published while the owner is active ...
            target = install("async-event-target");
            // ... and the owner is stopped before the asynchronous delivery
            owner.stop();
            received.clear();
            assertTrue(slowEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(2000);
            assertEquals("The listener of a bundle that was stopped before the asynchronous delivery must not receive the event",
                    new ArrayList<String>(), new ArrayList<String>(received));
        } finally {
            getSystemContext().removeBundleListener(slow);
            if (target != null)
                target.uninstall();
            owner.uninstall();
        }
    }

    private Bundle install(final String name) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return installBundle(archive);
    }
}
