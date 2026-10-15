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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.jboss.test.osgi.framework.simple.bundleB.BeanB;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * WovenClass.getBundleWiring() returns the wiring of the bundle's current revision, not the wiring whose class loader
 * defines the class: for a class loaded by the class loader of an updated (removal pending) revision it returns null
 * (or, once the new revision is resolved, the new revision's wiring).
 *
 * <h3>Severity</h3>
 * major - after Bundle.update without a refresh (the normal state until the refresh runs, and permanent when
 * nobody refreshes) the old revision keeps loading classes for its importers. Every weaving hook gets
 * {@code null} from getBundleWiring() for those classes; the usual first line of a weaver,
 * {@code wovenClass.getBundleWiring().getBundle()} (56.3 example), throws NullPointerException, so the framework
 * deny-lists the weaving hook for good and fails the class load with ClassFormatError. A weaver that copes with null
 * cannot tell which bundle the class belongs to; once the new revision resolves, weavers see the new revision's
 * wiring and class loader for classes of the old one (wrong bundle revision, wrong capabilities).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.WeavingContext.ContextClass#getBundleWiring}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 3): weaving-hook differential against Equinox 3.21 and Felix 7.0.5;
 * the stateful fuzzer {@code fa.WeaveModel} (seeds 1-3, 11-16) updates and refreshes the woven bundles between class
 * loads, the follow-up probe {@code fa.ProbeWeave} (case H) loads a class through the old revision's class loader
 * after an update without refresh: jbosgi {@code wiring=null}, Equinox and Felix
 * {@code wiring=old cl=defining current=false inUse=true}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 56.6.4.6 WovenClass.getBundleWiring()
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.weavinghooks.html): "Returns the bundle wiring whose
 * class loader will define the woven class."<br>
 * 56.3: "getBundleWiring() - The bundle wiring, which provides access to the bundle, the bundle class loaders and
 * the capabilities."<br>
 * 4.4.10 / FrameworkWiring: after update the old revision stays in use (removal pending) until refreshed, and its
 * class loader keeps serving its importers.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/WeavingContext.java:276-278}
 * ({@code return hostState.adapt(BundleWiring.class);} - the bundle's current wiring) while
 * {@code HostBundleClassLoader.loadClassLocal} (:75, {@code WeavingContext.create(getBundleState())}) creates the
 * context with the bundle state only, not the revision the class loader belongs to. Pre-existing (b294ba91, 2013);
 * unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The weaving context knows only the bundle (UserBundleState), not the revision whose class loader is defining the
 * class; getBundleWiring() asks the bundle for its current wiring.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=WovenClassWiringOfOldRevisionIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testWiringOfOldRevision: WovenClass.getBundleWiring() must return the wiring whose class loader defines the class
 *   (the old revision's wiring), but returned null
 * </pre>
 *
 * <h3>Expected</h3>
 * getBundleWiring() returns the old revision's wiring: same class loader as the class loaded before the update,
 * isCurrent() false, isInUse() true.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: the old revision's wiring (isCurrent false, isInUse true, its class loader is the
 * defining loader).
 *
 * <h3>Suggested fix</h3>
 * Create the WeavingContext (or the ContextClass) with the HostBundleRevision of the class loader
 * ({@code HostBundleClassLoader} knows its module/revision) and return {@code revision.getWiring()}.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class WovenClassWiringOfOldRevisionIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testWiringOfOldRevision() throws Exception {
        Bundle exporter = installBundle(getExporter("woven-old-revision", true));
        Bundle importer = installBundle(getImporter());
        final List<BundleWiring> wirings = new ArrayList<BundleWiring>();
        ServiceRegistration<WeavingHook> hookReg = null;
        try {
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(exporter, importer)));
            Class<?> loadedBefore = exporter.loadClass(A.class.getName());
            BundleWiring oldWiring = exporter.adapt(BundleWiring.class);
            assertSame(oldWiring.getClassLoader(), loadedBefore.getClassLoader());

            hookReg = getSystemContext().registerService(WeavingHook.class, new WeavingHook() {
                public void weave(WovenClass wovenClass) {
                    if (BeanB.class.getName().equals(wovenClass.getClassName()))
                        wirings.add(wovenClass.getBundleWiring());
                }
            }, null);

            // Update without refresh: the importer keeps the old revision in use
            exporter.update(toInputStream(getExporter("woven-old-revision", false)));

            // A class defined by the class loader of the old revision
            Class<?> clazz = loadedBefore.getClassLoader().loadClass(BeanB.class.getName());
            assertSame(loadedBefore.getClassLoader(), clazz.getClassLoader());

            assertEquals("Weaving hook called once for " + BeanB.class.getName(), 1, wirings.size());
            BundleWiring wiring = wirings.get(0);
            assertNotNull("WovenClass.getBundleWiring() must return the wiring whose class loader defines the class (the old revision's wiring), but returned null", wiring);
            assertSame("WovenClass.getBundleWiring() must return the old revision's wiring, whose class loader defines the class", loadedBefore.getClassLoader(), wiring.getClassLoader());
        } finally {
            if (hookReg != null)
                hookReg.unregister();
            importer.uninstall();
            exporter.uninstall();
        }
    }

    private JavaArchive getExporter(final String name, boolean withBeanB) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(A.class);
        if (withBeanB)
            archive.addClasses(BeanB.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addExportPackages(A.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    private JavaArchive getImporter() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "woven-old-revision-importer");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addImportPackages(A.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}
