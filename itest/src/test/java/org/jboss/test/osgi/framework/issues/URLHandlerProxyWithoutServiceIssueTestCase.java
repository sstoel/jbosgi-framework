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

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
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
 * When the URL Stream Handler service behind a URL scheme is unregistered, the framework's handler proxy throws an
 * IllegalStateException from {@code URL.openConnection()} instead of the required MalformedURLException.
 *
 * <h3>Severity</h3>
 * minor. Code that opens URLs of a scheme provided by a bundle handles the documented IOException when the providing
 * bundle is stopped, but gets an unchecked IllegalStateException instead (e.g. a resource loader or a URL class loader
 * then fails with an unexpected runtime exception).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.URLHandlerSupportImpl.URLStreamHandlerProxy}.
 *
 * <h3>Found by</h3>
 * connect workstream (compliance matrix service.url#21), exploratory test plus a differential run against Equinox
 * 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 52.3.1 Constructing a Proxy and Handler
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/service.url.html): "In this case, the proxy must handle
 * subsequent requests until another appropriate service is registered. When this happens, the proxy class must handle
 * the error. In the case of a URL Stream Handler proxy, it must throw a java.net.MalformedURLException exception if
 * the signature of a method allows throwing this exception. Otherwise, a java.lang.IllegalStateException exception is
 * thrown."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/URLHandlerSupportImpl.java:418-420}
 * ({@code getHandlerService} throws {@code MESSAGES.illegalStateNoStreamHandlersForProtocol(protocol)} for every
 * method), called from {@code openConnection(URL)} (line 367) and {@code openConnection(URL, Proxy)}, whose signatures
 * allow IOException/MalformedURLException. Unchanged on this branch (pre-existing).
 *
 * <h3>Root cause</h3>
 * The proxy uses one unchecked exception for all methods instead of MalformedURLException where the method signature
 * declares IOException.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=URLHandlerProxyWithoutServiceIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: URL.openConnection() must throw MalformedURLException when the handler service of the
 *   scheme is gone, but it threw java.lang.IllegalStateException: JBOSGI011226: No stream handlers for protocol:
 *   issuesproxy
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code java.net.MalformedURLException} (an IOException) from {@code openConnection}; IllegalStateException only from
 * methods that cannot throw it (equals, hashCode, toExternalForm, ...).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: {@code openConnection()} on the same kind of URL throws {@code java.net.MalformedURLException}.
 *
 * <h3>Suggested fix</h3>
 * In {@code URLStreamHandlerProxy.openConnection(URL)} and {@code openConnection(URL, Proxy)} check for an available
 * service first and throw {@code new MalformedURLException(...)} when there is none.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class URLHandlerProxyWithoutServiceIssueTestCase extends OSGiFrameworkTest {

    private static final String PROTOCOL = "issuesproxy";

    @Test
    public void testOpenConnectionWithoutService() throws Exception {
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(URLConstants.URL_HANDLER_PROTOCOL, PROTOCOL);
        ServiceRegistration<?> registration = getSystemContext().registerService(URLStreamHandlerService.class.getName(), new Handler(), props);
        URL url = new URL(PROTOCOL + "://host/path");
        url.openConnection().getInputStream().close();

        // The bundle that provided the scheme goes away, the JVM keeps the proxy for the scheme
        registration.unregister();
        try {
            url.openConnection();
            fail("URL.openConnection() must throw MalformedURLException when the handler service of the scheme is gone, but it returned");
        } catch (MalformedURLException ex) {
            // expected
        } catch (RuntimeException ex) {
            fail("URL.openConnection() must throw MalformedURLException when the handler service of the scheme is gone, but it threw " + ex);
        }
    }

    static class Handler extends AbstractURLStreamHandlerService {

        @Override
        public URLConnection openConnection(URL url) throws IOException {
            return new URLConnection(url) {
                @Override
                public void connect() {
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(new byte[0]);
                }
            };
        }
    }
}
