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
package org.jboss.osgi.framework.internal;

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.StartLevelManager;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.spi.AttachmentKey;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.launch.Framework;

/**
 * An implementation of the {@link StartLevelManager} service.
 *
 * @author <a href="david@redhat.com">David Bosschaert</a>
 * @author Thomas.Diesler@jboss.com
 */
public final class StartLevelManagerImpl implements StartLevelManager {

    private static AttachmentKey<BundleStartLevelState> BUNDLE_STARTLEVEL_KEY = AttachmentKey.create(BundleStartLevelState.class);

    private final BundleManagerPlugin bundleManager;
    private final FrameworkEvents events;
    private final ExecutorService executorService;
    private final AtomicBoolean immediateExecution;
    private AtomicInteger initialBundleStartLevel = new AtomicInteger(1);
    private volatile boolean initialStartLevelRestored;
    private final Object initialStartLevelLock = new Object();
    private AtomicInteger startLevel = new AtomicInteger(0);
    private AtomicBoolean changingStartLevel = new AtomicBoolean();

    public StartLevelManagerImpl(BundleManager bundleManager, FrameworkEvents frameworkEvents, ExecutorService executorService, AtomicBoolean immediateExecution) {
        this.bundleManager = (BundleManagerPlugin) bundleManager;
        this.events = frameworkEvents;
        this.executorService = executorService;
        this.immediateExecution = immediateExecution;
    }

    @Override
    public void enableImmediateExecution(boolean enable) {
        this.immediateExecution.set(enable);
    }

    @Override
    public int getFrameworkStartLevel() {
        return startLevel.get();
    }

    @Override
    public void setFrameworkStartLevel(final int level, FrameworkListener... listeners) {
        setFrameworkStartLevelInternal(level, immediateExecution.get(), listeners);
    }

    @Override
    public boolean isFrameworkStartLevelChanging() {
        return changingStartLevel.get();
    }

    @Override
    public void shutdownFramework(FrameworkListener... listeners) {
        setFrameworkStartLevelInternal(0, true, listeners);
    }

    /**
     * The request does not wait for a start level change that is in progress. The requests are processed in their order,
     * each one compares its level with the active start level when it is processed.
     */
    private void setFrameworkStartLevelInternal(final int level, final boolean synchronous, final FrameworkListener... listeners) {

        final XBundle sysbundle = bundleManager.getSystemBundle();
        Runnable runner = new Runnable() {
            @Override
            public void run() {
                synchronized (StartLevelManagerImpl.this) {
                    int activeLevel = getFrameworkStartLevel();
                    if (level > activeLevel) {
                        LOGGER.infoIncreasingStartLevel(activeLevel, level);
                        increaseFrameworkStartLevel(level);
                    } else if (level < activeLevel) {
                        LOGGER.infoDecreasingStartLevel(activeLevel, level);
                        decreaseFrameworkStartLevel(level);
                    }
                }
                events.fireFrameworkEvent(sysbundle, FrameworkEvent.STARTLEVEL_CHANGED, null, listeners);
            }
        };
        executeTask(runner, synchronous);
    }

