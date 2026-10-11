package org.jboss.test.osgi.framework.service;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.log.FormatterLogger;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;

/**
 * Test the Log Service that the framework provides.
 *
 * @since 11-Oct-2026
 */
public class LogServiceTestCase extends OSGiFrameworkTest {

    private final BlockingQueue<LogEntry> logged = new LinkedBlockingQueue<LogEntry>();
    private final LogListener listener = new LogListener() {
        @Override
        public void logged(LogEntry entry) {
            if (entry.getLoggerName().startsWith("test.") || entry.getLoggerName().startsWith("LogService.") || entry.getLoggerName().startsWith("Events.Service."))
                logged.add(entry);
        }
    };
    private BundleContext context;
    private LogService logService;
    private LogReaderService logReader;
    private LoggerContext rootContext;
    private Map<String, LogLevel> rootLevels;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        context = getSystemContext();
        logService = context.getService(context.getServiceReference(LogService.class));
        logReader = context.getService(context.getServiceReference(LogReaderService.class));
        LoggerAdmin loggerAdmin = context.getService(context.getServiceReference(LoggerAdmin.class));
        rootContext = loggerAdmin.getLoggerContext(null);
        rootLevels = rootContext.getLogLevels();
        logReader.addLogListener(listener);
    }

    @After
    public void tearDown() throws Exception {
        logReader.removeLogListener(listener);
        rootContext.setLogLevels(rootLevels);
        super.tearDown();
    }

    private LogEntry nextEntry() throws InterruptedException {
        LogEntry entry = logged.poll(10, TimeUnit.SECONDS);
        assertNotNull("Log entry expected", entry);
        return entry;
    }

    @Test
    public void testServices() throws Exception {
        ServiceReference<LogService> logRef = context.getServiceReference(LogService.class);
        assertSame("Registered by the system bundle", context.getBundle(0), logRef.getBundle());
        assertEquals("Also the logger factory", logRef, context.getServiceReference(LoggerFactory.class));

        ServiceReference<LoggerAdmin> adminRef = context.getServiceReference(LoggerAdmin.class);
        assertEquals(logRef.getProperty(Constants.SERVICE_ID), adminRef.getProperty(LoggerAdmin.LOG_SERVICE_ID));
    }

    @Test
    public void testEffectiveLogLevel() throws Exception {
        Logger logger = logService.getLogger("test.levels.logger");
        assertEquals("Default level", LogLevel.WARN, rootContext.getEffectiveLogLevel(logger.getName()));
        assertTrue(logger.isWarnEnabled());
        assertFalse(logger.isInfoEnabled());

        // A level of an ancestor name applies
        rootContext.setLogLevels(Collections.singletonMap("test.levels", LogLevel.DEBUG));
        assertEquals(LogLevel.DEBUG, rootContext.getEffectiveLogLevel(logger.getName()));
        assertTrue(logger.isDebugEnabled());
        assertFalse(logger.isTraceEnabled());
        assertEquals(LogLevel.WARN, rootContext.getEffectiveLogLevel("test.other"));

        // A named context that is not empty replaces the root context for the bundle
        LoggerAdmin loggerAdmin = context.getService(context.getServiceReference(LoggerAdmin.class));
        LoggerContext bundleContext = loggerAdmin.getLoggerContext(context.getBundle().getSymbolicName());
        try {
            bundleContext.setLogLevels(Collections.singletonMap(Logger.ROOT_LOGGER_NAME, LogLevel.ERROR));
            assertFalse(logger.isWarnEnabled());
            assertTrue(logger.isErrorEnabled());
        } finally {
            bundleContext.clear();
        }
        assertTrue(bundleContext.isEmpty());
        assertTrue(logger.isDebugEnabled());
    }

    @Test
    public void testLoggers() throws Exception {
        rootContext.setLogLevels(Collections.singletonMap("test", LogLevel.INFO));
        Exception exception = new Exception("test");
        ServiceReference<?> reference = context.getServiceReference(LogService.class);

        Logger logger = logService.getLogger("test.logger");
        logger.debug("Not logged");
        logger.info("One {}, literal \\{}, two {}, none {}", "1", 2, reference, exception);
        LogEntry entry = nextEntry();
        assertEquals("One 1, literal {}, two 2, none {}", entry.getMessage());
        assertEquals("test.logger", entry.getLoggerName());
        assertEquals(LogLevel.INFO, entry.getLogLevel());
        assertEquals(LogLevel.INFO.ordinal(), entry.getLevel());
        assertSame(exception, entry.getException());
        assertSame(reference, entry.getServiceReference());
        assertSame(context.getBundle(), entry.getBundle());
        assertEquals(getClass().getName(), entry.getLocation().getClassName());
        assertEquals("testLoggers", entry.getLocation().getMethodName());
        assertTrue(entry.getThreadInfo().contains(Thread.currentThread().getName()));

        FormatterLogger formatter = logService.getLogger("test.formatter", FormatterLogger.class);
        formatter.audit("Value %s and %d", "one", 2);
        LogEntry formatted = nextEntry();
        assertEquals("Value one and 2", formatted.getMessage());
        assertEquals(LogLevel.AUDIT, formatted.getLogLevel());
        assertTrue(formatted.getSequence() > entry.getSequence());
        assertNull(formatted.getException());

        // The most recent entry is the first in the log
        assertSame(formatted, logReader.getLog().nextElement());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testLegacyLog() throws Exception {
        logService.log(LogService.LOG_DEBUG, "Not logged");
        logService.log(LogService.LOG_ERROR, "Legacy message");
        LogEntry entry = nextEntry();
        assertEquals("Legacy message", entry.getMessage());
        assertEquals("LogService." + context.getBundle().getSymbolicName(), entry.getLoggerName());
        assertEquals(LogService.LOG_ERROR, entry.getLevel());
        assertEquals(LogLevel.ERROR, entry.getLogLevel());
    }

    @Test
    public void testServiceEventLogged() throws Exception {
        rootContext.setLogLevels(Collections.singletonMap("Events.Service", LogLevel.INFO));
        ServiceRegistration<String> sreg = context.registerService(String.class, "logged", null);
        try {
            LogEntry entry = nextEntry();
            assertEquals("ServiceEvent REGISTERED", entry.getMessage());
            assertEquals("Events.Service." + context.getBundle().getSymbolicName(), entry.getLoggerName());
            assertEquals(sreg.getReference(), entry.getServiceReference());
        } finally {
            sreg.unregister();
        }
        assertEquals("ServiceEvent UNREGISTERING", nextEntry().getMessage());
    }
}
