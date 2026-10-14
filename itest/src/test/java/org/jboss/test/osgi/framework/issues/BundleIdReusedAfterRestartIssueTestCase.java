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

import static org.junit.Assert.assertTrue;

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
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * After a restart the framework hands out bundle identifiers again that were already used by bundles uninstalled
 * before the restart: the next identifier is derived from the bundles that are still installed.
 *
 * <h3>Severity</h3>
 * critical (raised by the coordinator from minor -> major -> critical as consequences were found). Anything that
 * remembers bundle ids across restarts (management agents, audit logs, persisted caches keyed by bundle id, Remote
 * Service Admin endpoint ids, ...) can confuse the new bundle with the uninstalled one; a reused id hands another
 * bundle's persistent data area to the new bundle (testIdReuseDoesNotHandOverPersistentData, tests/08); and in the
 * WildFly server two LIVE bundles end up with the same id (see "Server consequence" below).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code BundleManagerPlugin.createRevisionIdentifier}, together with
 * jbosgi-resolver {@code org.jboss.osgi.resolver.spi.AbstractEnvironment.nextResourceIdentifier} (in-memory counter).
 *
 * <h3>Found by</h3>
 * connect workstream (persistence across restarts, compliance matrix framework.lifecycle#81), exploratory test plus a
 * differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.3 Installing Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.installingbundles):
 * "The Framework must assign a unique bundle identifier that is higher than any previous bundle identifier."<br>
 * Bundle.getBundleId javadoc (osgi.core 8.0.0): "A bundle's unique identifier has the following attributes: Is unique
 * and persistent. ... Its value is not reused for another bundle, even after a bundle is uninstalled."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:567-577}
 * ({@code createRevisionIdentifier} takes {@code env.nextResourceIdentifier(null, symbolicName)}), and
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:93-104}: the counter
 * lives in memory and, after a restart, only learns the ids of the bundles that are restored from storage; the highest
 * id ever assigned is not persisted. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The "next bundle id" is not part of the persistent framework state.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=BundleIdReusedAfterRestartIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A bundle installed after the restart must get an id higher than any previous id (2), but
 *   it got 2, the id of the bundle that was uninstalled before the restart
 * </pre>
 *
 * <h3>Expected</h3>
 * The id is 3 (higher than every id ever assigned in this storage area).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: uninstalled id 2, the first id after the restart is 3.
 *
 * <h3>Suggested fix</h3>
 * Persist the highest assigned bundle id in the storage area (e.g. a framework-level properties file written by
 * {@code StorageManagerImpl}) and seed {@code nextResourceIdentifier} with it on init.
 *
 * <h3>Data consequence</h3>
 * testIdReuseDoesNotHandOverPersistentData (added by the tests workstream): when a stored bundle is not restored, its id
 * and its bundle-N directory, including the files of its data area, go to the next installed bundle
 * ({@code StorageManagerImpl.createStorageState} takes over an existing directory of another location as previous
 * revision). In WildFly this happens on every restart for bundles deployed through the deployment scanner, see tests/08
 * ScannerBundleDataMixupIssueTestCase (critical).
 * <h3>Server consequence: duplicate live bundle ids (wildfly-lifecycle workstream, 2026-10-14)</h3>
 * Patched WildFly 41.0.1 + jbosgi R8, JDK 25, no uninstall involved: (1) fresh server, {@code deploy inst-async.jar}
 * with the CLI, the bundle gets id 25 (osgi-store/bundle-25); (2)
 * {@code /subsystem=osgi/capability=org.apache.felix.webconsole:add(startlevel=2)}; (3) {@code :shutdown} and start.
 * After the restart TWO live bundles have id 25: the new capability bundle (installed at boot before the deployments
 * are restored, while the in-memory counter only knows the capability ids 0..24) and the restored deployment (keeps
 * its stored id 25). {@code /subsystem=osgi:read-children-resources(child-type=bundle)} lists only the capability
 * bundle, the deployment is invisible in the management model ({@code bundle=inst-async.jar} gives WFLYCTL0216) and
 * {@code BundleContext.getBundle(25)} can return only one of the two. Stable over further restarts. Any module bundle
 * that is new at boot (capability added, non-OSGi jar deployed) can collide with a stored deployment bundle. Persisting
 * the highest assigned id (suggested fix above) fixes this too. Details: issues/connect/14-bundle-id-reused-after-restart/NOTE-from-lifecycle.md.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class BundleIdReusedAfterRestartIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        stopFramework();
    }

    private void stopFramework() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testIdNotReusedAfterRestart() throws Exception {
        framework = newFramework(true);
        framework.start();
        installBundle("first");
        Bundle last = installBundle("last");
        long lastId = last.getBundleId();
        last.uninstall();
        stopFramework();

        framework = newFramework(false);
        framework.start();
        long nextId = installBundle("next").getBundleId();
        assertTrue("A bundle installed after the restart must get an id higher than any previous id (" + lastId + "), but it got " + nextId
                + (nextId == lastId ? ", the id of the bundle that was uninstalled before the restart" : ""), nextId > lastId);
    }

    /**
     * Data consequence (added by the tests workstream, see tests/08 ScannerBundleDataMixupIssueTestCase): a stored
     * bundle that cannot be restored after the restart (here: its revision jar is gone) keeps its bundle-N directory;
     * its id is given out again and the new bundle inherits that directory, including the data files of the old one.
     */
    @Test
    public void testIdReuseDoesNotHandOverPersistentData() throws Exception {
        framework = newFramework(true);
        framework.start();
        installBundle("first");
        Bundle victim = installBundle("victim");
        victim.start();
        long victimId = victim.getBundleId();
        java.nio.file.Files.write(victim.getBundleContext().getDataFile("owner.txt").toPath(), "victim".getBytes("UTF-8"));
        stopFramework();

        // The stored content of the victim is lost, the framework cannot restore it
        File storageDir = new File("target/issues-osgi-store/bundle-" + victimId);
        for (File file : storageDir.listFiles()) {
            if (file.getName().endsWith(".jar"))
                file.delete();
        }

        framework = newFramework(false);
        framework.start();
        Bundle next = installBundle("next");
        next.start();
        File owner = next.getBundleContext().getDataFile("owner.txt");
        assertTrue("A bundle installed after the restart must start with an empty data area, but bundle " + next.getBundleId()
                + " finds the data of bundle " + victimId + " (owner.txt=" + (owner.exists() ? new String(java.nio.file.Files.readAllBytes(owner.toPath()), "UTF-8") : "-") + ")",
                owner.exists() == false);
    }

    private Bundle installBundle(final String name) throws BundleException {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle(name, archive.as(ZipExporter.class).exportAsInputStream());
    }

    private Framework newFramework(boolean clean) {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        if (clean)
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
