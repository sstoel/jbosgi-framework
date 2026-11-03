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

import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.DeploymentProvider;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.jboss.test.osgi.framework.issues.hooks.UpdateCollisionProbe;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.launch.Framework;

/**
 * An install of the location of an installed bundle waits for the lock of its symbolic name, which an update of that
 * bundle holds. In the server the update replaces the deployment of the bundle in a management operation; when the
 * deployment of the new revision fails, the operation is rolled back and the server deploys the bundle's deployment
 * again, on a thread of the service container: that install waits for the name lock of the update, which waits for the
 * operation. The update hangs until the blocking timeout of the server (300 s), which then requires a restart.
 *
 * <h2>Severity</h2>
 * major. A rolled-back Bundle.update in the server hangs management and leaves the server in restart-required.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.installBundleRevision}, {@code lockName}.
 *
 * <h2>Found by</h2>
 * R8 release check, T6 of verify-srv (the code review had judged the rollback right).
 *
 * <h2>Spec reference</h2>
 * BundleContext.installBundle: a bundle that is installed at the location is returned; no uniqueness check is done.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:702-711 takes the name lock before it
 * checks the location; UserBundleState.java:428-437 holds it while the bundle lifecycle creates the revision
 * (84ac5c2e). The server: BundleInstallProcessor.deploy:121 calls installBundleRevision.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=InstallOfLocationDuringUpdateIssueTestCase
 * </pre>
 * A collision hook runs while the update holds the name lock and installs the location of the updated bundle through
 * the BundleManager, the way the deployment of the server does.
 *
 * <h2>Expected</h2>
 * The install returns the revision of the installed bundle at once, the update completes.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 3093973d (the name lock of concurrency/04).
 */
public class InstallOfLocationDuringUpdateIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        ReleaseCheckSupport.stop(framework);
    }

    @Test
    public void testInstallOfOwnLocationDuringUpdate() throws Exception {
        File storage = ReleaseCheckSupport.storage("install-location-during-update");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true, Constants.FRAMEWORK_BSNVERSION, Constants.FRAMEWORK_BSNVERSION_MANAGED);
        framework.start();
        final BundleContext context = framework.getBundleContext();
        final Bundle bundle = context.installBundle("install-location-b", bundle("install.location.b", Constants.BUNDLE_VERSION, "1.0"));
        bundle.start();
        // Another bundle has the name and version of the update: the collision hook runs
        context.installBundle("install-location-other", bundle("install.location.b", Constants.BUNDLE_VERSION, "2.0"));

        final BundleManager bundleManager = framework.adapt(BundleManager.class);
        UpdateCollisionProbe probe = new UpdateCollisionProbe(bundle, 5000, () -> {
            // The deployment of the bundle's location with its content, as the server deploys it again
            DeploymentProvider provider = (DeploymentProvider) bundleManager.getServiceContainer().getRequiredService(IntegrationServices.DEPLOYMENT_PROVIDER_PLUGIN).getValue();
            VirtualFile root = AbstractVFS.toVirtualFile(bundle("install.location.b", Constants.BUNDLE_VERSION, "1.0"));
            Deployment deployment = provider.createDeployment(bundle.getLocation(), root);
            XBundleRevision brev = bundleManager.installBundleRevision(context, deployment, null);
            return brev.getBundle() == bundle ? "the installed bundle" : "another bundle: " + brev.getBundle();
        });
        context.registerService(CollisionHook.class, probe, null);

        bundle.update(bundle("install.location.b", Constants.BUNDLE_VERSION, "2.0"));
        probe.join();
        assertEquals("The install of the location returns the installed bundle while the update holds the name", "the installed bundle", probe.getResult());
        assertEquals("The update completes", "2.0.0", bundle.getVersion().toString());
        assertEquals(Bundle.ACTIVE, bundle.getState());
    }
}
