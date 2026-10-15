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

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * The system bundle's getLocation() returns "org.jboss.osgi.framework" instead of the "System Bundle" that the
 * specification and Constants.SYSTEM_BUNDLE_LOCATION define.
 *
 * <h3>Severity</h3>
 * minor. Code that recognises the system bundle by its location (management tools, bundle listings, the WildFly
 * management model: /subsystem=osgi/bundle=org.jboss.osgi.framework is the system bundle's resource name because the
 * resources are keyed by location) sees an implementation-specific string. BundleContext.getBundle("System Bundle")
 * does return the system bundle, so the lookup alias works; only getLocation is wrong.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.SystemBundleState#getLocation and FrameworkProxy#getLocation
 * (Constants.JBOSGI_FRAMEWORK_LOCATION).
 *
 * <h3>Found by</h3>
 * services workstream round 4 (WildFly osgi management model: the system bundle appears as
 * bundle=org.jboss.osgi.framework with location "org.jboss.osgi.framework"), Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.6 The System Bundle (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * "The system bundle getLocation method returns the string: "System Bundle", as defined in the Constants interface."
 * Constants.SYSTEM_BUNDLE_LOCATION = "System Bundle": "Location identifier of the OSGi system bundle".
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/SystemBundleState.java:83-86 and FrameworkProxy.java
 * ({@code return Constants.JBOSGI_FRAMEWORK_LOCATION;}, core/src/main/java/org/jboss/osgi/framework/Constants.java:40
 * {@code JBOSGI_FRAMEWORK_LOCATION = "org.jboss.osgi.framework"}). Same on WF41.
 *
 * <h3>Root cause</h3>
 * The implementation-specific location constant is returned instead of org.osgi.framework.Constants.SYSTEM_BUNDLE_LOCATION.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemBundleLocationIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testSystemBundleLocation: The system bundle location must be "System Bundle" expected:&lt;[System Bundle]&gt; but was:&lt;[org.jboss.osgi.framework]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * "System Bundle" for getBundle(0).getLocation() and for the Framework object.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0: getLocation() = "System Bundle".
 *
 * <h3>Suggested fix</h3>
 * Return org.osgi.framework.Constants.SYSTEM_BUNDLE_LOCATION from both getLocation methods (keeping the old string as
 * an accepted alias where the WildFly integration looks the system bundle up by location).
 *
 * Verified: returning Constants.SYSTEM_BUNDLE_LOCATION from both methods makes this test pass; the baseline test
 * itest SystemBundleTestCase.testLocation asserts the implementation string (JBOSGI_FRAMEWORK_LOCATION) and has to be
 * updated with the fix. The WildFly management model keys bundle resources by location, so the system bundle's resource
 * name would change from bundle=org.jboss.osgi.framework to bundle="System Bundle" (check the subsystem for lookups).
 *
 * <h3>Regression vs WF41</h3>
 * no.
 */
public class SystemBundleLocationIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testSystemBundleLocation() throws Exception {
        Bundle sysbundle = getSystemContext().getBundle(0);
        assertEquals("The system bundle location must be \"System Bundle\"", Constants.SYSTEM_BUNDLE_LOCATION, sysbundle.getLocation());
        assertEquals("The Framework's location must be \"System Bundle\"", Constants.SYSTEM_BUNDLE_LOCATION, getFramework().getLocation());
    }
}
