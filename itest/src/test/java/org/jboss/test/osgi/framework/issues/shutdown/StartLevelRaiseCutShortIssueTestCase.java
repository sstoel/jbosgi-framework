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
package org.jboss.test.osgi.framework.issues.shutdown;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * A stop of the framework during a start level increase does not cut the increase short: the bundles of the level
 * that are not started yet are started, and stopped again right after.
 *
 * <h2>Severity</h2>
 * minor. The stop waits for the rest of the increase; activators run in a framework that stops.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code StartLevelManagerImpl.increaseFrameworkStartLevel}; in the server the stop of the
 * framework services ({@code BundleManager.frameworkServicesStopping}).
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of shutdown/17 (umb-shutdown-06).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 9.3.1: a stop of the framework moves it to start level 0; no bundle is activated on the way.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/StartLevelManagerImpl.java:148 (fa32e5ab): the check is made per
 * level, not per bundle.
 *
 * <h2>Root cause</h2>
 * The increase checked whether the framework stops before a level, not before each bundle.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=StartLevelRaiseCutShortIssueTestCase
 * </pre>
 * The activator of A stops the framework while the start level 2 is entered; four other bundles have the same level and
 * higher ids. Whether the increase starts one of them depends on a race with the stop thread, which the first round in
 * a JVM tends to lose: the test repeats the scenario in one JVM (R8 release check, baseline-check 4.2; the first version
 * of the test ran it once and passed without the fix when it ran alone).
 *
 * <h2>Expected</h2>
 * In no round an activator of the other bundles is started.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class StartLevelRaiseCutShortIssueTestCase {

    static final String CALLS = "issues.shutdown.raise.calls";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
        System.getProperties().remove(CALLS);
    }

    @Test
    public void testStopDuringStartLevelIncrease() throws Exception {
        int roundsWithStart = 0;
        String first = null;
        for (int round = 0; round < 20; round++) {
            Map<String, String> calls = new ConcurrentHashMap<String, String>();
            System.getProperties().put(CALLS, calls);

            Map<String, String> props = new HashMap<String, String>();
            props.put(Constants.FRAMEWORK_STORAGE, new File("target/shutdown-raise-cut-short").getAbsolutePath());
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
            props.put(Constants.FRAMEWORK_BEGINNING_STARTLEVEL, "1");
            framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
            framework.start();
            BundleContext context = framework.getBundleContext();

            List<Bundle> bundles = new ArrayList<Bundle>();
            bundles.add(context.installBundle("raise-cut-a", bundle("raise.cut.a")));
            for (int i = 1; i <= 4; i++)
                bundles.add(context.installBundle("raise-cut-b" + i, bundle("raise.cut.b" + i)));
            for (Bundle bundle : bundles) {
                bundle.adapt(BundleStartLevel.class).setStartLevel(2);
                bundle.start();
            }

            framework.adapt(FrameworkStartLevel.class).setStartLevel(2);
            FrameworkEvent stopped = framework.waitForStop(30000);
            assertEquals("The framework stopped", FrameworkEvent.STOPPED, stopped.getType());
            assertEquals("Precondition: the activator of A stopped the framework", "started", calls.get("raise.cut.a"));
            calls.remove("raise.cut.a");
            if (calls.isEmpty() == false) {
                roundsWithStart++;
                if (first == null)
                    first = "round " + round + " started " + calls.keySet();
            }
        }
        assertEquals("Rounds in which the increase that the stop cut short started another bundle, the first: " + first, 0, roundsWithStart);
    }

    private static InputStream bundle(final String symbolicName) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        archive.addClasses(StoppingActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleActivator(StoppingActivator.class.getName());
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    /** Records its start; the activator of raise.cut.a stops the framework */
    public static class StoppingActivator implements BundleActivator {
        @SuppressWarnings("unchecked")
        public void start(BundleContext context) throws Exception {
            String name = context.getBundle().getSymbolicName();
            ((Map<String, String>) System.getProperties().get(CALLS)).put(name, "started");
            if (name.equals("raise.cut.a"))
                context.getBundle(0).stop();
        }

        public void stop(BundleContext context) {
        }
    }
}
