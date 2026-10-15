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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
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
 * {@code URL.openConnection(Proxy)} on a scheme provided by a URL Stream Handler service throws an IOException instead
 * of UnsupportedOperationException when the service has no {@code openConnection(URL, Proxy)} method, and it does not
 * find the method when the service class inherits it from a superclass.
 *
 * <h3>Severity</h3>
 * minor. Callers that probe proxy support get a misleading I/O error; handler services built on a common base class
 * that implements {@code openConnection(URL, Proxy)} cannot be used with a proxy at all.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.URLHandlerSupportImpl.URLStreamHandlerProxy}.
 *
 * <h3>Found by</h3>
 * connect workstream (compliance matrix service.url#33/#34), exploratory test plus a differential run against Equinox
 * 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 52.3.5 Stream Handlers that use java.net.Proxy
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/service.url.html): "scheme providers can optionally implement
 * the openConnection(URL,Proxy) method as a public method. If the scheme provider implements this method, then the
 * framework must call it (using reflection). If this method is not implemented in the URL Stream Handler service an
 * Unsupported Operation Exception must be thrown."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/URLHandlerSupportImpl.java:376-386}: the method is looked
 * up with {@code handler.getClass().getDeclaredMethod("openConnection", URL.class, Proxy.class)} (only the concrete
 * class, not inherited public methods) and every failure, including {@code NoSuchMethodException}, is turned into
 * {@code MESSAGES.cannotOpenConnectionOnHandler} (an IOException). Unchanged on this branch (pre-existing).
 *
 * <h3>Root cause</h3>
 * {@code getDeclaredMethod} instead of {@code getMethod}, and no distinction between "not implemented" and "failed".
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=URLHandlerProxyOpenConnectionWithProxyIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testNotImplemented: URL.openConnection(Proxy) must throw UnsupportedOperationException when the handler service does
 *   not implement openConnection(URL, Proxy), but it threw java.io.IOException: JBOSGI011266: Cannot open connection
 *   on: ...NoProxyHandler@...
 * testInheritedImplementation: The inherited public openConnection(URL, Proxy) of the handler service must be called,
 *   but URL.openConnection(Proxy) threw java.io.IOException: JBOSGI011266: Cannot open connection on: ...
 * </pre>
 *
 * <h3>Expected</h3>
 * UnsupportedOperationException in the first case; the inherited method is called in the second.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300 throws {@code java.lang.UnsupportedOperationException} (caused by NoSuchMethodException) when the
 * method is missing.
 *
 * <h3>Suggested fix</h3>
 * Use {@code handler.getClass().getMethod("openConnection", URL.class, Proxy.class)}; throw
 * {@code new UnsupportedOperationException(...)} on NoSuchMethodException, rethrow the IOException of the invoked
 * method, and wrap only other failures.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class URLHandlerProxyOpenConnectionWithProxyIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testNotImplemented() throws Exception {
        ServiceRegistration<?> registration = register("issuesnoproxy", new NoProxyHandler());
        try {
            new URL("issuesnoproxy://host/").openConnection(Proxy.NO_PROXY);
            fail("URL.openConnection(Proxy) must throw UnsupportedOperationException when the handler service does not implement openConnection(URL, Proxy), but it returned");
        } catch (UnsupportedOperationException ex) {
            // expected
        } catch (IOException ex) {
            fail("URL.openConnection(Proxy) must throw UnsupportedOperationException when the handler service does not implement openConnection(URL, Proxy), but it threw " + ex);
        } finally {
            registration.unregister();
        }
    }

    @Test
    public void testInheritedImplementation() throws Exception {
        ServiceRegistration<?> registration = register("issuesinheritedproxy", new InheritedProxyHandler());
        try {
            URLConnection connection = new URL("issuesinheritedproxy://host/").openConnection(Proxy.NO_PROXY);
            assertEquals("proxy", read(connection));
        } catch (IOException ex) {
            fail("The inherited public openConnection(URL, Proxy) of the handler service must be called, but URL.openConnection(Proxy) threw " + ex);
        } finally {
            registration.unregister();
        }
    }

    private ServiceRegistration<?> register(String protocol, URLStreamHandlerService service) throws Exception {
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(URLConstants.URL_HANDLER_PROTOCOL, protocol);
        return getSystemContext().registerService(URLStreamHandlerService.class.getName(), service, props);
    }

    private static String read(URLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), "UTF-8");
        }
    }

    static URLConnection connection(URL url, final String content) {
        return new URLConnection(url) {
            @Override
            public void connect() {
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content.getBytes());
            }
        };
    }

    /** A handler without openConnection(URL, Proxy) */
    public static class NoProxyHandler extends AbstractURLStreamHandlerService {

        @Override
        public URLConnection openConnection(URL url) {
            return connection(url, "plain");
        }
    }

    /** A base class of handlers that supports proxies */
    public static class ProxyHandlerBase extends AbstractURLStreamHandlerService {

        @Override
        public URLConnection openConnection(URL url) {
            return connection(url, "plain");
        }

        public URLConnection openConnection(URL url, Proxy proxy) {
            return connection(url, "proxy");
        }
    }

    /** A handler that inherits openConnection(URL, Proxy) */
    public static class InheritedProxyHandler extends ProxyHandlerBase {
    }
}
