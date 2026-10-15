package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.TreeSet;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleWiring;

/**
 * The file pattern of Bundle.findEntries, BundleWiring.findEntries and BundleWiring.listResources is used as a regular
 * expression: '.' matches any character, '$', '(', '+' and friends are regex operators, so names like
 * {@code Outer$Inner.class} or {@code a(1).txt} are never found and {@code *.txt} also matches {@code abtxt}.
 *
 * <h3>Severity</h3>
 * minor - the common patterns ({@code *.class}, {@code *.xml}) mostly work, but an exact name with a '$' (inner
 * classes), parentheses or '+' is not found, '.' over-matches, and a pattern such as {@code a[} throws a
 * PatternSyntaxException out of findEntries.
 *
 * <h3>Component</h3>
 * jbosgi-vfs, vfs30, {@code org.jboss.osgi.vfs30.VFSFindEntriesEnumeration.VisitorImpl.convertToPattern}
 * (Bundle.findEntries and BundleWiring.findEntries of bundles with a VirtualFile root);
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractBundleWiring.convertToPattern}
 * (BundleWiring.listResources); jbosgi-framework, core,
 * {@code org.jboss.osgi.framework.internal.ModuleEntriesProvider.convertToPattern} (findEntries of bundles that adapt
 * a jboss-modules Module).
 *
 * <h3>Found by</h3>
 * resolver workstream (class loading / resources), Equinox differential of findEntries/listResources with file names
 * that contain regex metacharacters.
 *
 * <h3>Spec reference</h3>
 * Bundle.findEntries / BundleWiring.findEntries / BundleWiring.listResources javadoc (OSGi Core R8, chapter 10
 * Framework API, https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html): "filePattern -
 * The file name pattern for selecting entries in the specified path. The pattern is only matched against the last
 * element of the entry path. [...] Substring matching is supported, as specified in the Filter specification, using
 * the wildcard character ("*")." Filter syntax (3.2.7): only '*' is a wildcard; '\' escapes '*', '(', ')' and '\'.
 *
 * <h3>Code location</h3>
 * jbosgi-vfs {@code vfs30/src/main/java/org/jboss/osgi/vfs30/VFSFindEntriesEnumeration.java:141-144};
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractBundleWiring.java:260-263};
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleEntriesProvider.java:142-145}.
 * All three are {@code Pattern.compile("^" + filePattern.replace("*", ".*") + "$")}; pre-existing (not touched by
 * the R7/R8 commits).
 *
 * <h3>Root cause</h3>
 * Only '*' is translated; every other character of the file pattern goes into the regular expression unquoted.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FindEntriesFilePatternIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testFindEntriesLiteralCharacters: Bundle.findEntries("p", "Outer$Inner.class") expected:&lt;[[/p/Outer$Inner.class]]&gt; but was:&lt;[[]]&gt;
 * testFindEntriesDotIsNoWildcard: Bundle.findEntries("p", "*.txt") expected:&lt;.../p/a+b.txt, /p/a.txt[]]&gt; but was:&lt;.../p/a+b.txt, /p/a.txt[, /p/abtxt]]&gt;
 * testListResourcesLiteralCharacters: BundleWiring.listResources("p", "a(1).txt") expected:&lt;[[p/a(1).txt]]&gt; but was:&lt;[[]]&gt;
 * testListResourcesDotIsNoWildcard: BundleWiring.listResources("p", "*.txt") expected:&lt;..., p/a+b.txt, p/a.txt[]]&gt; but was:&lt;..., p/a+b.txt, p/a.txt[, p/abtxt]]&gt;
 * testFindEntriesBracket: Bundle.findEntries("p", "a[") matches no entry expected:&lt;[[]]&gt; but was:&lt;[must not throw:
 *   java.util.regex.PatternSyntaxException: Unclosed character class near index 3
 * </pre>
 *
 * <h3>Expected</h3>
 * Only '*' acts as a wildcard; all other characters match literally.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 returns {@code /p/Outer$Inner.class} for {@code Outer$Inner.class} and {@code *$*.class},
 * {@code /p/a(1).txt} for {@code a(1).txt}, and does not return {@code abtxt} for {@code *.txt} (findEntries and
 * listResources).
 *
 * <h3>Suggested fix</h3>
 * Build the regex from the literal parts: split the pattern at '*' (honouring the Filter escapes) and join
 * {@code Pattern.quote(part)} with {@code ".*"}; or match with {@code FrameworkUtil.createFilter("(name=" + pattern + ")")}
 * as Equinox and Felix do. Apply in all three places.
 *
 * <h3>Regression vs WF41</h3>
 * no - same code on WF41; still a spec violation on the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class FindEntriesFilePatternIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFindEntriesLiteralCharacters() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            assertEquals("Bundle.findEntries(\"p\", \"Outer$Inner.class\")", "[/p/Outer$Inner.class]", findEntries(bundle, "Outer$Inner.class"));
            assertEquals("Bundle.findEntries(\"p\", \"a(1).txt\")", "[/p/a(1).txt]", findEntries(bundle, "a(1).txt"));
            assertEquals("Bundle.findEntries(\"p\", \"a+b.txt\")", "[/p/a+b.txt]", findEntries(bundle, "a+b.txt"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFindEntriesDotIsNoWildcard() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            assertEquals("Bundle.findEntries(\"p\", \"*.txt\")", "[/p/a(1).txt, /p/a+b.txt, /p/a.txt]", findEntries(bundle, "*.txt"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFindEntriesBracket() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            String result;
            try {
                result = findEntries(bundle, "a[");
            } catch (RuntimeException ex) {
                result = "must not throw: " + ex;
            }
            assertEquals("Bundle.findEntries(\"p\", \"a[\") matches no entry", "[]", result);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testListResourcesLiteralCharacters() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            bundle.start();
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            assertEquals("BundleWiring.listResources(\"p\", \"a(1).txt\")", "[p/a(1).txt]", new TreeSet<String>(wiring.listResources("p", "a(1).txt", BundleWiring.LISTRESOURCES_LOCAL)).toString());
            assertEquals("BundleWiring.listResources(\"p\", \"Outer$Inner.class\")", "[p/Outer$Inner.class]", new TreeSet<String>(wiring.listResources("p", "Outer$Inner.class", BundleWiring.LISTRESOURCES_LOCAL)).toString());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testListResourcesDotIsNoWildcard() throws Exception {
        Bundle bundle = installBundle(createBundle());
        try {
            bundle.start();
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            Collection<String> names = wiring.listResources("p", "*.txt", BundleWiring.LISTRESOURCES_LOCAL);
            assertEquals("BundleWiring.listResources(\"p\", \"*.txt\")", "[p/a(1).txt, p/a+b.txt, p/a.txt]", new TreeSet<String>(names).toString());
        } finally {
            bundle.uninstall();
        }
    }

    private String findEntries(Bundle bundle, String pattern) {
        TreeSet<String> result = new TreeSet<String>();
        Enumeration<URL> entries = bundle.findEntries("p", pattern, false);
        while (entries != null && entries.hasMoreElements())
            result.add(entries.nextElement().getPath());
        return result.toString();
    }

    private JavaArchive createBundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "file-pattern");
        for (String name : new String[] { "Outer$Inner.class", "a(1).txt", "a+b.txt", "a.txt", "abtxt" })
            archive.add(new StringAsset("content"), "p/" + name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        return archive;
    }
}
