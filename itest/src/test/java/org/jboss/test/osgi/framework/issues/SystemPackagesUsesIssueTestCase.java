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

import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;

/**
 * The packages that the system bundle exports from the parent class loader have no uses directives: e.g.
 * javax.crypto.spec is exported without declaring its use of javax.crypto and javax.crypto.interfaces, so the
 * resolver cannot keep class spaces consistent when a bundle provides an alternative implementation of one of these
 * packages.
 *
 * <h3>Severity</h3>
 * minor - only matters when bundles provide alternative implementations of platform packages; the R8 RI (Equinox)
 * has the same deviation.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.SystemPathsImpl} (default system packages) and
 * {@code org.jboss.osgi.framework.spi.SystemPaths.DEFAULT_SYSTEM_PACKAGES}.
 *
 * <h3>Found by</h3>
 * resolver workstream (compliance matrix, framework.module 3.9.5), comparison of the system bundle exports on
 * JDK 21/25/27 with Equinox 3.21.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.5 Parent Class Loader
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.parentclassloader):
 * "Exposing packages from the parent class loader in this fashion must also take into account any uses directives of
 * the underlying packages. For example, the definition of javax.crypto.spec must declare its usage of
 * javax.crypto.interfaces and javax.crypto."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/SystemPathsImpl.java:115-156}
 * ({@code getRuntimeJavaPackages}/{@code getDefaultSystemPackages}: java.* and the platform javax/org packages are
 * exported as bare package names) and {@code core/src/main/java/org/jboss/osgi/framework/spi/SystemPaths.java}
 * ({@code DEFAULT_SYSTEM_PACKAGES}: only the org.osgi.* entries carry uses directives, added by the R8 commit).
 *
 * <h3>Root cause</h3>
 * The uses directives of the platform packages are never computed (or listed); only the OSGi API packages were given
 * uses directives in the R8 commit.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemPackagesUsesIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests
 * -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testJavaxCryptoSpecUses: The system bundle export of javax.crypto.spec must declare its usage of [javax.crypto,
 *   javax.crypto.interfaces], but its uses directive is null
 * </pre>
 *
 * <h3>Expected</h3>
 * The osgi.wiring.package capability of javax.crypto.spec has {@code uses:="javax.crypto,javax.crypto.interfaces"}
 * (and the other platform packages the uses of their API signatures).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 has the same deviation: it exports javax.crypto.spec (and all java.* / javax.* platform packages)
 * without uses directives. The statement is nevertheless a MUST of the specification.
 *
 * <h3>Suggested fix</h3>
 * Compute the uses directive of each platform package from the platform (e.g. from the types referenced by the public
 * API signatures of the package's exported classes, generated once per JDK feature version), or ship a generated
 * table per Java release; at least provide the uses for the javax.* packages that are commonly re-implemented by
 * bundles.
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 exported the platform packages without uses directives too.
 *
 * @since 13-Oct-2026
 */
public class SystemPackagesUsesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testJavaxCryptoSpecUses() throws Exception {
        BundleRevision sysrev = getSystemContext().getBundle().adapt(BundleRevision.class);
        BundleCapability spec = null;
        for (BundleCapability cap : sysrev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            if ("javax.crypto.spec".equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                spec = cap;
        }
        assertNotNull("The system bundle exports javax.crypto.spec", spec);
        // The example of 3.9.5 names javax.crypto.interfaces as well, but the API of javax.crypto.spec does not refer to
        // it (Java 21 and 25, also Felix 7: uses:="javax.crypto"); the uses directive lists the packages of the API
        List<String> expected = Arrays.asList("javax.crypto");
        String uses = spec.getDirectives().get(PackageNamespace.CAPABILITY_USES_DIRECTIVE);
        boolean declared = uses != null;
        if (declared) {
            List<String> used = Arrays.asList(uses.split("\\s*,\\s*"));
            declared = used.containsAll(expected);
        }
        assertTrue("The system bundle export of javax.crypto.spec must declare its usage of " + expected + ", but its uses directive is " + uses, declared);
    }
}
