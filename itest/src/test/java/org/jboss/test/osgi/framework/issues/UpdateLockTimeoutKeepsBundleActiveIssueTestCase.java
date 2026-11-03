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
import static org.junit.Assert.assertNotNull;

import java.io.File;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.jboss.test.osgi.framework.issues.hooks.p.HookP;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;

/**
 * Bundle.update stops an ACTIVE bundle before it takes the locks of the update. When the locks cannot be obtained in
 * time the update fails, and the bundle stays RESOLVED: nothing starts it again.
 *
 * <h2>Severity</h2>
 * minor. A bundle stops running after an update that did not happen.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.updateBundle}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-06 (consequence of the fix of concurrency/05).
 *
 * <h2>Spec reference</h2>
 * Bundle.update: if the update fails, the original state of the bundle is restored.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:973 stops the bundle, :975 takes the
 * locks; a LockException there leaves the bundle stopped (84ac5c2e).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=UpdateLockTimeoutKeepsBundleActiveIssueTestCase
 * </pre>
 * The test waits for the lock timeout of the framework (30 s). A resolve holds the framework wiring lock in a resolver
 * hook during the update. Until the release check the update took the wiring lock with the lock of the bundle; it takes
 * the wiring lock later now, when it creates and installs the new revision. Either way it cannot complete in time.
 *
 * <h2>Expected</h2>
 * The update fails with a BundleException, the bundle is ACTIVE with its original revision.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by the fix of concurrency/05 (3093973d): the stop was under the locks before.
 */
public class UpdateLockTimeoutKeepsBundleActiveIssueTestCase extends OSGiTest {

    private Framework framework;
    private ReleaseCheckSupport.WiringLockHolder holder;

    @After
    public void tearDown() throws Exception {
        if (holder != null)
            holder.release();
        ReleaseCheckSupport.stop(framework);
    }

    @Test
    public void testUpdateThatTimesOut() throws Exception {
        File storage = ReleaseCheckSupport.storage("update-lock-timeout");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true);
        framework.start();
        BundleContext context = framework.getBundleContext();
        Bundle bundle = context.installBundle("update-lock-timeout-x", bundle("update.lock.timeout.x", new Class<?>[] { HookP.class }, Constants.BUNDLE_VERSION, "1.0"));
        bundle.start();
        holder = new ReleaseCheckSupport.WiringLockHolder(context);
        holder.begin();

        BundleException failure = null;
        try {
            bundle.update(bundle("update.lock.timeout.x", new Class<?>[] { HookP.class }, Constants.BUNDLE_VERSION, "2.0"));
        } catch (BundleException ex) {
            failure = ex;
        }
        int stateAfterUpdate = bundle.getState();
        holder.release();
        holder = null;

        assertNotNull("Precondition: the update cannot obtain its locks in time", failure);
        assertEquals("The bundle is ACTIVE again after the failed update (RESOLVED=4, ACTIVE=32)", Bundle.ACTIVE, stateAfterUpdate);
        assertEquals("The bundle keeps its revision", "1.0.0", bundle.getVersion().toString());
    }
}
