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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * The headers that a {@link ConnectContent} provides are turned into a synthesized {@code META-INF/MANIFEST.MF}
 * entry, which adds a header the content does not have, hides the content's own manifest entry, and rejects headers
 * that are not valid JAR attribute names.
 *
 * <h3>Severity</h3>
 * minor. Visible to connectors that provide headers (the main use of the Connect spec): {@code getHeaders()} has an
 * extra {@code Manifest-Version} header; {@code getEntry("META-INF/MANIFEST.MF")} returns bytes the content does not
 * have (or a phantom entry when the content has no manifest); a connector that maps headers whose names contain
 * characters outside {@code [A-Za-z0-9_-]} (for example dots) or are longer than 70 characters cannot install the bundle
 * at all, with the misleading message "Invalid number format".
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ConnectVirtualFile} ({@code getChild},
 * {@code openStream}, {@code Root.getManifestBytes}).
 *
 * <h3>Found by</h3>
 * connect workstream, review of ConnectVirtualFile plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.3.3.2 Bundle Manifest Headers
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html): "If the Optional returned has a map
 * present then the map must be used to provide the raw, unlocalized, headers for the bundle. ... the key/value pairs
 * must be used for the Dictionary returned by the bundle getHeaders() methods."<br>
 * 60.3.3.4 Connect Content Entries: "The connect content entries are used by the Framework for the following: To
 * provide content for the introspective methods on Bundle and BundleWiring. For example, the getEntry(String),
 * getEntryPaths(String) and findEntries(String,String,int) method."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ConnectVirtualFile.java:196} returns the synthesized
 * {@code manifestFile} for {@code META-INF/MANIFEST.MF} whenever the content provides headers, and line 264 serves it
 * from {@code Root.getManifestBytes()} (lines 372-383), which adds {@code Manifest-Version: 1.0} (line 375) and passes
 * every header through {@code java.util.jar.Attributes.putValue} (line 378), which only accepts JAR attribute names.
 * The bundle headers are then parsed back from these bytes. Introduced by 3680cfcc "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * Instead of using the header map directly as the bundle's raw headers, the framework serializes it into a JAR
 * manifest and re-reads it, and it exposes that serialized manifest as a bundle entry in place of the content's entry.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectHeadersSynthesizedManifestIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testHeadersAreTheContentHeaders: getHeaders() must contain exactly the headers of the connect content, but it has
 *   the extra header Manifest-Version=1.0
 * testManifestEntryComesFromTheContent: getEntry(META-INF/MANIFEST.MF) must return the manifest entry of the connect
 *   content expected:&lt;...Real-Manifest: yes...&gt; but was:&lt;Manifest-Version: 1.0 ...&gt;
 * testNoManifestEntryWhenTheContentHasNone: getEntry(META-INF/MANIFEST.MF) must be null for a content without that
 *   entry, but was bundle://.../META-INF/MANIFEST.MF
 * testHeaderNameThatIsNotAJarAttributeName: A connect bundle whose content provides the header X.Custom.Header must be
 *   installed, but installBundle threw org.osgi.framework.BundleException: JBOSGI011242: Invalid number format:
 *   X.Custom.Header
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code getHeaders()} contains exactly the content's headers; bundle entries are the content's entries (the real
 * manifest entry, or none); any header name the content provides is accepted.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: getHeaders() is exactly the map, getEntry("META-INF/MANIFEST.MF") returns the content's own entry
 * ("Real-Manifest: yes") or null when the content has none, and a header named {@code X.Dotted.Header} (and a value
 * containing a line break) is accepted.
 *
 * <h3>Suggested fix</h3>
 * Build the {@code OSGiMetaData} directly from the header map (e.g. {@code OSGiMetaDataBuilder.load(Map)} or a
 * Dictionary-based builder) in the deployment/metadata path for connect content, and drop the synthesized
 * {@code manifestFile} from {@code ConnectVirtualFile} so that entries are always the content's entries.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect is new in this branch).
 */
