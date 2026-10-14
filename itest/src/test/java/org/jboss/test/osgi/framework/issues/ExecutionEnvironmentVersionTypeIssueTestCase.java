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

import java.util.ArrayList;
import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;

/**
 * The osgi.ee capabilities of the system bundle that have one version (JavaSE/compact1, JavaSE/compact2,
 * JavaSE/compact3) declare the version attribute as a single {@code Version} instead of a {@code List<Version>}.
 *
 * <h3>Severity</h3>
 * minor. Filters still match, but code that reads the attribute as the namespace defines it - a resolver hook, a
 * management tool or a provisioning resolver doing
 * {@code (List<Version>) cap.getAttributes().get("version")} - gets a ClassCastException for these capabilities.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.SystemBundlePlugin} (osgi.ee capabilities built
 * from org.osgi.framework.executionenvironment).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox differential harness ({@code explore/manifest}, system bundle capability dump in
 * {@code out/report.json}); review of commit d7b20d08.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 8.2 osgi.ee Namespace, Table 8.2
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html): attribute {@code version},
 * kind CA, M, type {@code List<Version>}: "A comma separated list of versions, comparisons must be done with version
 * ordering because the version attribute must be declared with the List&lt;Version&gt; type."
 * ExecutionEnvironmentNamespace.CAPABILITY_VERSION_ATTRIBUTE javadoc: "The capability attribute contains the
 * versions of the execution environment. The value of this attribute must be of type List&lt;Version&gt;."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/SystemBundlePlugin.java:188}
 * ({@code ";version:" + (versions.size() > 1 ? "List<Version>" : "Version")}), introduced by d7b20d08 "Type the
 * version of an execution environment" (before it, a single version was an untyped String, also wrong).
 *
 * <h3>Root cause</h3>
 * The type is chosen by the number of versions instead of always being List&lt;Version&gt;.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=ExecutionEnvironmentVersionTypeIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testVersionIsListOfVersion: The version attribute of every osgi.ee capability must be a List&lt;Version&gt;, but
 *   these are not: [JavaSE/compact1 -&gt; org.osgi.framework.Version, JavaSE/compact2 -&gt; org.osgi.framework.Version,
 *   JavaSE/compact3 -&gt; org.osgi.framework.Version]
 * </pre>
 *
 * <h3>Expected</h3>
 * Every osgi.ee capability has {@code version:List<Version>}, e.g. {@code [1.8.0]} for the compact profiles.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 declares List&lt;Version&gt; for all osgi.ee capabilities, the compact profiles included.
 *
 * <h3>Suggested fix</h3>
 * Always use {@code ";version:List<Version>="}, also for one version. Verified: with that change the test passes
 * and CoreR7TestCase/CoreR8TestCase still pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * yes - measured by the coordinator's regression classification: the test passes on the WF41 framework (6.6.0.Final), which declares no compact-profile osgi.ee capabilities; they were added with a single Version by 410b7493 (corrected by the coordinator).
 */
public class ExecutionEnvironmentVersionTypeIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testVersionIsListOfVersion() throws Exception {
        BundleRevision sysrev = getSystemContext().getBundle().adapt(BundleRevision.class);
        List<String> wrong = new ArrayList<String>();
        for (BundleCapability cap : sysrev.getDeclaredCapabilities(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)) {
            Object name = cap.getAttributes().get(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE);
            Object version = cap.getAttributes().get(ExecutionEnvironmentNamespace.CAPABILITY_VERSION_ATTRIBUTE);
            boolean ok = version instanceof List;
            if (ok) {
                for (Object element : (List<?>) version)
                    ok &= element instanceof Version;
            }
            if (!ok)
                wrong.add(name + " -> " + (version != null ? version.getClass().getName() : null));
        }
        assertTrue("The version attribute of every osgi.ee capability must be a List<Version>, but these are not: " + wrong, wrong.isEmpty());
    }
}
