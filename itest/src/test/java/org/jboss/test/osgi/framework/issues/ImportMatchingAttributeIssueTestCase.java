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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Arbitrary matching attributes of Import-Package are not matched as the spec defines: a typed attribute
 * ({@code a:Long=5}) is silently dropped from the requirement, a value with parentheses makes the install fail, a
 * value with {@code *} is matched as a wildcard, and leading/trailing white space is not ignored.
 *
 * <h3>Severity</h3>
 * minor. {@code Import-Package: p;a:Long=5} (invalid syntax for Import-Package, but accepted) wires to an exporter
 * with {@code a=6} - the constraint is lost silently; {@code a="(x)"} makes the bundle uninstallable;
 * {@code company="AC*"} matches every exporter whose company starts with AC; {@code company=" ACME "} does not match
 * {@code company=ACME}.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractRequirement.generateFilterDirective} /
 * {@code addAttributePart}, which turns the matching attributes into the filter directive of the
 * osgi.wiring.package (and osgi.wiring.bundle/host) requirement.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code ty-imp-00..02},
 * {@code ma-typed}, {@code ma-paren}, {@code ma-star}, {@code ma-ws}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.7.7 Attribute Matching (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "In order for an import definition to be resolved to an export definition, the values of the attributes
 * specified by the import definition must match the values of the attributes of the export definition. ...
 * Attribute values are compared string wise except for the version and bundle-version attributes which use version
 * range comparisons. Leading and trailing white space in attribute values must be ignored."<br>
 * 3.6.4 Import-Package: "import ::= package-names ( ';' parameter )*" with 1.3.2 "attribute ::= extended '='
 * argument" (no type for Import-Package attributes; "a:Long" is not an extended), and 3.12: "Any syntactic error
 * ..." causes a bundle to fail to install.
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractRequirement.java:357-362}
 * ({@code addAttributePart}: a non-String value is removed and not added to the filter; a String value is put into
 * the filter unescaped and untrimmed); {@code :328-352} (generateFilterDirective). Since dbd130c (2012).
 *
 * <h3>Root cause</h3>
 * The import attributes are converted to a filter by string concatenation, which drops non-String values and lets
 * the filter syntax ({@code ( ) * \}) and white space of the value leak into the matching.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=ImportMatchingAttributeIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testTypedAttributeIsNotDropped: Import-Package ima.typed;a:Long=5 must not resolve against Export-Package
 *   ima.typed;a=6 (or be rejected at install), but it resolved
 * testValueWithParentheses: Import-Package ima.paren;a="(x)" is valid and must resolve against Export-Package
 *   ima.paren;a="(x)", but the install failed: org.osgi.framework.BundleException: JBOSGI010908: Cannot initialize
 *   resource from: ...
 * testValueWithAsteriskIsComparedStringWise: Import-Package ima.star;a="x*y" must not resolve against
 *   Export-Package ima.star;a=xzzy, attribute values are compared string wise
 * testLeadingAndTrailingWhiteSpaceIgnored: Import-Package ima.ws;a=" x " must resolve against Export-Package
 *   ima.ws;a=x, leading and trailing white space must be ignored
 * </pre>
 *
 * <h3>Expected</h3>
 * As stated in the assertion messages.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 and Felix 7.0.5 keep the typed attribute ({@code (a=5)}, no wire). Felix installs and resolves
 * the parentheses case (it escapes the value), Equinox installs it but does not resolve. Both RIs share the
 * wildcard and white space deviations; the spec text is explicit for both.
 *
 * <h3>Suggested fix</h3>
 * In addAttributePart use {@code String.valueOf(value).trim()} for every value type (or reject typed attributes in
 * Import-Package/Require-Bundle/Fragment-Host at install with MANIFEST_ERROR) and escape {@code \ ( ) *} in the
 * value; trim capability attribute values for the comparison as well, or compare in a custom matcher. Verified:
 * {@code String.valueOf(value).trim()} plus escaping in addAttributePart makes the four test methods pass, and the
 * jbosgi-resolver tests and ResolverTestCase, RequireBundleTestCase, FragmentTestCase, ExportFilterTestCase still
 * pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class ImportMatchingAttributeIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testTypedAttributeIsNotDropped() throws Exception {
        Bundle exporter = install("ima-typed-exp", Constants.EXPORT_PACKAGE, "ima.typed;a=6");
        try {
            Bundle importer;
            try {
                importer = install("ima-typed-imp", Constants.IMPORT_PACKAGE, "ima.typed;a:Long=5");
            } catch (BundleException ex) {
                return; // rejecting the invalid syntax is acceptable as well
            }
            try {
                assertFalse("Import-Package ima.typed;a:Long=5 must not resolve against Export-Package ima.typed;a=6 (or be rejected at install), but it resolved",
                        resolve(exporter, importer));
            } finally {
                importer.uninstall();
            }
        } finally {
            exporter.uninstall();
        }
    }

    @Test
    public void testValueWithParentheses() throws Exception {
        assertResolves("ima.paren", "a=\"(x)\"", "a=\"(x)\"", true);
    }

    @Test
    public void testValueWithAsteriskIsComparedStringWise() throws Exception {
        assertResolves("ima.star", "a=xzzy", "a=\"x*y\"", false);
    }

    @Test
    public void testLeadingAndTrailingWhiteSpaceIgnored() throws Exception {
        assertResolves("ima.ws", "a=x", "a=\" x \"", true);
    }

    private void assertResolves(String pkg, String exportAttr, String importAttr, boolean expected) throws Exception {
        String exportHeader = pkg + ";" + exportAttr;
        String importHeader = pkg + ";" + importAttr;
        Bundle exporter = install(pkg + "-exp", Constants.EXPORT_PACKAGE, exportHeader);
        try {
            Bundle importer;
            try {
                importer = install(pkg + "-imp", Constants.IMPORT_PACKAGE, importHeader);
            } catch (BundleException ex) {
                fail("Import-Package " + importHeader + " is valid and must " + (expected ? "" : "not ") + "resolve against Export-Package " + exportHeader
                        + ", but the install failed: " + ex);
                return;
            }
            try {
                String message = "Import-Package " + importHeader + " must " + (expected ? "" : "not ") + "resolve against Export-Package " + exportHeader
                        + (expected ? ", leading and trailing white space must be ignored" : ", attribute values are compared string wise");
                if (importAttr.contains("("))
                    message = "Import-Package " + importHeader + " is valid and must resolve against Export-Package " + exportHeader;
                assertTrue(message, resolve(exporter, importer) == expected);
            } finally {
                importer.uninstall();
            }
        } finally {
            exporter.uninstall();
        }
    }

    private boolean resolve(Bundle... bundles) throws Exception {
        return getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundles));
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