    /**
     * Increases the Start Level of the Framework in the current thread.
     *
     * @param level the target Start Level to which the Framework should move.
     */
    @Override
    public synchronized void increaseFrameworkStartLevel(int level) {

        try {
            changingStartLevel.set(true);

            // Sort the bundles after their bundle id
            List<XBundle> bundles = new ArrayList<XBundle>(bundleManager.getBundles());
            Comparator<XBundle> comparator = new Comparator<XBundle>() {
                @Override
                public int compare(XBundle b1, XBundle b2) {
                    return (int) (b1.getBundleId() - b2.getBundleId());
                }
            };
            Collections.sort(bundles, comparator);

            while (startLevel.get() < level) {
                // The stop of the framework is final: a pending or concurrent increase is discarded
                if (bundleManager.isFrameworkStopping()) {
                    LOGGER.debugf("Framework stopping, start level increase to %d discarded", level);
                    break;
                }
                startLevel.incrementAndGet();
                LOGGER.infoStartingBundlesForStartLevel(startLevel.get());
                for (XBundle bundle : bundles) {
                    // A stop of the framework cuts the increase short, the bundles are not started any more (shutdown/17)
                    if (bundleManager.isFrameworkStopping())
                        break;

                    // A bundle of the snapshot may have been uninstalled meanwhile, e.g. by the activator of another one
                    if (bundle.getBundleId() == 0 || bundle.isFragment() || bundle.getState() == Bundle.UNINSTALLED)
                        continue;

                    BundleStartLevelState state = getBundleStartLevelState(bundle);
                    if (state.getLevel() == startLevel.get() && state.isStarted()) {
                        try {
                            int opts = Bundle.START_TRANSIENT;
                            if (isBundleActivationPolicyUsed(bundle)) {
                                opts |= Bundle.START_ACTIVATION_POLICY;
                            }
                            bundle.start(opts);
                        } catch (Throwable e) {
                            events.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, e);
                        }
                    }
                }
            }
        } finally {
            changingStartLevel.set(false);
        }
    }

    /**
     * Decreases the Start Level of the Framework in the current thread.
     *
     * @param level the target Start Level to which the Framework should move.
     */
    @Override
    public synchronized void decreaseFrameworkStartLevel(int level) {
        try {
            changingStartLevel.set(true);

            while (startLevel.get() > level) {
                LOGGER.infoStoppingBundlesForStartLevel(level);

                // Sort the bundles after their bundle id
                List<XBundle> bundles = new ArrayList<XBundle>(bundleManager.getBundles());
                Comparator<XBundle> comparator = new Comparator<XBundle>() {
                    @Override
                    public int compare(XBundle b1, XBundle b2) {
                        return (int) (b1.getBundleId() - b2.getBundleId());
                    }
                };
                Collections.sort(bundles, comparator);
                Collections.reverse(bundles);

                for (XBundle bundle : bundles) {
                    // A bundle of the snapshot may have been uninstalled meanwhile, e.g. by the activator of another one
                    if (bundle.getBundleId() == 0 || bundle.isFragment() || bundle.getState() == Bundle.UNINSTALLED)
                        continue;

                    BundleStartLevelState state = getBundleStartLevelState(bundle);
                    if (state.getLevel() == startLevel.get()) {
                        try {
                            bundle.stop(Bundle.STOP_TRANSIENT);
                        } catch (Throwable e) {
                            events.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, e);
                        }
                    }
                }
                startLevel.decrementAndGet();
            }
        } finally {
            changingStartLevel.set(false);
        }
    }

    @Override
    public int getBundleStartLevel(XBundle bundle) {
        if (bundle == null)
            throw MESSAGES.illegalArgumentNull("bundle");
        return getBundleStartLevelState(bundle).getLevel();
    }

    @Override
    public void setBundleStartLevel(XBundle bundle, int level) {
        if (bundle == null)
            throw MESSAGES.illegalArgumentNull("bundle");
        if (bundle.getBundleId() == 0)
            throw MESSAGES.illegalArgumentStartLevelOnSystemBundles();
        if (level <= 0)
            throw MESSAGES.illegalArgumentInvalidStartLevel(level);

        final XBundle hostBundle = bundle;
        getBundleStartLevelState(bundle).setLevel(level);

        if (level <= getFrameworkStartLevel()) {
            // If the bundle is active or starting, we don't need to start it again
            if ((bundle.getState() & (Bundle.ACTIVE | Bundle.STARTING)) > 0)
                return;

            if (isBundlePersistentlyStarted(bundle)) {
                LOGGER.infoStartingBundleDueToStartLevel(hostBundle);
                Runnable runner = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int opts = Bundle.START_TRANSIENT;
                            if (isBundleActivationPolicyUsed(hostBundle))
                                opts |= Bundle.START_ACTIVATION_POLICY;

                            hostBundle.start(opts);
                        } catch (BundleException e) {
                            events.fireFrameworkEvent(hostBundle, FrameworkEvent.ERROR, e);
                        }
                    }
                };

                executeTask(runner, immediateExecution.get());
            }
        } else {
            // If the bundle is not active we don't need to stop it
            if ((bundle.getState() & (Bundle.ACTIVE | Bundle.STARTING)) == 0)
                return;

            LOGGER.infoStoppingBundleDueToStartLevel(hostBundle);
            Runnable runner = new Runnable() {
                @Override
                public void run() {
                    try {
                        hostBundle.stop(Bundle.STOP_TRANSIENT);
                    } catch (BundleException e) {
                        events.fireFrameworkEvent(hostBundle, FrameworkEvent.ERROR, e);
                    }
                }
            };

            executeTask(runner, immediateExecution.get());
        }
    }

    @Override
    public int getInitialBundleStartLevel() {
        restoreInitialBundleStartLevel();
        return initialBundleStartLevel.get();
    }

    @Override
    public void setInitialBundleStartLevel(int startlevel) {
        StorageManagerImpl storageManager = getStorageManagerImpl();
        synchronized (initialStartLevelLock) {
            initialStartLevelRestored = true;
            initialBundleStartLevel.set(startlevel);
            if (storageManager != null)
                storageManager.storeInitialBundleStartLevel(startlevel);
        }
    }

    // The initial bundle start level that was set before the framework was launched again
    private void restoreInitialBundleStartLevel() {
        if (initialStartLevelRestored)
            return;
        StorageManagerImpl storageManager = getStorageManagerImpl();
        int startlevel = storageManager != null ? storageManager.getStoredInitialBundleStartLevel() : -1;
        if (startlevel < 0)
            return;
        synchronized (initialStartLevelLock) {
            if (initialStartLevelRestored == false) {
                if (startlevel > 0)
                    initialBundleStartLevel.set(startlevel);
                initialStartLevelRestored = true;
            }
        }
    }

    private StorageManagerImpl getStorageManagerImpl() {
        try {
            StorageManager storageManager = bundleManager.getFrameworkState().getStorageManager();
            return storageManager instanceof StorageManagerImpl ? (StorageManagerImpl) storageManager : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public boolean isBundlePersistentlyStarted(XBundle bundle) {
        if (bundle == null)
            throw MESSAGES.illegalArgumentNull("bundle");
        return getBundleStartLevelState(bundle).isStarted();
    }

    @Override
    public void setBundlePersistentlyStarted(XBundle bundle, boolean started) {
        if (bundle == null)
            throw MESSAGES.illegalArgumentNull("bundle");
        getBundleStartLevelState(bundle).setStarted(started);
    }

    @Override
    public boolean isBundleActivationPolicyUsed(XBundle bundle) {
        boolean result = false;
        if (bundle instanceof AbstractBundleState) {
            AbstractBundleState<?> bundleState = AbstractBundleState.assertBundleState(bundle);
            StorageState storageState = bundleState.getStorageState();
            result = storageState.isBundleActivationPolicyUsed();
        }
        return result;
    }

    private BundleStartLevelState getBundleStartLevelState(XBundle bundle) {
        if (bundle instanceof Framework)
            return new BundleStartLevelState(bundle);

        BundleStartLevelState state = bundle.getAttachment(BUNDLE_STARTLEVEL_KEY);
        if (state == null) {
            state = new BundleStartLevelState(bundle);
            bundle.putAttachment(BUNDLE_STARTLEVEL_KEY, state);
        }
        return state;
    }

    private void executeTask(Runnable runner, boolean synchronous) {
        if (!executorService.isShutdown()) {
            if (synchronous) {
                runner.run();
            } else {
                executorService.execute(runner);
            }
        }
    }

    class BundleStartLevelState {
        final XBundle bundle;
        boolean started;
        int level;

        public BundleStartLevelState(XBundle bundle) {
            this.bundle = bundle;
            if (bundle instanceof UserBundleState) {
                UserBundleState userBundle = (UserBundleState)bundle;
                StorageState storageState = userBundle.getStorageState();
                level = storageState != null ? storageState.getStartLevel() : getInitialBundleStartLevel();
                // The autostart setting of a bundle that is restored from the storage
                started = storageState != null && storageState.isPersistentlyStarted();
            }
        }

        int getLevel() {
            return level;
        }

        void setLevel(int level) {
            this.level = level;
            if (bundle instanceof UserBundleState) {
                UserBundleState userBundle = (UserBundleState)bundle;
                StorageState storageState = userBundle.getStorageState();
                storageState.setStartLevel(level);
            }
        }

        boolean isStarted() {
            return started;
        }

        void setStarted(boolean started) {
            this.started = started;
            if (bundle instanceof UserBundleState) {
                UserBundleState userBundle = (UserBundleState)bundle;
                StorageState storageState = userBundle.getStorageState();
                storageState.setPersistentlyStarted(started);
            }
        }
    }
}
