package org.jboss.osgi.framework.spi;

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

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.msc.service.LifecycleEvent;
import org.jboss.msc.service.LifecycleListener;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartException;

/**
 * Track a number of services and call complete when they are done.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 19-Apr-2012
 */
public class ServiceTracker implements LifecycleListener {

    private final Set<ServiceName> addedNames = new HashSet<ServiceName>();
    private final Set<ServiceController<?>> trackedControllers = new HashSet<ServiceController<?>>();
    private final List<ServiceController<?>> started = new ArrayList<ServiceController<?>>();
    private final List<ServiceController<?>> failed = new ArrayList<ServiceController<?>>();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final AtomicBoolean allComplete = new AtomicBoolean();
    private final boolean completeOnFirstFailure;
    private final String trackerName;

    public ServiceTracker() {
        this("Anonymous");
    }

    public ServiceTracker(String trackerName) {
        this(trackerName, true);
    }

    public ServiceTracker(String trackerName, boolean completeOnFail) {
        this.completeOnFirstFailure = completeOnFail;
        this.trackerName = trackerName;
    }

    public String getTrackerName() {
        return trackerName;
    }
//
//    @Override
//    public void listenerAdded(ServiceController<?> controller) {
//        synchronized (trackedControllers) {
//            if (trackService(controller)) {
//                serviceListerAddedInternal(controller);
//                State state = controller.getState();
//                switch (state) {
//                    case UP:
//                        started.add(controller);
//                        serviceStarted(controller);
//                        break;
//                    case START_FAILED:
//                        failed.add(controller);
//                        serviceStartFailed(controller);
//                        break;
//                    default:
//                        trackedControllers.add(controller);
//                }
//            } else {
//                controller.removeListener(this);
//            }
//        }
//    }

    @Override
    public void handleEvent(final ServiceController<?> controller, final LifecycleEvent event) {
        synchronized (trackedControllers) {
            if (!allComplete.get()) {
                switch (event) {
                    case UP:
                        LOGGER.tracef("ServiceTracker %s transition to UP: %s", trackerName, controller.provides());
                        started.add(controller);
                        serviceStarted(controller);
                        serviceCompleteInternal(controller, false);
                        break;
                    case FAILED:
                        LOGGER.tracef("ServiceTracker %s transition to START_FAILED: %s", trackerName, controller.provides());
                        failed.add(controller);
                        serviceStartFailed(controller);
                        serviceCompleteInternal(controller, true);
                        break;
                    case REMOVED:
                    case DOWN:
                        if (addedNames.contains(controller.getName())) {
                            serviceCompleteInternal(controller, false);
                        } else if (trackService(controller)) {
                            serviceListerAddedInternal(controller);
                            trackedControllers.add(controller);
                        } else {
                            controller.removeListener(this);
                        }
                        break;
                }
            }
        }
    }

    public void synchronousListenerServiceStarted(ServiceController<?> controller) {
        synchronized (trackedControllers) {
            LOGGER.tracef("ServiceTracker %s synchronous service started: %s",trackerName, controller.provides());
            started.add(controller);
            serviceStarted(controller);
            serviceCompleteInternal(controller, false);
        }
    }

    public void synchronousListenerServiceFailed(ServiceController<?> controller, Throwable th) {
        synchronized (trackedControllers) {
            LOGGER.tracef("ServiceTracker %s synchronous service failed: %s", trackerName, controller.provides());
            failed.add(controller);
            serviceStartFailed(controller);
            serviceCompleteInternal(controller, true);
        }
    }

    public void checkAndComplete() {
        synchronized (trackedControllers) {
            if (trackedControllers.size() == 0 && allServicesAdded(Collections.unmodifiableSet(addedNames))) {
                completeInternal();
            }
        }
    }

    public boolean isComplete() {
        return allComplete.get();
    }

    public void untrackService(ServiceController<?> controller) {
        synchronized (trackedControllers) {
            LOGGER.tracef("ServiceTracker %s untrack service: %s", trackerName, controller.provides());
            trackedControllers.remove(controller);
            controller.removeListener(this);
            checkAndComplete();
        }
    }

    public List<ServiceController<?>> getStartedServices() {
        synchronized (trackedControllers) {
            return Collections.unmodifiableList(started);
        }
    }

    public List<ServiceController<?>> getFailedServices() {
        synchronized (trackedControllers) {
            return Collections.unmodifiableList(failed);
        }
    }

    public Throwable getFirstFailure() {
        synchronized (trackedControllers) {
            Throwable failure = null;
            for (ServiceController<?> controller : failed) {
                StartException startex = controller.getStartException();
                if (startex != null && startex.getCause() != null) {
                    failure = startex.getCause();
                    break;
                }
            }
            return failure;
        }
    }

    public boolean hasFailedServices() {
        synchronized (trackedControllers) {
            return !failed.isEmpty();
        }
    }

    public boolean awaitCompletion() throws InterruptedException {
        completionLatch.await();
        return !hasFailedServices();
    }

    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        if (!completionLatch.await(timeout, unit))
            throw new TimeoutException();
        return !hasFailedServices();
    }

    protected boolean trackService(ServiceController<?> controller) {
        return true;
    }

    protected boolean allServicesAdded(Set<ServiceName> trackedServices) {
        return true;
    }

    protected void serviceListenerAdded(ServiceController<?> controller) {
    }

    protected void serviceStarted(ServiceController<?> controller) {
    }

    protected void serviceStartFailed(ServiceController<?> controller) {
    }

    protected void complete() {
    }

    private void serviceListerAddedInternal(ServiceController<?> controller) {
        LOGGER.tracef("ServiceTracker %s controller added: %s", trackerName, controller.getName());
        addedNames.add(controller.getName());
        serviceListenerAdded(controller);
    }

    private void serviceCompleteInternal(ServiceController<?> controller, boolean failure) {
        trackedControllers.remove(controller);
        controller.removeListener(this);

        // On failure remove all tracked services and complete the tracker
        if (failure && completeOnFirstFailure) {
            Iterator<ServiceController<?>> iterator = trackedControllers.iterator();
            while (iterator.hasNext()) {
                ServiceController<?> aux = iterator.next();
                aux.removeListener(this);
                iterator.remove();
            }
            completeInternal();
        } else {
            checkAndComplete();
        }
    }

    private void completeInternal() {
        if (allComplete.compareAndSet(false, true)) {
            LOGGER.debugf("ServiceTracker %s complete", trackerName);
            completionLatch.countDown();
            complete();
        }
    }
}