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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

/**
 * A Logger obtained from the framework's LoggerFactory service stays enabled after the LoggerFactory service has
 * been unregistered; the specification requires it to enter a no-op state where no log level is effective.
 *
 * <h3>Severity</h3>
 * minor. Code that keeps a Logger beyond the lifetime of the Log Service (e.g. a static field of a bundle class
 * that survives a framework restart in the same JVM) still sees isXxxEnabled() true and keeps writing entries into
 * the log of the stopped framework instance.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.log.LoggerImpl / LogServices#stop
 *
 * <h3>Found by</h3>
 * services workstream, review of the Log Service 1.5 implementation against chapter 101; Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 101.3 Obtaining a Logger (https://docs.osgi.org/specification/osgi.core/8.0.0/service.log.html):
 * "As long as the LoggerFactory service, from which the Logger is obtained, is active, that is, the LoggerFactory
 * service has not been unregistered, then the Logger is valid and can be used to log. However, once the
 * LoggerFactory service has been unregistered, then Logger objects obtained from the LoggerFactory service must
 * enter a "no-op" state where no log level is effective and no logging occurs."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/log/LoggerImpl.java:50-52 ({@code isEnabled} only asks the
 * LoggerAdmin) and core/src/main/java/org/jboss/osgi/framework/internal/log/LogServices.java:140-167 ({@code stop()}
 * unregisters the services but leaves the loggers enabled). New in commit 3680cfcc ("Implement OSGi Core R8").
 *
 * <h3>Root cause</h3>
 * LogServices has no "stopped" state that the loggers consult; the effective level comes from the LoggerAdmin,
 * which still answers after the services are unregistered.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=LoggerNotNoOpAfterUnregisterIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testLoggerIsNoOpAfterLoggerFactoryUnregistered: After the LoggerFactory service is unregistered no log level may be
 *   effective, but isErrorEnabled() is true
 * </pre>
 *
 * <h3>Expected</h3>
 * All isXxxEnabled() methods return false and the log methods (audit included) do nothing once the
 * LoggerFactory service is unregistered.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 behaves like JBoss OSGi here (the logger stays enabled after the framework stopped), so this is a
 * deviation that the reference implementation shares; the specification text is explicit.
 *
 * <h3>Suggested fix</h3>
 * Set a volatile {@code stopped} flag in LogServices.stop() (before unregistering) and let
 * {@code LogServices.isEnabled} return false and {@code LogServices.log} return immediately when it is set.
 *
 * <h3>Regression vs WF41</h3>
 * no (the Log Service is new in this branch).
 */
public class LoggerNotNoOpAfterUnregisterIssueTestCase extends AbstractFrameworkLaunchTest {

    @Test
    public void testLoggerIsNoOpAfterLoggerFactoryUnregistered() throws Exception {
        Framework framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
        Logger logger;
        try {
            BundleContext context = framework.getBundleContext();
            ServiceReference<LoggerFactory> sref = context.getServiceReference(LoggerFactory.class);
            logger = context.getService(sref).getLogger("test.noop");
            assertTrue("While the LoggerFactory is registered the default level WARN implies ERROR", logger.isErrorEnabled());
        } finally {
            // Unregisters all services of the framework, the LoggerFactory service included
            framework.stop();
            framework.waitForStop(10000);
        }
        assertFalse("After the LoggerFactory service is unregistered no log level may be effective, but isErrorEnabled() is true", logger.isErrorEnabled());
        assertFalse("After the LoggerFactory service is unregistered no log level may be effective, but isWarnEnabled() is true", logger.isWarnEnabled());
    }
}
