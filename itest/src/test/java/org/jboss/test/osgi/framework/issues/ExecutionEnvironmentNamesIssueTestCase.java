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
import java.util.Collections;
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
import org.osgi.framework.Filter;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Namespace;

/**
 * Execution environment handling of Bundle-RequiredExecutionEnvironment: OSGi/Minimum-1.0 is not provided by the
 * framework, and execution environment names are put into the osgi.ee filter without escaping, so a name with
 * parentheses makes the install fail and a backslash is lost.
 *
 * <h3>Severity</h3>
 * minor. A bundle that declares {@code Bundle-RequiredExecutionEnvironment: OSGi/Minimum-1.0} (the R3 minimum
 * environment, which every Java SE VM implements) cannot be started on Java 25; a bundle with an unusual EE name
 * such as {@code Foo(bar)} cannot even be installed, although the header must never prevent an install.
 *
 * <h3>Component</h3>
 * jbosgi-framework {@code BundleManagerPlugin} (static list of execution environments, used for
 * org.osgi.framework.executionenvironment and the system bundle's osgi.ee capabilities via SystemBundlePlugin);
 * jbosgi-metadata {@code OSGiMetaDataBuilder.convertExecutionEnvironmentHeader} (filter construction).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox differential harness ({@code explore/manifest}, cases {@code ee-11}, {@code ee-22},
 * {@code ee-33}, {@code ee-start-minimum10}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 8.2 osgi.ee Namespace (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html):
 * "An OSGi Framework must register capabilities for all the execution environments the Java VM is known to be
 * backward compatible with." (its example lists osgi.ee="OSGi/Minimum"; version:List&lt;Version&gt;="1.0,1.1,1.2").<br>
 * 3.4.1 Bundle-RequiredExecutionEnvironment (framework.module.html): "The Bundle-RequiredExecutionEnvironment
 * header can not prevent a bundle from installing."; "If the bree term cannot be parsed into the given
 * constituents then the filter must look like: filter ::= '(osgi.ee=' &lt;ee name&gt; ')'" - a filter that asserts the
 * ee name, which requires the RFC 1960 escaping of '(', ')', '*' and '\' in the value.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:1017-1024}
 * (the list starts with OSGi/Minimum-1.1; no OSGi/Minimum-1.0); jbosgi-metadata
 * {@code src/main/java/org/jboss/osgi/metadata/OSGiMetaDataBuilder.java:312-321} and {@code :395-399} (the ee name
 * is concatenated into the filter unescaped; an InvalidSyntaxException becomes an IllegalArgumentException that
 * fails the install). Pre-existing.
 *
 * <h3>Root cause</h3>
 * Missing entry in the execution environment list; missing filter value escaping.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=ExecutionEnvironmentNamesIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testOSGiMinimum10: The bundle requires OSGi/Minimum-1.0, which Java 25 implements, but it did not start:
 *   org.osgi.framework.BundleException: JBOSGI011251: Unsupported execution environment [OSGi/Minimum-1.0] we have:
 *   [OSGi/Minimum-1.1, J2SE-1.1, OSGi/Minimum-1.2, ...]
 * testNameWithParenthesesDoesNotPreventInstall: Bundle-RequiredExecutionEnvironment: Foo(bar) must not prevent the
 *   install, but: org.osgi.framework.BundleException: JBOSGI010908: Cannot initialize resource from: ...
 * testBackslashInName: The osgi.ee requirement for 'Foo\Bar' must match the execution environment Foo\Bar, but its
 *   filter is (osgi.ee=FooBar)
 * </pre>
 *
 * <h3>Expected</h3>
 * The first bundle starts; the second installs (and stays unresolved); the third bundle's filter is
 * {@code (osgi.ee=Foo\\Bar)}.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 provides OSGi/Minimum 1.0, installs the Foo(bar) bundle with the filter
 * {@code (osgi.ee=Foo\(bar\))} and generates {@code (osgi.ee=Foo\\Bar)}.
 *
 * <h3>Suggested fix</h3>
 * Add OSGi/Minimum-1.0 to the execution environments (it is a subset of OSGi/Minimum-1.1); escape '\', '(', ')' and
 * '*' in n1/n2 and in the unparsed ee name before building the filter. Verified: adding OSGi/Minimum-1.0 to the
 * list in BundleManagerPlugin and escaping the name part in convertExecutionEnvironmentHeader makes the three test
 * methods pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class ExecutionEnvironmentNamesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testOSGiMinimum10() throws Exception {
        Bundle bundle = installBundle(getArchive("ee-minimum", "OSGi/Minimum-1.0"));
        try {
            try {
                bundle.start();
            } catch (BundleException ex) {
                fail("The bundle requires OSGi/Minimum-1.0, which Java 25 implements, but it did not start: " + ex
                        + (ex.getCause() != null ? " caused by " + ex.getCause() : ""));
            }
            assertEquals("The bundle must be ACTIVE", Bundle.ACTIVE, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testNameWithParenthesesDoesNotPreventInstall() throws Exception {
        Bundle bundle = null;
        try {
            bundle = installBundle(getArchive("ee-parens", "Foo(bar)"));
        } catch (BundleException ex) {
            fail("Bundle-RequiredExecutionEnvironment: Foo(bar) must not prevent the install, but: " + ex);
        } finally {
            if (bundle != null)
                bundle.uninstall();
        }
    }

    @Test
    public void testBackslashInName() throws Exception {
        Bundle bundle = installBundle(getArchive("ee-backslash", "Foo\\Bar"));
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE);
            String spec = reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE);
            Filter filter = getSystemContext().createFilter(spec);
            assertTrue("The osgi.ee requirement for 'Foo\\Bar' must match the execution environment Foo\\Bar, but its filter is " + spec,
                    filter.matches(Collections.<String, Object> singletonMap(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE, "Foo\\Bar")));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getArchive(final String name, final String bree) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, bree);
                return builder.openStream();
            }
        });
        return archive;
    }
}
