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

import static org.junit.Assert.fail;

import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;

/**
 * BundleRevision.getDeclaredCapabilities and getDeclaredRequirements return modifiable lists, although their javadoc
 * specifies "An unmodifiable list".
 *
 * <h3>Severity</h3>
 * cosmetic. The lists are fresh copies, so modifying them does not change the revision; only code that relies on the
 * documented UnsupportedOperationException (or on getting the same unmodifiable view, as getCapabilities of the
 * Resource API already provides) sees a difference.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, org.jboss.osgi.resolver.spi.AbstractBundleRevision (fix there; test in jbosgi-framework itest)
 *
 * <h3>Found by</h3>
 * services workstream round 2 (framework.api statements on immutability of Capability/Requirement/Resource),
 * Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 javadoc, BundleRevision.getDeclaredCapabilities(String)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html): "@return An unmodifiable list containing
 * the declared BundleCapabilitys from the specified namespace." and getDeclaredRequirements(String): "@return An
 * unmodifiable list containing the declared BundleRequirements from the specified namespace."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver api/src/main/java/org/jboss/osgi/resolver/spi/AbstractBundleRevision.java:81-96
 * ({@code List<BundleCapability> result = new ArrayList<BundleCapability>(); ... return result;}).
 * Same code on jbosgi-resolver origin/WF41. Verified: with Collections.unmodifiableList in both methods (jbosgi-resolver
 * api rebuilt into a private Maven repository) both test methods pass.
 *
 * <h3>Root cause</h3>
 * The Resource API lists (getCapabilities/getRequirements) are wrapped with Collections.unmodifiableList, the
 * BundleRevision variants copy them into a new ArrayList that is returned as is.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DeclaredCapabilitiesModifiableIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testDeclaredCapabilitiesUnmodifiable: getDeclaredCapabilities must return an unmodifiable list, but clear() succeeded
 * testDeclaredRequirementsUnmodifiable: getDeclaredRequirements must return an unmodifiable list, but clear() succeeded
 * </pre>
 *
 * <h3>Expected</h3>
 * UnsupportedOperationException from any modifying operation on the returned lists.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0: getDeclaredCapabilities(null).clear() and getDeclaredRequirements(null).clear() throw
 * UnsupportedOperationException.
 *
 * <h3>Suggested fix</h3>
 * Return {@code Collections.unmodifiableList(result)} in both methods of AbstractBundleRevision.
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class DeclaredCapabilitiesModifiableIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testDeclaredCapabilitiesUnmodifiable() throws Exception {
        BundleRevision revision = getSystemContext().getBundle(0).adapt(BundleRevision.class);
        List<BundleCapability> caps = revision.getDeclaredCapabilities(null);
        try {
            caps.clear();
        } catch (UnsupportedOperationException ex) {
            return;
        }
        fail("getDeclaredCapabilities must return an unmodifiable list, but clear() succeeded");
    }

    @Test
    public void testDeclaredRequirementsUnmodifiable() throws Exception {
        BundleRevision revision = getSystemContext().getBundle(0).adapt(BundleRevision.class);
        List<BundleRequirement> reqs = revision.getDeclaredRequirements(null);
        try {
            reqs.clear();
        } catch (UnsupportedOperationException ex) {
            return;
        }
        fail("getDeclaredRequirements must return an unmodifiable list, but clear() succeeded");
    }
}
