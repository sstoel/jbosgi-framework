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

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * Bundle.getHeaders(locale) only reads the most specific localization file that exists; a key that this file does
 * not define is not looked up in the less specific files, so the header is returned as the raw key.
 *
 * <h3>Severity</h3>
 * minor. A bundle that ships {@code bundle.properties} with all keys and a partial {@code bundle_en.properties}
 * (or a partial national translation, typically delivered by an NLS fragment) shows raw keys such as
 * {@code vendor} for every header the partial file does not translate, also from {@code getHeaders()} for the
 * default locale.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.AbstractCommonBundleRevision.getHeadersFromRaw}.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox differential harness ({@code explore/manifest}, cases {@code l10n-default},
 * {@code l10n-frag}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.11 Localization (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "If a translation is not found, the locale must be made more generic by first removing the variant, then the
 * country and finally the language until an entry is found that contains a valid translation. ...
 * This allows localization files for more specific locales to override localizations from less specific
 * localization files."<br>
 * 4.4.12 Retrieving Manifest Headers (framework.lifecycle.html): "If no translation is found for a specific key, the Dictionary returned by
 * Bundle.getHeaders will return the raw values as specified in the manifest header values without the leading
 * '%' character." - i.e. only when no file on the search path translates the key.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/AbstractCommonBundleRevision.java:61-93}: one
 * {@code getLocalizationEntry(baseName, locale)} URL (the first existing file of the search path) is loaded into a
 * single {@code PropertyResourceBundle} without a parent, and {@code resBundle.getString(key)} falls back to the
 * key. Unchanged since cd715ebf (2015).
 *
 * <h3>Root cause</h3>
 * The search stops at the first file that exists instead of at the first file that contains the key; the
 * PropertyResourceBundles of the less specific files are not chained as parents.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=LocalizationKeyFallbackIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testKeyMissingInLanguageFile: getHeaders("en") must take Bundle-Vendor from bundle.properties because
 *   bundle_en.properties does not define 'vendor' expected:&lt;[Base V]endor&gt; but was:&lt;[v]endor&gt;
 * testKeyMissingInCountryFile: getHeaders("en_US") must take Bundle-Name from bundle_en.properties because
 *   bundle_en_US.properties does not define 'name' expected:&lt;[English N]ame&gt; but was:&lt;[n]ame&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code Base Vendor} and {@code English Name}: each key is translated by the most specific file that defines it.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 returns {@code Base Vendor} and {@code English Name} (differential harness, JDK 25).
 *
 * <h3>Suggested fix</h3>
 * Load every existing file of the search path (base_lang_COUNTRY_variant ... base) and look each key up from the
 * most specific to the least specific one, e.g. by chaining PropertyResourceBundles with setParent in a small
 * subclass, or by merging Properties from least to most specific. Verified: loading all files of the search
 * path and taking each key from the first file that contains it makes both test methods pass, and the existing
 * LocalizationTestCase still passes (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class LocalizationKeyFallbackIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testKeyMissingInLanguageFile() throws Exception {
        Bundle bundle = installBundle(getArchive("l10n-language"));
        try {
            assertEquals("getHeaders(\"en\") must take Bundle-Vendor from bundle.properties because bundle_en.properties does not define 'vendor'",
                    "Base Vendor", bundle.getHeaders("en").get(Constants.BUNDLE_VENDOR));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testKeyMissingInCountryFile() throws Exception {
        Bundle bundle = installBundle(getArchive("l10n-country"));
        try {
            assertEquals("getHeaders(\"en_US\") must take Bundle-Name from bundle_en.properties because bundle_en_US.properties does not define 'name'",
                    "English Name", bundle.getHeaders("en_US").get(Constants.BUNDLE_NAME));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new StringAsset("name=Base Name\nvendor=Base Vendor\n"), "OSGI-INF/l10n/bundle.properties");
        archive.add(new StringAsset("name=English Name\n"), "OSGI-INF/l10n/bundle_en.properties");
        archive.add(new StringAsset("description=US Description\n"), "OSGI-INF/l10n/bundle_en_US.properties");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NAME, "%name");
                builder.addManifestHeader(Constants.BUNDLE_VENDOR, "%vendor");
                return builder.openStream();
            }
        });
        return archive;
    }
}
