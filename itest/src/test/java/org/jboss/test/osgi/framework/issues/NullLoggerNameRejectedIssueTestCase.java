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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

/**
 * The framework's LoggerFactory throws IllegalArgumentException("Null logger name") for a null logger name. The Felix
 * SCR that the RTR WildFly integration ships (org.apache.felix.scr 2.1.30, also 2.2.6) asks for a logger named after
 * the component, which is null for an invalid component without a name; the exception then aborts the processing of
 * the whole Service-Component descriptor, so the valid components declared in the same XML file are never registered.
 *
 * <h3>Severity</h3>
 * minor (interoperability). It is not a violation of the LoggerFactory contract (the name parameter is documented as
 * "Must not be null"), but the shipped SCR relies on null being tolerated, as the Equinox Log Service does. Effect in
 * the RTR distribution: one invalid, unnamed component in a descriptor makes every component of that descriptor
 * disappear, silently except for one ERROR log entry. Regression of the distribution: before R8 the framework had no
 * LoggerFactory and SCR logged through the plain LogService.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.log.LogServiceImpl#getLoggerInternal
 *
 * <h3>Found by</h3>
 * TCK workstream: OSGi Declarative Services 1.4 TCK (org.osgi.test.cases.component built from github.com/osgi/osgi tag
 * r7-cmpn-ri-ct-final) with the shipped org.apache.felix.scr 2.1.30: 102/102 on Equinox 3.18.600, 88/102 on JBoss OSGi
 * (JDK 25). DeclarativeServicesControl.testRegistration fails with
 * {@code junit.framework.AssertionFailedError: ServiceProvider service should be registered}, and with it
 * testGetServiceDirect, testGetServiceLookup, testGetServiceEvent, testGetProperties, testDynamicBind, testCMUpdate,
 * testComponentEnableAndDisable, testManagedServiceFactory, testServiceComponentRuntimeConfiguration and
 * testServiceComponentRuntimeEnablement - 11 of the 14 JBoss-only failures (the test bundle tb1 declares two invalid components
 * and an unnamed {@code <component>} next to the valid ServiceProviderImpl). SCR logs:
 * {@code General problem with descriptor entry '/org/osgi/test/cases/component/tb1/impl/serviceprovider.xml'
 * java.lang.IllegalArgumentException: Null logger name at ...LogServiceImpl.getLoggerInternal(LogServiceImpl.java:79)
 * at org.apache.felix.scr.impl.logger.LogManager$Lock.getLogger(LogManager.java:126) ...
 * at org.apache.felix.scr.impl.BundleComponentActivator.validateAndRegister(BundleComponentActivator.java:461)}.
 * With a framework built with null tolerated (verification patch: null mapped to the root logger name) the same TCK run
 * gives 99/102: these 11 tests pass; the remaining 3 (DS14TestCase.testConfigurationPlugin = services/03,
 * testReferenceTargetProperty, testServiceComponentRuntimeDescription) have other causes.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, org.osgi.service.log.LoggerFactory#getLogger(Bundle, String, Class)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/service.log.html): "@param name The name to use for the logger
 * name. Must not be null. ... @throws IllegalArgumentException If the specified type is not a supported Logger type or
 * the specified Bundle is not a resolved bundle." The documented IllegalArgumentException cases do not include a null
 * name; null is a caller precondition. Compendium R8, 112.4 Component Description
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.component.html): "If SCR detects an error when
 * processing a component description, it must log an error message with the Log Service, if present, and ignore the
 * component description." - the shipped SCR does that (and keeps the other descriptions of the document) only when the
 * logger lookup does not throw.
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/log/LogServiceImpl.java:78-79:
 * {@code if (name == null) throw new IllegalArgumentException("Null logger name");}. New in commit 3680cfcc
 * ("Implement OSGi Core R8").
 *
 * <h3>Root cause</h3>
 * The framework rejects a null logger name; Felix SCR passes the (null) name of an invalid component and does not
 * expect an exception from the LoggerFactory while it validates the descriptor.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NullLoggerNameRejectedIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: LoggerFactory.getLogger(bundle, null, Logger.class) must not throw:
 *   java.lang.IllegalArgumentException: Null logger name
 * </pre>
 *
 * <h3>Expected</h3>
 * A null name is tolerated (for example treated like the default logger name of the bundle, as Equinox does:
 * "LogService.&lt;bsn&gt;"), so that a logging call cannot break the caller.
 *
 * <h3>Reference behaviour</h3>
 * Standalone program, JDK 25: Equinox 3.18.600 returns a Logger named "LogService.org.eclipse.osgi" for
 * getLogger(bundle, null, Logger.class) and getLogger((String) null); JBoss OSGi throws IllegalArgumentException: Null
 * logger name for both. The DS 1.4 TCK with SCR 2.1.30 passes on Equinox (102/102) and, for this group, on Felix 7.0.5
 * with Felix Log 1.3.0.
 *
 * <h3>Suggested fix</h3>
 * In LogServiceImpl.getLoggerInternal use a default name for null (e.g. "LogService." + bsn, or the root logger
 * name) instead of throwing.
 *
 * <h3>Regression vs WF41</h3>
 * yes for the distribution (the framework Log Service/LoggerFactory is new in the R8 commit 3680cfcc).
 */
public class NullLoggerNameRejectedIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testGetLoggerForBundleWithNullName() throws Exception {
        BundleContext context = getSystemContext();
        ServiceReference<LoggerFactory> sref = context.getServiceReference(LoggerFactory.class);
        LoggerFactory factory = context.getService(sref);
        try {
            Bundle bundle = context.getBundle();
            Logger logger = null;
            try {
                logger = factory.getLogger(bundle, null, Logger.class);
            } catch (RuntimeException ex) {
                fail("LoggerFactory.getLogger(bundle, null, Logger.class) must not throw: " + ex);
            }
            assertNotNull("A logger is returned for a null name", logger);
        } finally {
            context.ungetService(sref);
        }
    }
}
