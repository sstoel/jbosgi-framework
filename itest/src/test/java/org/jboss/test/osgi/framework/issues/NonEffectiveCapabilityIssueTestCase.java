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
import static org.junit.Assert.assertFalse;

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A capability with an effective directive other than {@code resolve} (for example {@code effective:=active})
 * satisfies requirements during resolving and gets wired.
 *
 * <h3>Severity</h3>
 * minor. A bundle that requires {@code eff.ns} resolves (and is wired to the provider) although the only provider
 * declares the capability for another effective time; the resolver must not consider such capabilities.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractResolveContext.findProviders} (and
 * {@code AbstractEnvironment.findProviders}): only requirements are checked for effective ({@code isEffective}),
 * capabilities are not.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox differential harness ({@code explore/manifest}, cases {@code cap-resolve-pc-effective},
 * {@code ign-pc-effective-resolve}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.3.3 Bundle Capabilities (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "effective - (resolve) Specifies the time a capability is available, either resolve (default) or another name.
 * The OSGi framework resolver only considers Capabilities without an effective directive or effective:=resolve.
 * Capabilities with other values for the effective directive can be considered by an external agent."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResolveContext.java:62-68}
 * (findProviders returns {@code environment.findProviders(req)} unfiltered; {@code :78-83} isEffective only
 * covers requirements) and {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:217-276}.
 * Present since 2012.
 *
 * <h3>Root cause</h3>
 * No code path drops capabilities whose effective directive is not {@code resolve}.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=NonEffectiveCapabilityIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testActiveCapabilityIsNotConsidered: The only capability eff.ns has effective:=active, the requirement on eff.ns
 *   must stay unsatisfied, but the requirer was wired: [Wire[HostBundleRevision[eff-requirer:0.0.0]
 *   {XCapabilityRequirement[eff.ns,...]} =&gt; HostBundleRevision[eff-provider:0.0.0]
 *   {XProvidedCapability[eff.ns,dirs={effective=active},...]}]]
 * </pre>
 *
 * <h3>Expected</h3>
 * resolveBundles returns false and the requiring bundle stays INSTALLED.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 does not resolve the requiring bundle (differential harness, JDK 25); Felix 7.0.5 has the same
 * defect as jbosgi.
 *
 * <h3>Suggested fix</h3>
 * In AbstractResolveContext.findProviders remove capabilities whose
 * {@code Namespace.CAPABILITY_EFFECTIVE_DIRECTIVE} is set and not {@code resolve} (the system bundle's and wiring
 * namespaces' capabilities have none). Verified: that filter in AbstractResolveContext.findProviders makes the
 * test pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class NonEffectiveCapabilityIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testActiveCapabilityIsNotConsidered() throws Exception {
        Bundle provider = install("eff-provider", Constants.PROVIDE_CAPABILITY, "eff.ns;effective:=active");
        Bundle requirer = install("eff-requirer", Constants.REQUIRE_CAPABILITY, "eff.ns");
        try {
            FrameworkWiring fwiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            boolean resolved = fwiring.resolveBundles(Arrays.asList(provider, requirer));
            BundleWiring wiring = requirer.adapt(BundleWiring.class);
            assertFalse("The only capability eff.ns has effective:=active, the requirement on eff.ns must stay unsatisfied, but the requirer was wired: "
                    + (wiring != null ? wiring.getRequiredWires("eff.ns") : null), resolved);
            assertEquals("The requiring bundle must stay INSTALLED", Bundle.INSTALLED, requirer.getState());
        } finally {
            requirer.uninstall();
            provider.uninstall();
        }
    }

    private Bundle install(final String name, final String header, final String value) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader(header, value);
                return builder.openStream();
            }
        });
        return installBundle(archive);
    }
}
