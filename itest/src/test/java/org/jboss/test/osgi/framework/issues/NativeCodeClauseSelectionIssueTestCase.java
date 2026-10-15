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
import java.util.Locale;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * A Bundle-NativeCode clause that does not specify osname or processor is never selected, and processor and
 * language are matched case-sensitively, so bundles whose native code clause is valid for the running platform
 * fail to resolve.
 *
 * <h3>Severity</h3>
 * major. A bundle with an unconditional native code clause ({@code Bundle-NativeCode: lib/libfoo.so}) or a clause
 * that only names the operating system ({@code lib/libfoo.so; osname=Linux}) cannot be resolved or started on any
 * platform: it fails with "JBOSGI011260: No native code clause selected". Equinox and Felix start these bundles.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.NativeCodeImpl.matchParameter}, called from
 * {@code resolveNativeCode} while the bundle is resolved.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code nc-00},
 * {@code nc-start-uncond}, {@code nc-start-osname-only}, {@code nc-start-macosx}, {@code nc-start-proc-upper},
 * {@code nc-start-lang-upper}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.10.1 Native Code Algorithm
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "~= represents the match operation. The match operation is a case insensitive comparison. ...
 * Only select the native code clauses for which the following expressions all evaluate to true.
 * osname ~= [org.osgi.framework.os.name] or osname is not specified;
 * processor ~= [org.osgi.framework.processor] or processor is not specified; ...
 * language ~= [org.osgi.framework.language] or language is not specified".<br>
 * The conversion to the osgi.native requirement (3.10) agrees: a clause without selection attributes yields a
 * requirement that matches every environment, which is what this branch already generates
 * (NativeNamespaceSupport, commit 8322ec6d) - but the framework's own clause selection still rejects it.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeCodeImpl.java:268}
 * ({@code boolean match = (osnameParam != null);}), {@code :297} ({@code match &= (procParam != null);}),
 * {@code :304} ({@code proc.equals(fwProcessor)}), {@code :338} ({@code language.equals(fwLanguage)});
 * the exception is thrown at {@code :222}. Unchanged since 2011 (faf3b793, NativeCodePluginImpl).
 *
 * <h3>Root cause</h3>
 * matchParameter implements the R4 algorithm in which osname and processor were mandatory: a missing osname or
 * processor makes the clause not match. Processor and language are compared with {@code equals} instead of the
 * case insensitive match operation.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=NativeCodeClauseSelectionIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testClauseWithoutSelectionAttributes: A native code clause without selection attributes matches every platform,
 *   but the bundle did not start: JBOSGI011250: Cannot resolve bundle ... JBOSGI011260: No native code clause
 *   selected for: [lib/libfoo.so[attr={},dirs={}]]
 * testClauseWithOsnameOnly: ... No native code clause selected for: [lib/libfoo.so[attr={osname=[Mac OS X]},dirs={}]]
 * testClauseWithProcessorOnly: ... No native code clause selected for: [lib/libfoo.so[attr={processor=[aarch64]},...
 * testProcessorMatchIsCaseInsensitive: ... No native code clause selected ... processor=[AARCH64] ...
 * testLanguageMatchIsCaseInsensitive: ... No native code clause selected ... language=[EN] ...
 * </pre>
 *
 * <h3>Expected</h3>
 * Each bundle resolves and starts (state ACTIVE): its clause names only values of the running platform, in a
 * different case at most, or no selection attributes at all.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 and Felix 7.0.5 start all five bundles (differential harness, JDK 25).
 *
 * <h3>Suggested fix</h3>
 * In matchParameter start with {@code match = true}, evaluate osname/processor only when the attribute is present,
 * and compare osname, processor (and their aliases) and language with {@code equalsIgnoreCase}; in
 * resolveNativeCode skip the optional {@code *} marker clause, which would otherwise now match as a clause.
 * Verified: with exactly these changes this test passes and the existing NativeCodeTestCase,
 * NativeNamespaceTestCase and NativeCodePermissionsTestCase still pass (JDK 25).<br>
 * Related, filed separately by the fuzz workstream: NativeCodeRepeatedAttributeIssueTestCase (repeated osname
 * values joined into one filter value) and NativeCodeMissingLibraryIssueTestCase (missing library fails the
 * resolve).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same), but the branch claims R8 native code support (8322ec6d) and its new
 * NativeNamespaceTestCase.testClauseWithoutSelection only checks findProviders(), never resolves such a bundle.
 */
public class NativeCodeClauseSelectionIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testClauseWithoutSelectionAttributes() throws Exception {
        assertStarts("native-no-selection", "lib/libfoo.so",
                "A native code clause without selection attributes matches every platform");
    }

    @Test
    public void testClauseWithOsnameOnly() throws Exception {
        assertStarts("native-osname-only", "lib/libfoo.so; osname=\"" + property(Constants.FRAMEWORK_OS_NAME) + "\"",
                "A native code clause that names the running OS and no processor matches");
    }

    @Test
    public void testClauseWithProcessorOnly() throws Exception {
        assertStarts("native-processor-only", "lib/libfoo.so; processor=\"" + property(Constants.FRAMEWORK_PROCESSOR) + "\"",
                "A native code clause that names the running processor and no OS matches");
    }

    @Test
    public void testProcessorMatchIsCaseInsensitive() throws Exception {
        String header = "lib/libfoo.so; osname=\"" + property(Constants.FRAMEWORK_OS_NAME) + "\"; processor=\""
                + property(Constants.FRAMEWORK_PROCESSOR).toUpperCase(Locale.ENGLISH) + "\"";
        assertStarts("native-processor-case", header, "The processor match operation is case insensitive");
    }

    @Test
    public void testLanguageMatchIsCaseInsensitive() throws Exception {
        String header = "lib/libfoo.so; osname=\"" + property(Constants.FRAMEWORK_OS_NAME) + "\"; processor=\""
                + property(Constants.FRAMEWORK_PROCESSOR) + "\"; language=\"" + property(Constants.FRAMEWORK_LANGUAGE).toUpperCase(Locale.ENGLISH) + "\"";
        assertStarts("native-language-case", header, "The language match operation is case insensitive");
    }

    private void assertStarts(String name, String nativeCode, String reason) throws Exception {
        Bundle bundle = installBundle(getNativeCodeArchive(name, nativeCode));
        try {
            try {
                bundle.start();
            } catch (BundleException ex) {
                fail(reason + ", but the bundle with 'Bundle-NativeCode: " + nativeCode + "' did not start: " + ex + " caused by " + ex.getCause());
            }
            assertEquals(reason + ", the bundle must be ACTIVE", Bundle.ACTIVE, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    private String property(String key) throws Exception {
        String value = getSystemContext().getProperty(key);
        assertEquals("The framework defines " + key, true, value != null);
        return value;
    }

    private JavaArchive getNativeCodeArchive(String name, final String nativeCode) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libfoo.so");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, nativeCode);
                return builder.openStream();
            }
        });
        return archive;
    }
}
