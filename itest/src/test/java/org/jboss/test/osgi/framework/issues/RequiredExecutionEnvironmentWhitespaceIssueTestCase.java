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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Namespace;

/**
 * The entries of Bundle-RequiredExecutionEnvironment are not trimmed: in the usual {@code "a, b"} notation every
 * entry after the first keeps its leading space, so it never matches an execution environment of the framework.
 *
 * <h3>Severity</h3>
 * major. A bundle with {@code Bundle-RequiredExecutionEnvironment: CDC-1.0/Foundation-1.0, J2SE-1.4} (a very common
 * header of older Eclipse/Apache bundles) cannot be started: the converted osgi.ee requirement contains
 * {@code (osgi.ee= JavaSE)} (with a space) and the start-time check compares {@code " J2SE-1.4"} with the
 * execution environment names. Only bundles whose first listed environment is supported start.
 *
 * <h3>Component</h3>
 * jbosgi-metadata, {@code org.jboss.osgi.metadata.spi.AbstractOSGiMetaData.getRequiredExecutionEnvironment}
 * (via {@code internal.StringListValueCreator}); consumed by jbosgi-resolver
 * {@code AbstractResourceBuilder.loadFrom} / {@code OSGiMetaDataBuilder.convertExecutionEnvironmentHeader} (the
 * osgi.ee requirement) and jbosgi-framework {@code UserBundleState.assertStartConditions} (the start check).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code ee-30},
 * {@code ee-31} - the R8 spec's own example header - and {@code ee-start-spec-example}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.4.1 Bundle-RequiredExecutionEnvironment
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "Bundle-RequiredExecutionEnvironment ::= ee-name ( ',' ee-name )*" with the example
 * "Bundle-RequiredExecutionEnvironment: CDC-1.0/Foundation-1.0, OSGi/Minimum-1.1"; "A bundle can only resolve if
 * the framework is running on a VM which implements one of the listed required execution environments."
 * The conversion example maps "CDC-1.0/Foundation-1.0, OSGi/Minimum-1.2, J2SE-1.4, ..." to
 * "(&amp;(osgi.ee=OSGi/Minimum)(version=1.2))", "(&amp;(osgi.ee=JavaSE)(version=1.4))", ... (no spaces).
 * 1.3.2: "Whitespaces between terminals are ignored unless specifically noted."
 *
 * <h3>Code location</h3>
 * jbosgi-metadata {@code src/main/java/org/jboss/osgi/metadata/spi/AbstractOSGiMetaData.java:164-166}
 * ({@code get(BUNDLE_REQUIREDEXECUTIONENVIRONMENT, STRING_LIST_VC)}) and
 * {@code src/main/java/org/jboss/osgi/metadata/internal/StringListValueCreator.java:43}
 * ({@code ElementParser.parseDelimitedString(attribute, ',', false)}, i.e. without trimming). The same symptom
 * was patched for Bundle-ClassPath only, by trimming in {@code getBundleClassPath} (branch commit b0ad60a).
 * Consumers: jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:752-764},
 * jbosgi-metadata {@code OSGiMetaDataBuilder.java:339-407} (convertExecutionEnvironmentHeader).
 * Code from 2011/2012 (d13bf10, 08f2aa0).
 *
 * <h3>Root cause</h3>
 * StringListValueCreator splits the header at commas without trimming the parts (its trim flag is ignored), so
 * {@code getRequiredExecutionEnvironment()} returns {@code [CDC-1.0/Foundation-1.0, " J2SE-1.4"]}.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=RequiredExecutionEnvironmentWhitespaceIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testSecondEnvironmentAfterCommaSpace: The bundle requires 'JavaSE-99, JavaSE-1.8' and JavaSE-1.8 is available,
 *   but it did not start: org.osgi.framework.BundleException: JBOSGI011251: Unsupported execution environment
 *   [JavaSE-99,  JavaSE-1.8] we have: [OSGi/Minimum-1.1, J2SE-1.1, ..., JavaSE-1.8, ...]
 * testConvertedRequirementMatchesJavaSE: The osgi.ee requirement converted from 'JavaSE-99, JavaSE-1.8' must
 *   match the JavaSE 1.8 environment, but its filter is
 *   (|(&amp;(osgi.ee=JavaSE)(version=99))(&amp;(osgi.ee= JavaSE)(version=1.8)))
 * </pre>
 *
 * <h3>Expected</h3>
 * The bundle starts; the osgi.ee requirement filter is
 * {@code (|(&(osgi.ee=JavaSE)(version=99))(&(osgi.ee=JavaSE)(version=1.8)))}.<br>
 * (The test lists a JavaSE version first on purpose: with another environment name first, for example
 * {@code CDC-1.0/Foundation-1.0, J2SE-1.4}, the bundle also fails after this fix because of the separate defect
 * OrFilterProviderLookupIssueTestCase - only the first osgi.ee value of the filter is looked up.)
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 and Felix 7.0.5 start both bundles and generate the filter without spaces.
 *
 * <h3>Suggested fix</h3>
 * Trim the entries in {@code getRequiredExecutionEnvironment} (as {@code getBundleClassPath} now does for its
 * entries); Bundle-Category has the same problem. Do not trim in StringListValueCreator itself: STRING_LIST_VC
 * also parses {@code List<String>} capability attributes, whose element values must keep their white space
 * (3.3.4: "White space around an element value must be trimmed for non-String element types").
 * Verified: trimming the entries makes both test methods pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class RequiredExecutionEnvironmentWhitespaceIssueTestCase extends OSGiFrameworkTest {

    private static final String BREE = "JavaSE-99, JavaSE-1.8";

    @Test
    public void testSecondEnvironmentAfterCommaSpace() throws Exception {
        Bundle bundle = installBundle(getArchive("bree-start", BREE));
        try {
            try {
                bundle.start();
            } catch (BundleException ex) {
                fail("The bundle requires '" + BREE + "' and JavaSE-1.8 is available, but it did not start: " + ex
                        + (ex.getCause() != null ? " caused by " + ex.getCause() : ""));
            }
            assertEquals("The bundle must be ACTIVE", Bundle.ACTIVE, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testConvertedRequirementMatchesJavaSE() throws Exception {
        Bundle bundle = installBundle(getArchive("bree-requirement", BREE));
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE);
            assertEquals("One osgi.ee requirement for the Bundle-RequiredExecutionEnvironment header", 1, reqs.size());
            String filterSpec = reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE);
            Filter filter = getSystemContext().createFilter(filterSpec);

            Map<String, Object> javase = new HashMap<String, Object>();
            javase.put(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE, "JavaSE");
            javase.put(ExecutionEnvironmentNamespace.CAPABILITY_VERSION_ATTRIBUTE, Version.parseVersion("1.8"));
            assertTrue("The osgi.ee requirement converted from '" + BREE + "' must match the JavaSE 1.8 environment, but its filter is " + filterSpec,
                    filter.matches(javase));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getArchive(String name, final String bree) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, bree);
                return builder.openStream();
            }
        });
        return archive;
    }
}
