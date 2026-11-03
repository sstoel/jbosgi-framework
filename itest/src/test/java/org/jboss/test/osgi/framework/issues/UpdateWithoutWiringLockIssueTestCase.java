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

import java.io.File;
import java.util.Collections;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.jboss.test.osgi.framework.issues.hooks.UpdateCollisionProbe;
import org.jboss.test.osgi.framework.issues.hooks.p.HookP;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Bundle.update holds the framework wiring lock while the bundle lifecycle creates the new revision. In the server that
 * is a management operation that replaces the deployment of the bundle: it waits for the operations that are in
 * progress, and their deployments wait for the wiring lock to resolve their bundles. Only the lock timeout of 60 s ends
 * the cycle, and the concurrent deployment fails; while updates run, management writes come to a halt.
 *
 * <h2>Severity</h2>
 * major. Bundle.update next to a deployment wedges management, 8 operations per minute in the soak.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.updateBundle}, {@code UserBundleState.updateInternal}.
 *
 * <h2>Found by</h2>
 * R8 release check, S3 of the soak (F3).
 *
 * <h2>Spec reference</h2>
 * none; OSGi Core R8 4.4.9 Bundle.update.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:970-985 locks the wiring lock and the
 * bundle for the whole update (84ac5c2e); the server: BundleLifecycleIntegration.replaceDeployment.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=UpdateWithoutWiringLockIssueTestCase
 * </pre>
 * A collision hook runs while the update creates its revision and resolves an unrelated bundle on another thread, the
 * way the deployment of a concurrent management operation does.
 *
 * <h2>Expected</h2>
 * The unrelated bundle is resolved while the update creates its revision; the update completes.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class UpdateWithoutWiringLockIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        ReleaseCheckSupport.stop(framework);
    }

    @Test
    public void testResolveOfOtherBundleDuringUpdate() throws Exception {
        File storage = ReleaseCheckSupport.storage("update-without-wiring-lock");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true, Constants.FRAMEWORK_BSNVERSION, Constants.FRAMEWORK_BSNVERSION_MANAGED);
        framework.start();
        BundleContext context = framework.getBundleContext();
        String pkg = HookP.class.getPackage().getName();
        final Bundle bundle = context.installBundle("without-wiring-lock-b", bundle("without.wiring.lock.b", new Class<?>[] { HookP.class }, Constants.BUNDLE_VERSION, "1.0", Constants.EXPORT_PACKAGE, pkg));
        bundle.start();
        // Another bundle has the name and version of the update: the collision hook runs
        context.installBundle("without-wiring-lock-other", bundle("without.wiring.lock.b", Constants.BUNDLE_VERSION, "2.0"));
        final Bundle unrelated = context.installBundle("without-wiring-lock-u", bundle("without.wiring.lock.u", Constants.BUNDLE_VERSION, "1.0"));

        final FrameworkWiring wiring = framework.adapt(FrameworkWiring.class);
        UpdateCollisionProbe probe = new UpdateCollisionProbe(bundle, 5000, () -> {
            boolean resolved = wiring.resolveBundles(Collections.singleton(unrelated));
            return resolved && unrelated.getState() == Bundle.RESOLVED ? "resolved" : "not resolved, state " + unrelated.getState();
        });
        context.registerService(CollisionHook.class, probe, null);

        bundle.update(bundle("without.wiring.lock.b", new Class<?>[] { HookP.class }, Constants.BUNDLE_VERSION, "2.0", Constants.EXPORT_PACKAGE, pkg));
        probe.join();
        assertEquals("An unrelated bundle is resolved while the update creates its revision", "resolved", probe.getResult());
        assertEquals("The update completes", "2.0.0", bundle.getVersion().toString());
        assertEquals(Bundle.ACTIVE, bundle.getState());
    }
}
