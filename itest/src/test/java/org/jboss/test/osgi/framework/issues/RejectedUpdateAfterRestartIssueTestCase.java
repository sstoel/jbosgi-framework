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

import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.bundle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;

/**
 * An update that the framework rejects, for a manifest that cannot be read or a symbolic name and version that another
 * bundle has, is written to the storage before it is rejected and nothing undoes that. The bundle keeps running its
 * revision, but the next launch of the framework restores the rejected content: a broken manifest loses the bundle, a
 * colliding update replaces it by a second copy of the other bundle.
 *
 * <h2>Severity</h2>
 * major. The bundle is lost or changed by a restart after an update that failed.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleState.createUpdateRevision}, {@code StorageManagerImpl.createStorageState},
 * {@code UserBundleRevisionFactory.handleCreateException}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-03 (also reported in the R8 fix review: no test of a failed update).
 *
 * <h2>Spec reference</h2>
 * Bundle.update: if the framework is unable to install the updated version of the bundle, the original version must be
 * restored and a BundleException thrown; 4.4.3 an installed bundle is persistent.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:409 persists the update revision before its
 * metadata is read (:412) and before the uniqueness check (:430); the catch in updateInternal (:343) rolls back nothing
 * in the storage (84ac5c2e).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=RejectedUpdateAfterRestartIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * After the rejected update and a launch of the framework the bundle is installed with its id, symbolic name, version
 * and content, it is ACTIVE again, and its storage has no content of the rejected revision.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class RejectedUpdateAfterRestartIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        ReleaseCheckSupport.stop(framework);
    }

    @Test
    public void testUpdateWithBrokenManifest() throws Exception {
        File storage = startFramework("rejected-update-manifest");
        Bundle bundle = framework.getBundleContext().installBundle("rejected-update-b", bundle("rejected.update.b", Constants.BUNDLE_VERSION, "1.0", "X-Content", "v1"));
        long id = bundle.getBundleId();
        bundle.start();
        try {
            bundle.update(bundle("rejected.update.b", Constants.BUNDLE_VERSION, "not.a.version.x y", "X-Content", "v2"));
            fail("The update with a broken manifest is rejected");
        } catch (BundleException ex) {
            // expected
        }
        assertEquals("Precondition: the bundle keeps its revision", "1.0.0", bundle.getVersion().toString());
        assertEquals("Precondition: the bundle is ACTIVE again", Bundle.ACTIVE, bundle.getState());

        assertRestored(storage, id, "rejected.update.b");
    }

    @Test
    public void testUpdateThatCollides() throws Exception {
        File storage = startFramework("rejected-update-collision");
        framework.getBundleContext().installBundle("rejected-update-other", bundle("rejected.update.other", Constants.BUNDLE_VERSION, "2.0", "X-Content", "other"));
        Bundle bundle = framework.getBundleContext().installBundle("rejected-update-b", bundle("rejected.update.b", Constants.BUNDLE_VERSION, "1.0", "X-Content", "v1"));
        long id = bundle.getBundleId();
        bundle.start();
        try {
            bundle.update(bundle("rejected.update.other", Constants.BUNDLE_VERSION, "2.0", "X-Content", "v2"));
            fail("The update to the symbolic name and version of another bundle is rejected");
        } catch (BundleException ex) {
            assertEquals("Precondition: a collision", BundleException.DUPLICATE_BUNDLE_ERROR, ex.getType());
        }
        assertEquals("Precondition: the bundle keeps its revision", "rejected.update.b", bundle.getSymbolicName());

        assertRestored(storage, id, "rejected.update.b");
        int others = 0;
        for (Bundle aux : framework.getBundleContext().getBundles())
            others += "rejected.update.other".equals(aux.getSymbolicName()) ? 1 : 0;
        assertEquals("The other bundle is installed once", 1, others);
    }

    private File startFramework(String name) throws Exception {
        File storage = ReleaseCheckSupport.storage(name);
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, false);
        framework.start();
        return storage;
    }

    // The bundle as it was before the rejected update, after a launch of the framework
    private void assertRestored(File storage, long id, String symbolicName) throws Exception {
        File bundleDir = ReleaseCheckSupport.bundleDir(storage, id);
        String[] files = bundleDir.list();
        ReleaseCheckSupport.stop(framework);

        framework = ReleaseCheckSupport.newFramework(storage, false);
        framework.start();
        Bundle restored = framework.getBundleContext().getBundle(id);
        String info = "storage of the bundle after the rejected update " + Arrays.toString(files) + ", bundles after the restart " + Arrays.toString(framework.getBundleContext().getBundles());
        assertNotNull("The bundle is installed after the restart: " + info, restored);
        assertEquals("Its symbolic name: " + info, symbolicName, restored.getSymbolicName());
        assertEquals("Its version: " + info, "1.0.0", restored.getVersion().toString());
        assertEquals("Its content: " + info, "v1", restored.getHeaders().get("X-Content"));
        assertEquals("It is started again: " + info, Bundle.ACTIVE, restored.getState());
        assertFalse("No content of the rejected revision is stored: " + info, Arrays.asList(files).contains("bundle-" + id + "-rev-1.jar"));
    }
}
