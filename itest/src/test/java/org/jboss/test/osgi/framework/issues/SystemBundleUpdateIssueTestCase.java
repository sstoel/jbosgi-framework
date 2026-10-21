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
import static org.junit.Assert.assertNotNull;

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
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Bundle.update() of the system bundle does nothing: the framework is not stopped and started again.
 *
 * <h2>Severity</h2>
 * minor. A management agent that restarts the framework through the system bundle, as the specification describes,
 * gets no restart.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code SystemBundleState.updateInternal}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of connect/08 (fw-major).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.6.4: an update of the system bundle returns immediately, the framework is stopped and started again
 * on another thread, like Framework.update.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/SystemBundleState.java:152 (a TODO).
 *
 * <h2>Root cause</h2>
 * The method was not implemented.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=SystemBundleUpdateIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * waitForStop reports STOPPED_UPDATE, then the framework is ACTIVE again.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class SystemBundleUpdateIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testUpdateOfSystemBundle() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/system-bundle-update").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        Bundle installed = framework.getBundleContext().installBundle("system-bundle-update", bundle("system.bundle.update.b"));

        framework.getBundleContext().getBundle(0).update();
        FrameworkEvent event = framework.waitForStop(20000);
        assertEquals("The framework stopped for the update", FrameworkEvent.STOPPED_UPDATE, event.getType());
        for (int i = 0; i < 200 && framework.getState() != Bundle.ACTIVE; i++)
            Thread.sleep(50);
        assertEquals("The framework runs again", Bundle.ACTIVE, framework.getState());
        assertNotNull("The bundles are still installed", framework.getBundleContext().getBundle(installed.getLocation()));
    }

    private static InputStream bundle(final String symbolicName) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }
}
