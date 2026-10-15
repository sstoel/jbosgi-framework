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
import java.util.Arrays;
import java.util.Collection;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A requirement whose filter tests the namespace attribute with anything but one top-level equality - an OR of
 * values, a NOT, a wildcard - only finds the capabilities whose namespace value equals the first value that occurs
 * in the filter text; the other matching capabilities are never considered, so the requirement is not satisfied.
 *
 * <h3>Severity</h3>
 * major. Examples a real bundle has: {@code Bundle-RequiredExecutionEnvironment: CDC-1.0/Foundation-1.0,J2SE-1.4}
 * (many Eclipse/Equinox bundles; converted to {@code (|(&(osgi.ee=CDC/Foundation)(version=1.0))(&(osgi.ee=JavaSE)(version=1.4)))})
 * cannot be resolved on Java 25, nor can {@code Require-Capability: osgi.ee;filter:="(|(osgi.ee=JavaSE)(osgi.ee=JRE))"}
 * written the other way round, nor any generic requirement such as {@code (|(my.ns=a)(my.ns=b))},
 * {@code (!(my.ns=a))}, {@code (my.ns=b*)}, the presence filter {@code (my.ns=*)} or a comparison such as
 * {@code (my.ns>=a)} when a capability with exactly the compared value exists (then only that capability is
 * considered: a {@code cardinality:=multiple} requirement is wired to it alone, and if it is not resolvable the
 * requirement fails although other capabilities match).
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractEnvironment.findProviders} /
 * {@code CacheKey.create(Requirement)} / {@code findCachedCapabilities}, which use
 * {@code AbstractRequirement.getValueFromFilter} to pick a single namespace value out of the filter string.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code ee-31} - the R8
 * spec's own Bundle-RequiredExecutionEnvironment conversion example -, {@code or-ee-second},
 * {@code or-generic-second}, {@code or-ee-and-or}, {@code or-ee-not}, {@code or-ee-wild}); presence and comparison
 * cases added by the resolver workstream (Equinox differential {@code explore/resolver/sc/frag/g2, g4}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.3.6 Bundle Requirements (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "filter - (Filter) A filter expression that is asserted on the Capabilities belonging to the given namespace.
 * The matching of the filter against the capability is done on one capability at a time."<br>
 * 3.4.1: "Each element of the Bundle-RequiredExecutionEnvironment is ORed together in the final osgi.ee
 * requirement's filter directive" and "A bundle can only resolve if the framework is running on a VM which
 * implements one of the listed required execution environments."<br>
 * FrameworkWiring.findProviders javadoc: "Find bundle capabilities that match the given requirement."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:217-224}
 * (findProviders iterates only {@code findCachedCapabilities(CacheKey.create(req))}), {@code :376-390}
 * (all capabilities of the namespace are only considered when the extracted value is null or the operator is
 * fuzzy), {@code :415-421} (CacheKey.create), and
 * {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractRequirement.java:144-167} (getValueFromFilter takes
 * the first {@code (namespace=} occurrence of the filter string, ignoring |, ! and wildcards).
 * Present since 2012/2013 (dbd130c, bdaa078).
 *
 * <h3>Root cause</h3>
 * The capability cache is keyed by namespace value. For a requirement the key value is the first
 * {@code namespace=value} found in the filter text, whatever the structure of the filter. With {@code |} the
 * other alternatives are lost, with {@code !} exactly the wrong capabilities are looked up, and a wildcard value
 * {@code b*} or the presence value {@code *} is looked up literally. For the comparison operators
 * ({@code >=}, {@code <=}, {@code ~=}) findCachedCapabilities only widens the lookup to the whole namespace when
 * NO capability has exactly the compared value (line 382: {@code capset.isEmpty() && (key.value == null ||
 * key.hasFuzzyOperator())}); otherwise only the exact-value capabilities are candidates.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=OrFilterProviderLookupIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testOrOfNamespaceValues: The bundle with the requirement (|(or.ns=a)(or.ns=b)) must resolve against the
 *   capability or.ns=b
 * testNotOfNamespaceValue: The bundle with the requirement (!(or.ns=a)) must resolve against the capability or.ns=b
 * testWildcardNamespaceValue: The bundle with the requirement (or.ns=b*) must resolve against the capability or.ns=b
 * testPresenceOfNamespaceValue: The requirement (or.ns=*) must be satisfied by the capability or.ns=b, but
 *   findProviders returned [...] / The bundle with the requirement (or.ns=*) must resolve against the capability or.ns=b
 * testGreaterOrEqualWithUnresolvableExactValue: The bundle with the requirement (or.ns&gt;=a) must resolve against
 *   the capability or.ns=b (the capability or.ns=a is not resolvable)
 * testGreaterOrEqualCardinalityMultiple: The requirement (or.ns&gt;=a) with cardinality:=multiple must be wired to
 *   or.ns=a and or.ns=b expected:&lt;2&gt; but was:&lt;1&gt;
 * testRequiredExecutionEnvironmentNotListedFirst: The bundle requires 'CDC-1.0/Foundation-1.0,J2SE-1.4' and
 *   J2SE-1.4 is available, but it did not start: org.osgi.framework.BundleException: JBOSGI011250: Cannot resolve
 *   bundle: or-bree:0.0.0 caused by org.apache.felix.resolver.reason.ReasonException: Unable to resolve
 *   HostBundleRevision[or-bree:0.0.0]: missing requirement XCapabilityRequirement[osgi.ee,
 *   dirs={filter=(|(&amp;(osgi.ee=CDC/Foundation)(version=1.0))(&amp;(osgi.ee=JavaSE)(version=1.4)))} ...
 * </pre>
 * (FrameworkWiring.findProviders does return the capability: FrameworkWiringImpl scans all revisions itself. Only
 * the resolver's lookup through AbstractEnvironment is affected.)
 *
 * <h3>Expected</h3>
 * The requirer resolves against the capability {@code or.ns=b} for all these filters (each matches it), a
 * {@code cardinality:=multiple} requirement is wired to every matching capability, and the bundle requiring
 * {@code CDC-1.0/Foundation-1.0,J2SE-1.4} starts.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 and Felix 7.0.5 resolve all these requirements (differential harness, JDK 25).
 *
 * <h3>Suggested fix</h3>
 * Only use the cache key when the filter is a plain equality on the namespace attribute (operator "=", no wildcard)
 * or an AND whose operands include such an equality (that is what getValueFromFilter can safely detect); in every
 * other case - an OR or NOT anywhere above the namespace attribute, a value with a {@code *} (including the presence
 * filter {@code (ns=*)}), or a comparison operator {@code >=}, {@code <=}, {@code ~=} - look at all capabilities of
 * the namespace (set the key value to null). Note that nulling the key only for "(|", "(!" and '*' is not enough:
 * findCachedCapabilities must not return just the exact-value set for a comparison operator (drop the
 * {@code capset.isEmpty()} condition for fuzzy operators, or treat them like a null value). The manifest workstream
 * verified that nulling the key for "(|", "(!" and '*' makes the first four methods pass with the jbosgi-resolver
 * suite still green (JDK 25); the two comparison methods need the additional change.
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class OrFilterProviderLookupIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testOrOfNamespaceValues() throws Exception {
        assertProvided("(|(or.ns=a)(or.ns=b))");
    }

    @Test
    public void testNotOfNamespaceValue() throws Exception {
        assertProvided("(!(or.ns=a))");
    }

    @Test
    public void testWildcardNamespaceValue() throws Exception {
        assertProvided("(or.ns=b*)");
    }

    @Test
    public void testPresenceOfNamespaceValue() throws Exception {
        assertProvided("(or.ns=*)");
    }

    @Test
    public void testGreaterOrEqualWithUnresolvableExactValue() throws Exception {
        // or.ns=a is the exact compared value but its bundle cannot resolve, or.ns=b also matches (or.ns>=a)
        Bundle unresolvable = installBundle(getArchive("or-provider-a", "or.ns;or.ns=a", "or.missing", null));
        Bundle provider = installBundle(getArchive("or-provider", "or.ns;or.ns=b", null, null));
        Bundle requirer = installBundle(getArchive("or-requirer", null, "or.ns;filter:=\"(or.ns>=a)\"", null));
        try {
            FrameworkWiring fwiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            fwiring.resolveBundles(Arrays.asList(unresolvable, provider, requirer));
            assertTrue("The bundle with the requirement (or.ns>=a) must resolve against the capability or.ns=b (the capability or.ns=a is not resolvable)",
                    requirer.getState() == Bundle.RESOLVED);
        } finally {
            requirer.uninstall();
            provider.uninstall();
            unresolvable.uninstall();
        }
    }

    @Test
    public void testGreaterOrEqualCardinalityMultiple() throws Exception {
        Bundle providerA = installBundle(getArchive("or-provider-a", "or.ns;or.ns=a", null, null));
        Bundle providerB = installBundle(getArchive("or-provider", "or.ns;or.ns=b", null, null));
        Bundle requirer = installBundle(getArchive("or-requirer", null, "or.ns;filter:=\"(or.ns>=a)\";cardinality:=multiple", null));
        try {
            FrameworkWiring fwiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            assertTrue(fwiring.resolveBundles(Arrays.asList(providerA, providerB, requirer)));
            assertEquals("The requirement (or.ns>=a) with cardinality:=multiple must be wired to or.ns=a and or.ns=b", 2,
                    requirer.adapt(BundleWiring.class).getRequiredWires("or.ns").size());
        } finally {
            requirer.uninstall();
            providerB.uninstall();
            providerA.uninstall();
        }
    }

    @Test
    public void testRequiredExecutionEnvironmentNotListedFirst() throws Exception {
        final String bree = "CDC-1.0/Foundation-1.0,J2SE-1.4";
        Bundle bundle = installBundle(getArchive("or-bree", null, null, bree));
        try {
            try {
                bundle.start();
            } catch (BundleException ex) {
                fail("The bundle requires '" + bree + "' and J2SE-1.4 is available, but it did not start: " + ex
                        + (ex.getCause() != null ? " caused by " + ex.getCause() : ""));
            }
            assertEquals("The bundle must be ACTIVE", Bundle.ACTIVE, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    private void assertProvided(String filter) throws Exception {
        Bundle provider = installBundle(getArchive("or-provider", "or.ns;or.ns=b", null, null));
        Bundle requirer = installBundle(getArchive("or-requirer", null, "or.ns;filter:=\"" + filter + "\"", null));
        try {
            BundleRequirement req = requirer.adapt(BundleRevision.class).getDeclaredRequirements("or.ns").get(0);
            FrameworkWiring fwiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            Collection<BundleCapability> providers = fwiring.findProviders(req);
            assertEquals("The requirement " + filter + " must be satisfied by the capability or.ns=b, but findProviders returned " + providers,
                    1, providers.size());
            boolean resolved = fwiring.resolveBundles(Arrays.asList(provider, requirer));
            assertTrue("The bundle with the requirement " + filter + " must resolve against the capability or.ns=b", resolved);
        } finally {
            requirer.uninstall();
            provider.uninstall();
        }
    }

    private JavaArchive getArchive(String name, final String provide, final String require, final String bree) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                if (provide != null)
                    builder.addManifestHeader(Constants.PROVIDE_CAPABILITY, provide);
                if (require != null)
                    builder.addManifestHeader(Constants.REQUIRE_CAPABILITY, require);
                if (bree != null)
                    builder.addManifestHeader(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, bree);
                return builder.openStream();
            }
        });
        return archive;
    }
}
