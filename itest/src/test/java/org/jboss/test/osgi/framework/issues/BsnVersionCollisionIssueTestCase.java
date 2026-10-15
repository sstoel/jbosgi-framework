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
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.hooks.bundle.CollisionHook;

/**
 * The bundle symbolic name / version collision check treats a bundle without Bundle-Version (0.0.0) as colliding with
 * every installed bundle of the same symbolic name: with the default org.osgi.framework.bsnversion=managed (and with
 * single) installing or updating to an unversioned bundle fails with DUPLICATE_BUNDLE_ERROR as soon as any other
 * version of that name is installed; CollisionHooks are called with those other versions as candidates, and the
 * candidate collection accepts add().
 *
 * <h3>Severity</h3>
 * major - with the default policy (managed, no collision hook) a bundle without a Bundle-Version header (very common
 * for home-grown and test bundles, and the version every R3 bundle gets) cannot be installed or updated next to any
 * versioned bundle of the same symbolic name ("Bundle symbolic name and version are not unique"), although nothing
 * collides. The reverse order (unversioned bundle first, then a versioned one) works, so the outcome depends on the
 * installation order (e.g. after a restart or redeployment). A CollisionHook (region/management agent) sees
 * bundles that do not collide and must remove them to let the install pass.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin#checkUniqunessPolicy}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 1): seeded stateful bundle-hook differential {@code fa.BundleHookModel}
 * (installs/updates of bsn a|b with versions 1.0, 2.0, 1.0.0, 0.0.0 and none, at reused locations, by different
 * contexts; Find/Event/Collision hooks registered and unregistered at random; bundle listeners; resolve/refresh;
 * org.osgi.framework.bsnversion=managed/multiple/single, seeds 1 and 2, 250 steps each) against Equinox 3.21 and
 * Felix 7.0.5. First divergence: managed seed 1 step 24 ({@code update} of a 2.0 bundle to b without version while
 * b 1.0 is installed: Equinox/Felix succeed, jbosgi throws BundleException type 9). Minimised by the probe
 * {@code fa.ProbeBundleHooks}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 54.5 Bundle Collision Hook
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.bundlehooks.html): "Two bundles collide when they
 * have the same bundle symbolic name and version" and 54.7.2.3 CollisionHook.filterCollisions: "The collision
 * candidate collection contains the existing bundles installed which have the same symbolic name and version as the
 * bundle being installed." / "... as the content the target bundle is being updated to."<br>
 * Same section: "collisionCandidates - The collection of collision candidates. The collection supports all the
 * optional Collection operations except add and addAll. Attempting to add to the collection will result in an
 * UnsupportedOperationException."<br>
 * 3.2.1.10 / 3.6.2 Bundle-Version: "the default value is 0.0.0" (an unversioned bundle has version 0.0.0, which
 * only equals 0.0.0).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:739-744}:
 * {@code if (!Version.emptyVersion.equals(version)) versionRange = new VersionRange("[" + version + "," + version + "]");}
 * then {@code getBundles(symbolicName, versionRange)} - for version 0.0.0 the range stays {@code null}, which
 * {@code getBundles} (:467-481) treats as "any version". {@code :738} the candidates are a plain HashSet handed to
 * the hooks (:763). Pre-existing (9105ebd4, 2012); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * "No version range" is used to express "exact version 0.0.0", but means "all versions" to getBundles. The candidate
 * collection is not wrapped in a shrinkable (remove-only) collection.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=BsnVersionCollisionIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testInstallUnversionedNextToVersioned: Installing collision.a without Bundle-Version next to collision.a 1.0 must
 *   succeed (no bundle with that symbolic name and version 0.0.0), but failed with
 *   org.osgi.framework.BundleException: JBOSGI011240: Bundle name and version already installed: [collision.a:0.0.0] (type 9)
 * testUpdateToUnversionedNextToVersioned: Updating a bundle to collision.b without Bundle-Version next to
 *   collision.b 1.0 must succeed, but failed with org.osgi.framework.BundleException: JBOSGI011240: Bundle name and
 *   version already installed: [collision.b:0.0.0] (type 9)
 * testCollisionHookOnlySeesSameVersion: The CollisionHook must not be called when no installed bundle has the same
 *   symbolic name and version expected:&lt;[]&gt; but was:&lt;[INSTALLING [collision.c 1.0.0]]&gt;
 * testCollisionCandidatesAreShrinkable: CollisionHook candidates: add must throw UnsupportedOperationException
 *   expected:&lt;[UnsupportedOperationException]&gt; but was:&lt;[add accepted]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Only bundles with exactly the same symbolic name and version collide: the unversioned bundle installs/updates,
 * the collision hook is not called for it (no candidates), and the candidate collection throws
 * UnsupportedOperationException on add.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 (bsnversion single and managed): the unversioned install and update succeed, the
 * collision hook is not called; the candidate collection throws UnsupportedOperationException on add.
 *
 * <h3>Suggested fix</h3>
 * Always use the exact range {@code [version,version]} (also for 0.0.0), or compare
 * {@code version.equals(aux.getVersion())} directly; pass the hooks a remove-only collection (as the bundle FindHook
 * and EventHook already get: {@code RemoveOnlyCollection}).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class BsnVersionCollisionIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testInstallUnversionedNextToVersioned() throws Exception {
        assertEquals("managed", getSystemContext().getProperty(Constants.FRAMEWORK_BSNVERSION));
        Bundle versioned = installBundle(getArchive("collision-a1", "collision.a", "1.0"));
        Bundle unversioned = null;
        BundleException failure = null;
        try {
            unversioned = installBundle(getArchive("collision-a0", "collision.a", null));
            assertEquals(Version.emptyVersion, unversioned.getVersion());
        } catch (BundleException ex) {
            failure = ex;
        } finally {
            if (unversioned != null)
                unversioned.uninstall();
            versioned.uninstall();
        }
        assertNull("Installing collision.a without Bundle-Version next to collision.a 1.0 must succeed (no bundle with that symbolic name and version 0.0.0), but failed with "
                + failure + (failure != null ? " (type " + failure.getType() + ")" : ""), failure);
    }

    @Test
    public void testUpdateToUnversionedNextToVersioned() throws Exception {
        Bundle versioned = installBundle(getArchive("collision-b1", "collision.b", "1.0"));
        Bundle other = installBundle(getArchive("collision-other", "collision.other", "1.0"));
        BundleException failure = null;
        try {
            other.update(toInputStream(getArchive("collision-other", "collision.b", null)));
        } catch (BundleException ex) {
            failure = ex;
        } finally {
            other.uninstall();
            versioned.uninstall();
        }
        assertNull("Updating a bundle to collision.b without Bundle-Version next to collision.b 1.0 must succeed, but failed with " + failure
                + (failure != null ? " (type " + failure.getType() + ")" : ""), failure);
    }

    @Test
    public void testCollisionHookOnlySeesSameVersion() throws Exception {
        final List<String> calls = new ArrayList<String>();
        ServiceRegistration<CollisionHook> reg = getSystemContext().registerService(CollisionHook.class, new CollisionHook() {
            public void filterCollisions(int operationType, Bundle target, Collection<Bundle> candidates) {
                List<String> names = new ArrayList<String>();
                for (Bundle bundle : candidates)
                    names.add(bundle.getSymbolicName() + " " + bundle.getVersion());
                calls.add((operationType == CollisionHook.INSTALLING ? "INSTALLING " : "UPDATING ") + names);
                // let the operation pass, the call itself is the defect
                candidates.clear();
            }
        }, null);
        Bundle versioned = installBundle(getArchive("collision-c1", "collision.c", "1.0"));
        Bundle unversioned = null;
        try {
            unversioned = installBundle(getArchive("collision-c0", "collision.c", null));
        } finally {
            reg.unregister();
            if (unversioned != null)
                unversioned.uninstall();
            versioned.uninstall();
        }
        assertEquals("The CollisionHook must not be called when no installed bundle has the same symbolic name and version", "[]", calls.toString());
    }

    @Test
    public void testCollisionCandidatesAreShrinkable() throws Exception {
        final List<String> result = new ArrayList<String>();
        ServiceRegistration<CollisionHook> reg = getSystemContext().registerService(CollisionHook.class, new CollisionHook() {
            public void filterCollisions(int operationType, Bundle target, Collection<Bundle> candidates) {
                try {
                    candidates.add(target);
                    result.add("add accepted");
                } catch (UnsupportedOperationException ex) {
                    result.add("UnsupportedOperationException");
                }
                candidates.clear();
            }
        }, null);
        Bundle first = installBundle(getArchive("collision-d1", "collision.d", "1.0"));
        Bundle second = null;
        try {
            second = installBundle(getArchive("collision-d1-copy", "collision.d", "1.0"));
        } finally {
            reg.unregister();
            if (second != null)
                second.uninstall();
            first.uninstall();
        }
        assertEquals("CollisionHook candidates: add must throw UnsupportedOperationException", "[UnsupportedOperationException]", result.toString());
    }

    private JavaArchive getArchive(String name, final String symbolicName, final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                if (version != null)
                    builder.addBundleVersion(version);
                return builder.openStream();
            }
        });
        return archive;
    }
}