public class ConnectHeadersSynthesizedManifestIssueTestCase extends OSGiTest {

    private static final String LOCATION = "connect:headers";
    private static final String REAL_MANIFEST = "Manifest-Version: 1.0\r\nReal-Manifest: yes\r\n\r\n";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testHeadersAreTheContentHeaders() throws Exception {
        Content content = new Content(false);
        Bundle bundle = install(content);
        Dictionary<String, String> headers = bundle.getHeaders("");
        List<String> extra = new ArrayList<String>();
        for (String key : Collections.list(headers.keys())) {
            if (content.headers.containsKey(key) == false)
                extra.add(key + "=" + headers.get(key));
        }
        assertEquals("getHeaders() must contain exactly the headers of the connect content, but it has the extra header(s) " + extra, 0, extra.size());
    }

    @Test
    public void testManifestEntryComesFromTheContent() throws Exception {
        Bundle bundle = install(new Content(true));
        URL entry = bundle.getEntry("META-INF/MANIFEST.MF");
        assertNotNull("The connect content has a META-INF/MANIFEST.MF entry, so getEntry must return it", entry);
        assertEquals("getEntry(META-INF/MANIFEST.MF) must return the manifest entry of the connect content", REAL_MANIFEST, read(entry));
    }

    @Test
    public void testNoManifestEntryWhenTheContentHasNone() throws Exception {
        Bundle bundle = install(new Content(false));
        assertNull("getEntry(META-INF/MANIFEST.MF) must be null for a content without that entry", bundle.getEntry("META-INF/MANIFEST.MF"));
    }

    @Test
    public void testHeaderNameThatIsNotAJarAttributeName() throws Exception {
        Content content = new Content(false);
        content.headers.put("X.Custom.Header", "value");
        try {
            Bundle bundle = install(content);
            assertEquals("value", bundle.getHeaders("").get("X.Custom.Header"));
        } catch (BundleException ex) {
            fail("A connect bundle whose content provides the header X.Custom.Header must be installed, but installBundle threw " + ex);
        }
    }

    private Bundle install(final Content content) throws Exception {
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                if (LOCATION.equals(location) == false)
                    return Optional.empty();
                return Optional.<ConnectModule> of(new ConnectModule() {
                    @Override
                    public ConnectContent getContent() {
                        return content;
                    }
                });
            }

            @Override
            public Optional<BundleActivator> newBundleActivator() {
                return Optional.empty();
            }
        };
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(ConnectFrameworkFactory.class).newFramework(props, connector);
        framework.start();
        return framework.getBundleContext().installBundle(LOCATION);
    }

    private static String read(URL url) throws Exception {
        try (InputStream input = url.openStream()) {
            return new String(input.readAllBytes(), "UTF-8");
        }
    }

    static class Content implements ConnectContent {

        final Map<String, String> headers = new LinkedHashMap<String, String>();
        final Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();

        Content(boolean withManifestEntry) throws Exception {
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, "connect.headers");
            entries.put("org/", null);
            entries.put("org/test/", null);
            entries.put("org/test/a.txt", "A".getBytes("UTF-8"));
            if (withManifestEntry) {
                entries.put("META-INF/", null);
                entries.put("META-INF/MANIFEST.MF", REAL_MANIFEST.getBytes("UTF-8"));
            }
        }

        @Override
        public Optional<Map<String, String>> getHeaders() {
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() {
            return new ArrayList<String>(entries.keySet());
        }

        @Override
        public Optional<ConnectEntry> getEntry(final String path) {
            final byte[] bytes = entries.get(path);
            if (bytes == null)
                return Optional.empty();
            return Optional.<ConnectEntry> of(new ConnectEntry() {
                @Override
                public String getName() {
                    return path;
                }

                @Override
                public long getContentLength() {
                    return bytes.length;
                }

                @Override
                public long getLastModified() {
                    return 0;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            });
        }

        @Override
        public Optional<ClassLoader> getClassLoader() {
            return Optional.empty();
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }
    }
}
