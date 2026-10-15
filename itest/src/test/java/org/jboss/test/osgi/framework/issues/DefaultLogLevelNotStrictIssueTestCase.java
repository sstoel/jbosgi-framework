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

import java.util.Map;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;

/**
 * The launch property org.osgi.service.log.admin.loglevel is accepted case-insensitively and with surrounding
 * white space ("debug", " DEBUG"), although the specification says that a value that is not the name of a LogLevel
 * leaves the root default at WARN.
 *
 * <h3>Severity</h3>
 * cosmetic. The leniency is user friendly, but a configuration that is invalid for every other OSGi framework
 * (Equinox falls back to WARN) silently changes the log volume on this one, so a configuration tested here behaves
 * differently elsewhere.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.log.LoggerAdminImpl (constructor)
 *
 * <h3>Found by</h3>
 * services workstream, review of the Log Service launch property handling; Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 101.4.2 Effective Log Level (https://docs.osgi.org/specification/osgi.core/8.0.0/service.log.html):
 * "The default log level for the root LoggerContext can be set by the framework launch property
 * LOGGER_CONTEXT_DEFAULT_LOGLEVEL. The value of this property must be the name of the one of the LogLevel values.
 * If not specified, or the specified value is not the name of the one of the LogLevel values, the default log level
 * of the root LoggerContext is WARN." LoggerContext.LOGGER_CONTEXT_DEFAULT_LOGLEVEL: "The value of this property
 * must be the name of the one of the LogLevels."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/log/LoggerAdminImpl.java:45
 * ({@code LogLevel.valueOf(defaultLevel.trim().toUpperCase())}). New in commit 3680cfcc.
 *
 * <h3>Root cause</h3>
 * The value is trimmed and upper-cased before LogLevel.valueOf, so values that are not LogLevel names are accepted.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DefaultLogLevelNotStrictIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testLowerCaseValueIsNotALogLevelName: "debug" is not the name of a LogLevel, the root default must stay WARN
 *   expected:&lt;WARN&gt; but was:&lt;DEBUG&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * WARN for any value that is not exactly AUDIT, ERROR, WARN, INFO, DEBUG or TRACE.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0: "debug" and " DEBUG" give WARN, "DEBUG" gives DEBUG. JBoss OSGi gives DEBUG for all three.
 *
 * <h3>Suggested fix</h3>
 * Use {@code LogLevel.valueOf(defaultLevel)} without trim/upper-case (or document the leniency as an extension).
 *
 * <h3>Regression vs WF41</h3>
 * no (the Log Service is new in this branch).
 */
public class DefaultLogLevelNotStrictIssueTestCase extends AbstractFrameworkLaunchTest {

    @Test
    public void testLowerCaseValueIsNotALogLevelName() throws Exception {
        Map<String, String> props = getFrameworkInitProperties(true);
        props.put(LoggerContext.LOGGER_CONTEXT_DEFAULT_LOGLEVEL, "debug");
        Framework framework = newFramework(props);
        framework.start();
        try {
            BundleContext context = framework.getBundleContext();
            LoggerAdmin loggerAdmin = context.getService(context.getServiceReference(LoggerAdmin.class));
            assertEquals("\"debug\" is not the name of a LogLevel, the root default must stay WARN", LogLevel.WARN,
                    loggerAdmin.getLoggerContext(null).getEffectiveLogLevel("test.default.level"));
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }
    }
}
