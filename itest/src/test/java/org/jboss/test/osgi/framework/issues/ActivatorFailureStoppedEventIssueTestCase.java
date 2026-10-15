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
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.SynchronousBundleListener;

/**
 * When the BundleActivator.start method of a bundle throws, the framework fires STOPPING followed by RESOLVED
 * instead of STOPPING followed by STOPPED.
 *
 * <h3>Severity</h3>
 * major - a spec violation that every bundle with a failing activator hits; listeners that pair STARTING/STOPPING
 * with STOPPED (extenders, bundle trackers keyed on events, audit) never see the bundle stop.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState} (activator start failure path).
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful API fuzzer ({@code Stateful}, seed 1): the per-bundle event state machine oracle reported
 * "event RESOLVED after STOPPING" for a bundle whose activator was toggled to throw; minimized and compared with
 * Equinox 3.21 and the WF41 framework.
 *
 * <h3>Spec reference</h3>
 * Bundle.start(int) javadoc (OSGi Core R8, 10.1.5.33): "If the BundleActivator is invalid or throws an exception then:
 * This bundle's state is set to STOPPING. A bundle event of type BundleEvent.STOPPING is fired. Any services
 * registered by this bundle must be unregistered. ... This bundle's state is set to RESOLVED. A bundle event of type
 * BundleEvent.STOPPED is fired. A BundleException is then thrown."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:632-634}: the
 * comment says "#8.7 A bundle event of type BundleEvent.STOPPED is fired" but the code calls
 * {@code changeState(Bundle.RESOLVED)}, and {@code AbstractBundleState.changeState(int)} (:221-245) maps the RESOLVED
 * state to {@code BundleEvent.RESOLVED}. (Same code on WF41.)
 *
 * <h3>Root cause</h3>
 * The failure path reuses the generic state change that maps the RESOLVED state to a RESOLVED event.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ActivatorFailureStoppedEventIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testStoppedEventAfterActivatorFailure: Bundle events of a failing activator start
 *   expected:&lt;...STARTING, STOPPING, [STOPP]ED]&gt; but was:&lt;...STARTING, STOPPING, [RESOLV]ED]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Events STARTING, STOPPING, STOPPED (and the BundleException of type ACTIVATOR_ERROR).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 fires [STARTING, STOPPING, STOPPED].
 *
 * <h3>Suggested fix</h3>
 * Fire BundleEvent.STOPPED explicitly after setting the state to RESOLVED on the activator failure path.
 *
 * <h3>Regression vs WF41</h3>
 * no - same events on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class ActivatorFailureStoppedEventIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testStoppedEventAfterActivatorFailure() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "activator-failure-events");
        archive.addClasses(FailingActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(FailingActivator.class);
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final long id = bundle.getBundleId();
        SynchronousBundleListener listener = new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle().getBundleId() == id && event.getType() != BundleEvent.RESOLVED)
                    events.add(name(event.getType()));
                else if (event.getBundle().getBundleId() == id && !events.isEmpty())
                    events.add(name(event.getType()));
            }
        };
        getSystemContext().addBundleListener(listener);
        try {
            try {
                bundle.start();
                fail("BundleException expected");
            } catch (BundleException ex) {
                assertEquals("Exception type", BundleException.ACTIVATOR_ERROR, ex.getType());
            }
            assertEquals("Bundle events of a failing activator start", "[STARTING, STOPPING, STOPPED]", events.toString());
        } finally {
            getSystemContext().removeBundleListener(listener);
            bundle.uninstall();
        }
    }

    private static String name(int type) {
        switch (type) {
        case BundleEvent.STARTING:
            return "STARTING";
        case BundleEvent.STARTED:
            return "STARTED";
        case BundleEvent.STOPPING:
            return "STOPPING";
        case BundleEvent.STOPPED:
            return "STOPPED";
        case BundleEvent.RESOLVED:
            return "RESOLVED";
        default:
            return "EVENT" + type;
        }
    }

    public static class FailingActivator implements BundleActivator {
        public void start(BundleContext context) throws Exception {
            throw new IllegalStateException("activator start failure");
        }

        public void stop(BundleContext context) throws Exception {
        }
    }
}
