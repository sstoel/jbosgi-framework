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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.bundle.support.a.ObjectA;
import org.jboss.test.osgi.framework.bundle.support.a.ObjectA2;
import org.jboss.test.osgi.framework.bundle.support.b.ObjectB;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The include/exclude class filtering of Export-Package is wrong: an export with only an include directive hides every
 * class of every exported package (also the included ones), and the filter of one exported package is applied to the
 * classes of the bundle's other exported packages.
 *
 * <h3>Severity</h3>
 * major - a bundle that uses {@code include:=} (or {@code include:=} plus {@code exclude:=} on one of several exported
 * packages) exports unusable packages: importers resolve but get ClassNotFoundException for classes that must be
 * visible.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ModuleManagerImpl.getExportClassFilter}.
 *
 * <h3>Found by</h3>
 * resolver workstream (compliance matrix, 3.7.9 Class Filtering), Equinox differential
 * ($SP/explore/resolver/sc/incl: {@code p;include:=X,q}: jbosgi CNFE for p.X, q.X, q.Y; Equinox 3.21 loads them).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.7.9 Class Filtering
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.classfiltering):
 * "An exporter can limit the visibility of the classes in a package with the include and exclude directives on the
 * export definition. [...] The default for the include directive is an asterisk ('*') (wildcard matching all names),
 * and for the exclude directive, so that no classes are excluded, an empty list that matches no names. [...] A class
 * is only visible if it is: Matched with an entry in the included list, and Not matched with an entry in the excluded
 * list." 3.6.5 Export-Package: "include - A comma-separated list of class names that must be visible to an importer."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:438-476}: the
 * missing exclude filter defaults to {@code PathFilters.rejectAll()} although {@code excludeFilter} holds the
 * negated (accepting) filter, and {@code includeFilter}/{@code excludeFilter} are single variables overwritten per
 * package and then applied to the classes of all packages. Pre-existing (same on WF41).
 *
 * <h3>Root cause</h3>
 * (1) default {@code rejectAll()} for an absent exclude list rejects everything when only include is given;
 * (2) the filters are not scoped to their package.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ExportClassFilterIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests
 * -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testIncludeOnly: org.jboss.test.osgi.framework.bundle.support.a.ObjectA is included by the export and must be
 *   visible to the importer, but: java.lang.ClassNotFoundException: org.jboss.test.osgi.framework.bundle.support.a.ObjectA
 *   from [Module "jbosgi.filter-include-only-importer:0.0.0.bid2rev0" from FrameworkModuleLoaderImpl]
 * testFilterDoesNotAffectOtherPackages: org.jboss.test.osgi.framework.bundle.support.b.ObjectB is exported without a
 *   filter and must be visible to the importer, but: java.lang.ClassNotFoundException: ...ObjectB from [Module
 *   "jbosgi.filter-other-package-importer:0.0.0.bid4rev0" from FrameworkModuleLoaderImpl]
 * </pre>
 *
 * <h3>Expected</h3>
 * Included classes are visible, excluded classes are not, and packages without directives export all their classes.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: {@code p;include:=X,q} exports p.X, q.X and q.Y (p.Y hidden); {@code p;include:="X,Y";exclude:=Y,q}
 * exports p.X, q.X and q.Y.
 *
 * <h3>Suggested fix</h3>
 * Build one filter per exported package: {@code all(isChildOf/in package, any(includes) or acceptAll,
 * not(any(excludes)) or acceptAll)} and combine the packages with {@code any(...)}, plus {@code acceptAll} for the
 * classes of packages without directives (or match per package path).
 *
 * <h3>Regression vs WF41</h3>
 * no - same code on WF41.
 *
 * @since 13-Oct-2026
 */
public class ExportClassFilterIssueTestCase extends OSGiFrameworkTest {

    private static final String PACKAGE_A = ObjectA.class.getPackage().getName();
    private static final String PACKAGE_B = ObjectB.class.getPackage().getName();

    @Test
    public void testIncludeOnly() throws Exception {
        Bundle exporter = installBundle(createExporter("filter-include-only", PACKAGE_A + ";include:=\"ObjectA\""));
        Bundle importer = installBundle(createImporter("filter-include-only-importer", PACKAGE_A));
        try {
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(exporter, importer)));
            assertVisible(importer, ObjectA.class.getName(), "is included by the export");
            assertInvisible(importer, ObjectA2.class.getName());
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    @Test
    public void testFilterDoesNotAffectOtherPackages() throws Exception {
        Bundle exporter = installBundle(createExporter("filter-other-package", PACKAGE_A + ";include:=\"ObjectA,ObjectA2\";exclude:=\"ObjectA2\"," + PACKAGE_B));
        Bundle importer = installBundle(createImporter("filter-other-package-importer", PACKAGE_A + "," + PACKAGE_B));
        try {
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(exporter, importer)));
            assertVisible(importer, ObjectB.class.getName(), "is exported without a filter");
            assertVisible(importer, ObjectA.class.getName(), "is included by the export");
            assertInvisible(importer, ObjectA2.class.getName());
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    private void assertVisible(Bundle importer, String className, String why) {
        try {
            assertNotNull(importer.loadClass(className));
        } catch (ClassNotFoundException ex) {
            fail(className + " " + why + " and must be visible to the importer, but: " + ex);
        }
    }

    private void assertInvisible(Bundle importer, String className) {
        try {
            importer.loadClass(className);
            fail(className + " is excluded by the export and must not be visible to the importer");
        } catch (ClassNotFoundException ex) {
            // expected
        }
    }

    private JavaArchive createExporter(final String name, final String exports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(ObjectA.class, ObjectA2.class, ObjectB.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader("Export-Package", exports);
                return builder.openStream();
            }
        });
        return archive;
    }

    private JavaArchive createImporter(final String name, final String imports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader("Import-Package", imports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
