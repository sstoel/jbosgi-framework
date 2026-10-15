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
import static org.junit.Assert.fail;

import java.io.InputStream;
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
import org.osgi.framework.wiring.BundleRevision;

/**
 * Typed attributes of Provide-Capability are parsed against the R8 rules in two ways: a Long whose quoted value
 * has white space around it makes the bundle fail to install, and an attribute with a type that does not exist
 * ({@code a:Integer=1}, {@code a:Foo=x}) is silently accepted as a String.
 *
 * <h3>Severity</h3>
 * minor. {@code a:Long=" 23 "} is explicitly valid in the spec, yet installBundle throws (with type UNSPECIFIED).
 * A misspelled or unsupported type ({@code Integer}, {@code long}, {@code List<Integer>}) is not reported; the
 * attribute becomes a String, so a filter such as {@code (a>=10)} compares lexicographically instead of failing at
 * install time as the spec requires for a syntax error.
 *
 * <h3>Component</h3>
 * jbosgi-metadata, {@code org.jboss.osgi.metadata.internal.LongValueCreator} (no trimming) and
 * {@code ValueCreatorUtil.forType} (unknown type falls back to STRING_VC), used by
 * {@code internal.ManifestParser.parse}.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code ty-pc-04},
 * {@code ty-pc-35}..{@code ty-pc-39}, {@code ty-pc-45}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.3.4 Bundle Capability Attributes
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html): "Numeric types must trim white space
 * around the value, for other types spaces around the argument are not ignored ... attr:Long="       23      ",
 * // ok, because nummeric".<br>
 * 3.3.3: "typed-attr ::= extended ( ':' type )? '=' argument; type ::= scalar | list; scalar ::= 'String' |
 * 'Version' | 'Long' | 'Double'; list ::= 'List' ( '&lt;' scalar '&gt;' )?".<br>
 * 3.12 Bundle Validity: "Any syntactic error (for example, improperly formatted version or bundle symbolic name,
 * unrecognized directive value, etc.)" causes a bundle to fail to install.
 *
 * <h3>Code location</h3>
 * jbosgi-metadata {@code src/main/java/org/jboss/osgi/metadata/internal/LongValueCreator.java:37}
 * ({@code Long.valueOf(attribute)}; the trim flag of AbstractValueCreator is false for LONG_VC, while
 * {@code Double.valueOf} happens to accept spaces) and
 * {@code src/main/java/org/jboss/osgi/metadata/internal/ValueCreatorUtil.java:48-66} (forType returns STRING_VC
 * for any unknown type); {@code ManifestParser.java:127-138}. Pre-existing.
 *
 * <h3>Root cause</h3>
 * LONG_VC is created without trimming; forType has no error path.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=TypedAttributeParsingIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testLongWithWhiteSpaceInQuotes: Provide-Capability ty.ns;a:Long=" 23 " is valid (numeric types trim white
 *   space), but the bundle did not install: org.osgi.framework.BundleException: JBOSGI010908: Cannot initialize
 *   resource from: [ty-long:0.0.0] ...
 * testUnknownScalarTypeIsRejected: Provide-Capability ty.ns;a:Integer=1 has a type that is not defined, the
 *   install must fail, but the bundle was installed with a = 1 (java.lang.String)
 * testUnknownListElementTypeIsRejected: ... ty.ns;a:List&lt;Integer&gt;=1 ... installed with a = 1 (java.lang.String)
 * </pre>
 *
 * <h3>Expected</h3>
 * The first bundle installs with {@code a = 23L}; the other two installs fail with a BundleException (of type
 * MANIFEST_ERROR; the test accepts any type because jbosgi reports a header that fails to parse after the
 * metadata validation with type UNSPECIFIED, which is ManifestErrorTypeIssueTestCase).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 installs the first bundle with a Long 23 and rejects the unknown types with MANIFEST_ERROR
 * ("Unsupported type: Integer"); Felix 7.0.5 also accepts the first and rejects the others.
 *
 * <h3>Suggested fix</h3>
 * Create LONG_VC (and LONG_LIST_VC elements, DOUBLE_VC) with trimming; let forType throw an
 * IllegalArgumentException for a type outside String, Version, Long, Double, List and List&lt;scalar&gt; (the
 * install then fails). Verified: LONG_VC = new LongValueCreator(true) plus an IllegalArgumentException for
 * unknown types in forType makes all three test methods pass, and the jbosgi-metadata tests still pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class TypedAttributeParsingIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testLongWithWhiteSpaceInQuotes() throws Exception {
        Bundle bundle = null;
        try {
            bundle = install("ty-long", "ty.ns;a:Long=\" 23 \"");
        } catch (BundleException ex) {
            fail("Provide-Capability ty.ns;a:Long=\" 23 \" is valid (numeric types trim white space), but the bundle did not install: " + ex);
        }
        try {
            Map<String, Object> atts = bundle.adapt(BundleRevision.class).getDeclaredCapabilities("ty.ns").get(0).getAttributes();
            assertEquals("The attribute a is the Long 23", Long.valueOf(23), atts.get("a"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testUnknownScalarTypeIsRejected() throws Exception {
        assertRejected("ty-integer", "ty.ns;a:Integer=1");
    }

    @Test
    public void testUnknownListElementTypeIsRejected() throws Exception {
        assertRejected("ty-list-integer", "ty.ns;a:List<Integer>=1");
    }

    private void assertRejected(String name, String header) throws Exception {
        Bundle bundle;
        try {
            bundle = install(name, header);
        } catch (BundleException ex) {
            // Rejected. The exception type should be MANIFEST_ERROR, see ManifestErrorTypeIssueTestCase
            return;
        }
        Object value = bundle.adapt(BundleRevision.class).getDeclaredCapabilities("ty.ns").get(0).getAttributes().get("a");
        bundle.uninstall();
        fail("Provide-Capability " + header + " has a type that is not defined, the install must fail, but the bundle was installed with a = "
                + value + " (" + (value != null ? value.getClass().getName() : null) + ")");
    }

    private Bundle install(final String name, final String provide) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader(Constants.PROVIDE_CAPABILITY, provide);
                return builder.openStream();
            }
        });
        return installBundle(archive);
    }
}
