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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
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
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * A connect bundle whose module connector returns the same {@link ConnectContent} for the updated revision
 * cannot be used after {@link Bundle#update()}: the framework closes the content that the new revision uses.
 *
 * <h3>Severity</h3>
 * major. A connector that serves constant content (the common case: class path, module path, jlink image,
 * the spec's own example connector) breaks every connect bundle on its first {@code update()}. After the update,
 * {@code getEntry}, {@code findEntries}, resource loading and class loading of the bundle fail with the
 * IllegalStateException that the closed content throws (or silently read a closed jar).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ConnectVirtualFile} (inner class {@code Root}),
 * {@code UserBundleState.updateInternal}, {@code UserBundleRevision.closeConnectContent}.
 *
 * <h3>Found by</h3>
 * connect workstream, code review of the open/close bookkeeping plus a differential run of the same scenario
 * against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.3.2 Updating Connect Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html#framework.connect.update):
 * "Depending on the dynamic nature of the module connector, updating a connect bundle may result in the exact same
 * ConnectContent being used for each updated revision for the bundle. This implies that the same class loader and
 * content entries could be used for each updated revision."<br>
 * 60.3.3.1 Opening Connect Content: "The Framework must always ensure that the content is open before calling other
 * methods on the ConnectContent."<br>
 * 60.3.3.5 Closing Connect Content: "When a BundleRevision connected to a ConnectContent no longer isInUse() then the
 * Framework must call the ConnectContent method close() ..."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ConnectVirtualFile.java:76} creates a new {@code Root}
 * (with its own {@code open} flag, line 312) for every {@code connect()} call, also when the content object is the
 * same; {@code Root.open()} (line 322) and {@code Root.close()} (line 330) track the state per Root, not per content.
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:293} closes the old revision's
 * content right after the new revision was created ({@code currentRev.closeConnectContent()}), and
 * {@code UserBundleRevision.close()} (line 133) closes it again when the old revision is removed on refresh.
 * Introduced by 3680cfcc "Implement OSGi Core R8" (close-on-update added in 8322ec6d).
 *
 * <h3>Root cause</h3>
 * The open state of a {@link ConnectContent} is tracked per framework-side wrapper ({@code ConnectVirtualFile.Root}),
 * and every revision gets its own wrapper. On update the new revision's Root opens the content (a second
 * {@code open()} without an intervening {@code close()}) and remembers "open"; then the old revision's Root calls
 * {@code content.close()}. The new Root still believes the content is open and calls {@code getEntries()},
 * {@code getEntry()} and {@code getInputStream()} on the closed content, without ever calling {@code open()} again.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectContentSharedAcrossUpdateIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testEntryOfUpdatedRevisionWithSameContent: The content of the updated revision must be readable,
 *   but reading the entry failed with java.lang.IllegalStateException: getEntries() called on a closed content
 * testContentIsOpenWhileUsedByUpdatedRevision: The content is used by the current revision, so it must be open
 *   after the update, but the framework closed it (open() calls: 2, close() calls: 1)
 * </pre>
 *
 * <h3>Expected</h3>
 * After {@code update()} the bundle's current revision can read its entries; the content is open (or is re-opened
 * before use) while a revision that uses it is current or in use, and every {@code open()} is matched by one
 * {@code close()}.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300 (same scenario, same strict content): log "open, connect, close, open", the entry of the updated
 * revision reads "A", and the content is closed once the framework stops.
 *
 * <h3>Suggested fix</h3>
 * Keep one Root per {@link ConnectContent} instance (e.g. an identity map in the bundle manager, or reuse the old
 * revision's Root in {@code BundleManagerPlugin.connect} when {@code module.getContent()} returns the same object),
 * with a reference count of the revisions that use it: {@code open()} on the first user, {@code close()} when the
 * last revision that uses it is closed. Alternatively let {@code Root} re-check/re-open before every content call
 * and never close a content that another live Root still uses.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect is new in this branch).
 */
public class ConnectContentSharedAcrossUpdateIssueTestCase extends OSGiTest {

    private static final String LOCATION = "connect:shared";
    private static final String ENTRY = "org/test/a.txt";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testEntryOfUpdatedRevisionWithSameContent() throws Exception {
        StrictContent content = new StrictContent();
        Bundle bundle = installConnectBundle(content);
        assertEquals("A", read(bundle.getEntry(ENTRY)));

        // The connector returns the same content for the updated revision, which the spec explicitly allows
        bundle.update();

        try {
            URL entry = bundle.getEntry(ENTRY);
            assertNotNull("The entry of the updated revision must exist, but getEntry returned null", entry);
            assertEquals("A", read(entry));
        } catch (IllegalStateException ex) {
            fail("The content of the updated revision must be readable, but reading the entry failed with " + ex);
        }
    }

    @Test
    public void testContentIsOpenWhileUsedByUpdatedRevision() throws Exception {
        StrictContent content = new StrictContent();
        Bundle bundle = installConnectBundle(content);
        bundle.getEntry(ENTRY);

        bundle.update();

        assertTrue("The content is used by the current revision, so it must be open after the update, but the framework closed it (open() calls: "
                + content.opens + ", close() calls: " + content.closes + ")", content.open);
    }

    private Bundle installConnectBundle(final StrictContent content) throws Exception {
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

    /** A content that, like a jar based one, cannot be used while it is closed */
    static class StrictContent implements ConnectContent {

        boolean open;
        int opens;
        int closes;

        private void assertOpen(String method) {
            if (open == false)
                throw new IllegalStateException(method + " called on a closed content");
        }

        @Override
        public Optional<Map<String, String>> getHeaders() {
            assertOpen("getHeaders()");
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, "connect.shared");
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() {
            assertOpen("getEntries()");
            List<String> result = new ArrayList<String>();
            result.add("org/");
            result.add("org/test/");
            result.add(ENTRY);
            return result;
        }

        @Override
        public Optional<ConnectEntry> getEntry(final String path) {
            assertOpen("getEntry()");
            if (ENTRY.equals(path) == false)
                return Optional.empty();
            return Optional.<ConnectEntry> of(new ConnectEntry() {
                @Override
                public String getName() {
                    return ENTRY;
                }

                @Override
                public long getContentLength() {
                    return 1;
                }

                @Override
                public long getLastModified() {
                    return 0;
                }

                @Override
                public InputStream getInputStream() {
                    assertOpen("getInputStream()");
                    return new ByteArrayInputStream("A".getBytes());
                }
            });
        }

        @Override
        public Optional<ClassLoader> getClassLoader() {
            assertOpen("getClassLoader()");
            return Optional.empty();
        }

        @Override
        public void open() {
            opens++;
            open = true;
        }

        @Override
        public void close() {
            closes++;
            open = false;
        }
    }
}
