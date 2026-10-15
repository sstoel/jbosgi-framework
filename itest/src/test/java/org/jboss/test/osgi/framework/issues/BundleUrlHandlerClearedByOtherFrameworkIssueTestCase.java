package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Stopping one framework instance breaks the bundle: URL protocol for every other framework instance in the JVM:
 * afterwards {@code new URL(entryURL.toExternalForm())} fails with "No stream handlers for protocol: bundle" for
 * the bundles of a framework that is still running.
 *
 * <h3>Severity</h3>
 * minor. Needs more than one framework instance in one JVM (embedding, test harnesses such as the TCK, a
 * framework that is created again while an old instance is stopped). In a WildFly server there is one framework.
 * Code that re-parses bundle URLs (resource URLs passed around as strings, URL(context, spec) to find related
 * resources, ServiceLoader-like lookups) then fails in the surviving framework.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.URLHandlerSupportImpl (static stream handler delegate)
 * and OSGiStreamHandlerFactoryDelegate.
 *
 * <h3>Found by</h3>
 * TCK workstream: OSGi Core R8 TCK org.osgi.test.cases.framework.launch 8.0.0,
 * {@code MultiReleaseJarTestCase.testMultiReleaseGetResource} fails on JBoss OSGi with
 * {@code java.lang.reflect.InvocationTargetException ... Caused by: java.net.MalformedURLException: unknown protocol:
 * bundle at ...mrjars.test.TestClassBase.createURL(TestClassBase.java:29)} - the TCK launches many framework
 * instances one after the other in the same JVM and re-parses a resource URL with {@code new URL(String)}. It still
 * fails with {@code --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED} on
 * linux/amd64, where Equinox 3.18.600 passes the whole suite (58/58); Felix 7.0.5 passes it without extra options.
 * The root cause was isolated with an Equinox/Felix differential (see Reference behaviour).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.6 Resource Loading (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "Bundle entry URLs are normally created by the Framework, however, in certain cases bundles need to manipulate
 * the URL to find related resources. The Framework is therefore required to ensure that: Bundle entry URLs must be
 * hierarchical ... Usable as a context for constructing another URL. The java.net.URLStreamHandler class used for a
 * bundle entry URL must be available to the java.net.URL class to setup a URL that uses the protocol scheme defined
 * by the Framework."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/URLHandlerSupportImpl.java:75-76 - the stream and content
 * handler delegates are static (one per class loader, shared by all framework instances; only the first one can
 * be installed with URL.setURLStreamHandlerFactory, line 85); {@code stop()} (line 126-132) calls
 * {@code streamHandlerDelegate.clearHandlers()} (OSGiStreamHandlerFactoryDelegate.java:84-90 empties the handler
 * lists of all frameworks) and {@code streamHandlerDelegate.setDelegateFactory(null)}. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The URL handler state is global but owned by whichever framework started last: stopping any framework removes
 * the 'bundle' handler registrations of all frameworks and unsets the delegate factory. The handler that the JDK
 * cached for the 'bundle' protocol then finds no stream handler service.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=BundleUrlHandlerClearedByOtherFrameworkIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A bundle entry URL of the running framework must still be usable with new URL(String)
 *   after another framework instance was stopped: java.net.MalformedURLException: JBOSGI011226: No stream handlers
 *   for protocol: bundle
 * </pre>
 *
 * <h3>Expected</h3>
 * Stopping a framework only removes its own URL handlers; the bundle URLs of the frameworks that keep running stay
 * usable.
 *
 * <h3>Reference behaviour</h3>
 * Standalone program, JDK 25, framework A started, framework B started and stopped (same class loader), then
 * {@code new URL(aEntry.toExternalForm()).openStream()}: Equinox 3.18.600 and Felix 7.0.5 read the content of A's
 * entry; JBoss OSGi WF41-osgi-r8 and the WF41 build (jbosgi-framework-core 6.6.0.Final) throw
 * {@code MalformedURLException: JBOSGI011226: No stream handlers for protocol: bundle}.
 *
 * <h3>Suggested fix</h3>
 * Keep the handler registrations per framework instance: on stop remove only the service references of the
 * stopping framework (the tracker already removes them one by one), and only unset the delegate factory when the
 * last framework stops (or route by framework, e.g. a framework id in the URL host as Equinox and Felix do).
 *
 * <h3>Regression vs WF41</h3>
 * no (same behaviour in the WF41 build).
 */
public class BundleUrlHandlerClearedByOtherFrameworkIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testBundleUrlUsableAfterOtherFrameworkStopped() throws Exception {
        Bundle bundle = installBundle(getArchive("url.handler.a", "a.txt", "content-A"));
        try {
            URL entry = bundle.getEntry("a.txt");
            assertEquals("Precondition: the entry URL can be re-parsed", "content-A", readReparsed(entry));

            Map<String, String> config = new HashMap<String, String>();
            config.put(Constants.FRAMEWORK_STORAGE, new File("target/url-handler-other-framework").getAbsolutePath());
            config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
            Framework other = ServiceLoader.load(FrameworkFactory.class).iterator().next().newFramework(config);
            other.start();
            other.stop();
            other.waitForStop(5000);

            try {
                assertEquals("Content of the re-parsed entry URL", "content-A", readReparsed(entry));
            } catch (IOException ex) {
                fail("A bundle entry URL of the running framework must still be usable with new URL(String) after another framework instance was stopped: " + ex);
            }
        } finally {
            bundle.uninstall();
        }
    }

    private static String readReparsed(URL url) throws IOException {
        URL copy = new URL(url.toExternalForm());
        BufferedReader reader = new BufferedReader(new InputStreamReader(copy.openStream()));
        try {
            return reader.readLine();
        } finally {
            reader.close();
        }
    }

    private static JavaArchive getArchive(final String symbolicName, String entry, String content) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        archive.add(new StringAsset(content), entry);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                return builder.openStream();
            }
        });
        return archive;
    }
}
