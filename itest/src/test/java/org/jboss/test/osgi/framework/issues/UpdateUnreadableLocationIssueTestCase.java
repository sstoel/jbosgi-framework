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
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleRevisions;

/**
 * Bundle.update() of a bundle whose location is a URL that cannot be read any more updates the bundle with its old
 * content: the bundle gets a new revision and an UPDATED event, the caller cannot tell that nothing was read.
 *
 * <h2>Severity</h2>
 * minor. The bundle is stopped and started again for nothing, the caller believes it was updated.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleState.getContentAtLocation}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of services/18 (fw-minor-D04).
 *
 * <h2>Spec reference</h2>
 * Bundle.update: a BundleException of type READ_ERROR when the update cannot be read; the postcondition of an exception
 * is that no update occurred.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:387-389, :443-460 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * An IOException while the location was read was taken like a location that is not a URL: the current content was used.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=UpdateUnreadableLocationIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * update() throws a BundleException of type READ_ERROR, the bundle keeps its revision and no UPDATED event is fired.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class UpdateUnreadableLocationIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUpdateFromDeletedLocation() throws Exception {
        File file = new File("target/update-unreadable-location.jar");
        write(file, "1.0");
        Bundle bundle = getSystemContext().installBundle(file.toURI().toURL().toString());
        final List<Integer> events = Collections.synchronizedList(new ArrayList<Integer>());
        BundleListener listener = new BundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getType() == BundleEvent.UPDATED)
                    events.add(event.getType());
            }
        };
        getSystemContext().addBundleListener(listener);
        try {
            assertTrue("The content is deleted", file.delete());
            try {
                bundle.update();
                fail("update() must fail when the location cannot be read");
            } catch (BundleException ex) {
                assertEquals("The update fails with READ_ERROR: " + ex, BundleException.READ_ERROR, ex.getType());
            }
            assertEquals("The bundle keeps its revision", 1, bundle.adapt(BundleRevisions.class).getRevisions().size());
            assertEquals(Version.parseVersion("1.0"), bundle.getVersion());
            assertEquals("No UPDATED event", 0, events.size());
        } finally {
            getSystemContext().removeBundleListener(listener);
            bundle.uninstall();
        }
    }

    private void write(File file, final String version) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "update-unreadable-location");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("update-unreadable-location");
                builder.addBundleVersion(version);
                return builder.openStream();
            }
        });
        try (FileOutputStream out = new FileOutputStream(file)) {
            archive.as(ZipExporter.class).exportTo(out);
        }
    }
}
