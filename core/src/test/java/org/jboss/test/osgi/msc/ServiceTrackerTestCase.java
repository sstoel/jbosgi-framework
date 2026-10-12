package org.jboss.test.osgi.msc;
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


import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.msc.service.LifecycleEvent;
import org.jboss.msc.service.LifecycleListener;
import org.junit.Assert;

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.osgi.framework.spi.FutureServiceValue;
import org.jboss.osgi.framework.spi.ServiceTracker;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test the {@link ServiceTracker}
 *
 * @author Thomas.Diesler@jboss.com
 * @since 19-Apr-2012
 */
public class ServiceTrackerTestCase extends AbstractServiceTestCase {

    @Test
    public void testListenerCalledOnInstall() throws Exception {

        // The tracker relies on the event that a listener gets when the service is installed
        final CountDownLatch installed = new CountDownLatch(1);
        final AtomicReference<LifecycleEvent> firstEvent = new AtomicReference<LifecycleEvent>();
        LifecycleListener listener = (controller, event) -> {
            firstEvent.compareAndSet(null, event);
            installed.countDown();
        };
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.addListener(listener);
        builder.install();

        Assert.assertTrue("Listener called", installed.await(500, TimeUnit.MILLISECONDS));
        Assert.assertEquals(LifecycleEvent.DOWN, firstEvent.get());
    }

    @Test
    public void testSimpleTracker() throws Exception {

        ServiceTracker tracker = new ServiceTracker();
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.addListener(tracker);
        builder.install();

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertTrue("All complete", outcome);
    }

    @Test
    public void testServiceSetTracker() throws Exception {

        final Set<ServiceName> expected = new HashSet<ServiceName>();
        for (int i = 0; i < 10; i++) {
            expected.add(ServiceName.of("service" + i));
        }

        final Set<ServiceName> names = new HashSet<ServiceName>();
        ServiceTracker tracker = new ServiceTracker() {

            @Override
            protected boolean allServicesAdded(Set<ServiceName> trackedServices) {
                return expected.equals(trackedServices);
            }

            @Override
            public void serviceStarted(ServiceController<? extends Object> controller) {
                names.add(controller.getName());
            }
        };

        for (ServiceName serviceName : expected) {
            Thread.sleep(10);
            ServiceBuilder<String> builder = serviceTarget.addService(serviceName, new TestService());
            builder.addListener(tracker);
            builder.install();
        }

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertTrue("All complete", outcome);
        Assert.assertEquals("All services started", 10, names.size());

    }

    @Test
    public void testDependencyFailed() throws Exception {

        ServiceTracker tracker = new ServiceTracker();
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.requires(ServiceName.of("serviceB"));
        builder.addListener(tracker);
        builder.install();

        builder = serviceTarget.addService(ServiceName.of("serviceB"), new ServiceB() {
            @Override
            public void start(StartContext context) throws StartException {
                throw new StartException("serviceB failed");
            }
        });
        builder.addListener(tracker);
        builder.install();

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertFalse("All complete", outcome);
    }


    @Test
    @Ignore
    public void testUntrackedDependencyFailed() throws Exception {

        ServiceTracker tracker = new ServiceTracker();
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.requires(ServiceName.of("serviceB"));
        builder.addListener(tracker);
        builder.install();

        builder = serviceTarget.addService(ServiceName.of("serviceB"), new ServiceB() {
            @Override
            public void start(StartContext context) throws StartException {
                throw new StartException("serviceB failed");
            }
        });
        // don't add the tracker to this service
        builder.install();

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertTrue("All complete", outcome);
    }

    @Test
    public void testTransitionToNever() throws Exception {

        ServiceTracker tracker = new ServiceTracker();
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.addListener(tracker);
        ServiceController<String> controller = builder.install();

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertTrue("All complete", outcome);

        // A service that goes down after it was up does not change the outcome
        controller.setMode(Mode.NEVER);
        new FutureServiceValue<String>(controller, State.DOWN).get(500, TimeUnit.MILLISECONDS);

        Assert.assertTrue("Complete", tracker.isComplete());
        Assert.assertEquals(1, tracker.getStartedServices().size());
        Assert.assertFalse("No failed services", tracker.hasFailedServices());
    }

    @Test
    public void testRemoveBeforeStart() throws Exception {

        // The service cannot start, its dependency is missing
        ServiceTracker tracker = new ServiceTracker();
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.requires(ServiceName.of("serviceB"));
        builder.addListener(tracker);
        ServiceController<String> controller = builder.install();

        try {
            tracker.awaitCompletion(200, TimeUnit.MILLISECONDS);
            Assert.fail("TimeoutException expected");
        } catch (TimeoutException ex) {
            // expected
        }
        Assert.assertFalse("Not complete", tracker.isComplete());

        controller.setMode(Mode.REMOVE);

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertTrue("All complete", outcome);
        Assert.assertTrue("No started services", tracker.getStartedServices().isEmpty());
    }

    @Test
    public void testUntrackBeforeStart() throws Exception {

        // The tracker is not notified of a start that is cancelled, the service is taken out explicitly
        ServiceTracker tracker = new ServiceTracker();
        ServiceBuilder<String> builder = serviceTarget.addService(ServiceName.of("serviceA"), new ServiceA());
        builder.requires(ServiceName.of("serviceB"));
        builder.addListener(tracker);
        ServiceController<String> controller = builder.install();

        controller.setMode(Mode.NEVER);
        tracker.untrackService(controller);

        boolean outcome = tracker.awaitCompletion(500, TimeUnit.MILLISECONDS);
        Assert.assertTrue("All complete", outcome);
    }
}
