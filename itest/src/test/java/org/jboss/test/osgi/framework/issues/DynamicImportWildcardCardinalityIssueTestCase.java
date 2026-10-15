package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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

import java.io.InputStream;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Namespace;

/**
 * The osgi.wiring.package requirement of a wildcard DynamicImport-Package clause has no cardinality directive,
 * although PackageNamespace requires cardinality:=multiple for exactly these requirements.
 *
 * <h3>Severity</h3>
 * cosmetic. Visible through the wiring API, to resolver hooks and to tools that compute what a bundle can import
 * from its requirements: a wildcard dynamic import is reported as a single-provider requirement.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, org.jboss.osgi.resolver.spi.AbstractResourceBuilder (DynamicImport-Package; fix there, test
 * in jbosgi-framework itest)
 *
 * <h3>Found by</h3>
 * services workstream round 2 (framework.api statements #354/#355), Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 javadoc, PackageNamespace (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html):
 * "A cardinality directive specified on the Import-Package or DynamicImport-Package headers must be ignored. Only
 * requirements with resolution set to dynamic and the package name contains a wildcard must have the cardinality
 * directive set to multiple. Otherwise, a cardinality directive must not be present in a requirement."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResourceBuilder.java:260-272 (only
 * resolution:=dynamic is added). Same code on jbosgi-resolver origin/WF41.
 *
 * <h3>Root cause</h3>
 * The builder does not add the cardinality directive for wildcard dynamic imports.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportWildcardCardinalityIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testWildcardDynamicImportHasCardinalityMultiple: the requirement of DynamicImport-Package dyn.card.* must have
 *   cardinality:=multiple, directives were {resolution=dynamic, filter=(osgi.wiring.package=dyn.card.*)}
 *   expected:&lt;multiple&gt; but was:&lt;null&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code cardinality=multiple} on the wildcard requirement, no cardinality on the non-wildcard one.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0: {@code DynamicImport-Package: b5.dyn.*,b5.single} gives
 * {filter=(osgi.wiring.package=b5.dyn.*), resolution=dynamic, cardinality=multiple} and
 * {filter=(osgi.wiring.package=b5.single), resolution=dynamic}.
 *
 * <h3>Suggested fix</h3>
 * In AbstractResourceBuilder, for DynamicImport-Package clauses whose package name ends with "*" (or is "*"), put
 * {@code cardinality=multiple} into the directives after copying the clause directives (dropping any cardinality from
 * the header, see manifest/05).
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class DynamicImportWildcardCardinalityIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testWildcardDynamicImportHasCardinalityMultiple() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "dyn-cardinality");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("dyn-cardinality");
                builder.addDynamicImportPackages("dyn.card.*");
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            BundleRequirement wildcard = null;
            for (BundleRequirement req : bundle.adapt(BundleRevision.class).getDeclaredRequirements(PackageNamespace.PACKAGE_NAMESPACE)) {
                if (String.valueOf(req.getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE)).contains("dyn.card.*"))
                    wildcard = req;
            }
            assertNotNull("the dynamic import requirement exists", wildcard);
            Map<String, String> dirs = wildcard.getDirectives();
            assertEquals("the requirement of DynamicImport-Package dyn.card.* must have cardinality:=multiple, directives were " + dirs,
                    Namespace.CARDINALITY_MULTIPLE, dirs.get(Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE));
        } finally {
            bundle.uninstall();
        }
    }
}
