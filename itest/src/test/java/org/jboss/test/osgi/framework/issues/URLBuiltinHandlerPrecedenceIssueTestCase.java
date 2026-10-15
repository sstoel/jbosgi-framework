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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Dictionary;
import java.util.Hashtable;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;

/**
 * The framework's URLStreamHandlerFactory does not look for built-in handlers through
 * {@code java.protocol.handler.pkgs} before it searches the service registry: a URL Stream Handler service registered
 * for a scheme that has a built-in handler takes the scheme over.
 *
 * <h3>Severity</h3>
 * minor. Handlers that the platform or the launcher provides through {@code java.protocol.handler.pkgs} can be replaced
 * by any bundle that registers a URLStreamHandlerService for the same scheme, for every URL of that scheme created
 * afterwards in the whole JVM (the JVM caches the handler).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.URLHandlerSupportImpl.createURLStreamHandler}
 * (via {@code OSGiStreamHandlerFactory}).
 *
 * <h3>Found by</h3>
 * connect workstream (compliance matrix service.url#25/#26, which were UNVERIFIED in round 1), exploratory test plus a
 * differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 52.3.2 Built-in Handlers (https://docs.osgi.org/specification/osgi.core/8.0.0/service.url.html): "To
 * facilitate the discovery of built-in handlers that are available through the name construction, the method described
 * in the next section must be used by the Framework before any handlers are searched for in the service registry."<br>
 * 52.3.3 Finding Built-in Handlers: "If the system properties java.protocol.handler.pkgs or java.content.handler.pkgs
 * are defined, they must be used to locate built-in handlers. ... A URL Stream Handler name for a scheme is formed by
 * appending the string ".Handler" to the scheme name."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/URLHandlerSupportImpl.java} {@code createURLStreamHandler}
 * returns a proxy whenever a service is registered for the scheme and never consults
 * {@code java.protocol.handler.pkgs} (only {@code createContentHandler} looks for built-in content handlers, through
 * {@code hasBuiltinContentHandler}). Because the JVM asks the installed factory before its own
 * {@code java.protocol.handler.pkgs} lookup, the registered service wins. Unchanged on this branch (pre-existing).
 *
 * <h3>Root cause</h3>
 * The built-in stream handler lookup of 52.3.3 is not implemented in the stream handler factory.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=URLBuiltinHandlerPrecedenceIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: The built-in handler found through java.protocol.handler.pkgs must be used before the
 *   service registry expected:&lt;[built-in]&gt; but was:&lt;[bundle]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The URL is handled by {@code org.jboss.test.osgi.framework.issues.urlbuiltin.issuesbuiltin.Handler}.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300 (same set-up: built-in handler in a package listed in java.protocol.handler.pkgs and a registered
 * URLStreamHandlerService for the same scheme) uses the built-in handler.
 *
 * <h3>Suggested fix</h3>
 * In {@code createURLStreamHandler} first try the class name construction for every package of
 * {@code java.protocol.handler.pkgs} (and return null when a class is found, so that the JVM uses it), as
 * {@code hasBuiltinContentHandler} already does for content handlers.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class URLBuiltinHandlerPrecedenceIssueTestCase extends OSGiFrameworkTest {

    private static final String PROTOCOL = "issuesbuiltin";

    @Test
    public void testBuiltinHandlerBeforeServiceRegistry() throws Exception {
        String previous = System.getProperty("java.protocol.handler.pkgs");
        System.setProperty("java.protocol.handler.pkgs", "org.jboss.test.osgi.framework.issues.urlbuiltin" + (previous != null ? "|" + previous : ""));
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(URLConstants.URL_HANDLER_PROTOCOL, PROTOCOL);
        ServiceRegistration<?> registration = getSystemContext().registerService(URLStreamHandlerService.class.getName(), new BundleHandler(), props);
        try {
            URL url = new URL(PROTOCOL + "://host/path");
            assertEquals("The built-in handler found through java.protocol.handler.pkgs must be used before the service registry", "built-in", read(url));
        } finally {
            registration.unregister();
            if (previous != null)
                System.setProperty("java.protocol.handler.pkgs", previous);
            else
                System.clearProperty("java.protocol.handler.pkgs");
        }
    }

    private static String read(URL url) throws IOException {
        try (InputStream input = url.openStream()) {
            return new String(input.readAllBytes(), "UTF-8");
        }
    }

    static class BundleHandler extends AbstractURLStreamHandlerService {

        @Override
        public URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() {
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream("bundle".getBytes());
                }
            };
        }
    }
}
