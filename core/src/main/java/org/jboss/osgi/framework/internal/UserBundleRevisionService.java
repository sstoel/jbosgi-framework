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

import java.io.IOException;

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Substate;
import org.jboss.msc.service.ServiceListener;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.BundleStorage;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.LockableEnvironment;
import org.jboss.osgi.framework.spi.NativeCode;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResource.State;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;

/**
 * Service associated with a user installed {@link XBundleRevision}
 *
 * @author thomas.diesler@jboss.com
 * @since 21-Mar-2013
 */
abstract class UserBundleRevisionService<R extends UserBundleRevision> extends AbstractBundleRevisionService<R> {

    private final Deployment deployment;
    private final ServiceName serviceName;
    private final BundleContext targetContext;
    private R bundleRevision;

    UserBundleRevisionService(FrameworkState frameworkState, BundleContext targetContext, Deployment deployment) {
        super(frameworkState);
        this.deployment = deployment;
        this.targetContext = targetContext;
        this.serviceName = getBundleManager().getServiceName(deployment);
    }

    ServiceController<R> install(ServiceTarget serviceTarget, ServiceListener<XBundleRevision> listener) {
        ServiceBuilder<R> builder = serviceTarget.addService(serviceName, this);
        addServiceDependencies(builder);
        if (listener != null) {
            builder.addListener(listener);
        }
        return builder.install();
    }

    protected void addServiceDependencies(ServiceBuilder<R> builder) {
        builder.addDependency(IntegrationServices.FRAMEWORK_CORE_SERVICES);
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        LOGGER.debugf("Creating %s %s", getClass().getSimpleName(), serviceName);
        StorageState storageState = null;
        try {
            Deployment dep = deployment;
            RevisionIdentifier revIdentifier = dep.getAttachment(RevisionIdentifier.class);
            storageState = createStorageState(dep, revIdentifier);
            OSGiMetaData metadata = dep.getAttachment(OSGiMetaData.class);
            ServiceName serviceName = startContext.getController().getName();
            bundleRevision = createBundleRevision(dep, metadata, storageState, serviceName, startContext.getChildTarget());
            bundleRevision.addAttachment(XResource.RESOURCE_IDENTIFIER_KEY, revIdentifier.getRevisionId());
            validateBundleRevision(bundleRevision, metadata);
            processNativeCode(bundleRevision, metadata, dep);
            XBundle bundle = (XBundle) dep.getAttachment(Bundle.class);
            if (bundle == null) {
                bundle = createBundleState(bundleRevision);
                UserBundleState userBundle = UserBundleState.assertBundleState(bundle);
                userBundle.addBundleRevision(bundleRevision);
                dep.addAttachment(Bundle.class, userBundle);
                userBundle.initLazyActivation();
                installBundleRevision(bundleRevision);
                userBundle.changeState(Bundle.INSTALLED, 0);
                LOGGER.infoBundleInstalled(bundle);
                FrameworkEvents events = getFrameworkState().getFrameworkEvents();
                events.fireBundleEvent(targetContext, bundle, BundleEvent.INSTALLED);
            } else {
                UserBundleState userBundle = UserBundleState.assertBundleState(bundle);
                userBundle.addBundleRevision(bundleRevision);
                installBundleRevision(bundleRevision);
            }
        } catch (BundleException ex) {
            if (storageState != null) {
                BundleStorage storagePlugin = getFrameworkState().getBundleStorage();
                storagePlugin.deleteStorageState(storageState);
            }
            throw new StartException(ex);
        }
    }

    @Override
    public void stop(StopContext context) {

        // The revision is already uninstalled. This would be
        // the case when we come through Bundle.uninstall()
        if (bundleRevision.getState() == State.UNINSTALLED)
            return;

        XBundle bundle = bundleRevision.getBundle();
        XBundleRevision currentRevision = bundle.getBundleRevision();
        if (currentRevision == bundleRevision && bundle.getState() != Bundle.UNINSTALLED) {
            try {
                BundleManagerPlugin bundleManager = getBundleManager();
                bundleManager.uninstallBundle(bundle, getUninstallOptions());
            } catch (BundleException ex) {
                throw new IllegalStateException(ex);
            }
        } else {
            LockableEnvironment env = (LockableEnvironment) getFrameworkState().getEnvironment();
            env.uninstallResourcesUnlocked(bundleRevision);
            bundleRevision.close();
        }
    }

    private int getUninstallOptions() {
        BundleManagerPlugin bundleManager = getBundleManager();
        ServiceContainer serviceContainer = bundleManager.getServiceContainer();
        ServiceController<?> controller = serviceContainer.getRequiredService(Services.BUNDLE_MANAGER);
        int managerState = bundleManager.getManagerState();
        Substate substate = controller.getSubstate();
        boolean stopping = managerState == Bundle.STOPPING || managerState == Bundle.RESOLVED || substate == Substate.STOP_REQUESTED;
        return InternalConstants.UNINSTALL_INTERNAL + (stopping ? Bundle.STOP_TRANSIENT : 0);
    }

    abstract R createBundleRevision(Deployment deployment, OSGiMetaData metadata, StorageState storageState, ServiceName serviceName, ServiceTarget serviceTarget)
            throws BundleException;

    UserBundleState createBundleState(UserBundleRevision revision) {
        return new UserBundleState(getFrameworkState(), revision);
    }

    private StorageState createStorageState(Deployment dep, RevisionIdentifier revIdentifier) throws BundleException {
        // The storage state exists when we re-create the bundle from persistent storage
        StorageState storageState = dep.getAttachment(StorageState.class);
        if (storageState == null) {
            String location = dep.getLocation();
            VirtualFile rootFile = dep.getRoot();
            try {
                BundleStorage storagePlugin = getFrameworkState().getBundleStorage();
                Integer startlevel = dep.getStartLevel();
                if (startlevel == null) {
                    startlevel = getFrameworkState().getStartLevelSupport().getInitialBundleStartLevel();
                }
                storageState = storagePlugin.createStorageState(revIdentifier.getRevisionId(), location, startlevel, rootFile);
                dep.addAttachment(StorageState.class, storageState);
            } catch (IOException ex) {
                throw MESSAGES.cannotSetupStorage(ex, rootFile);
            }
        }
        return storageState;
    }

    private void installBundleRevision(R bundleRev) throws BundleException {
        XEnvironment env = getFrameworkState().getEnvironment();
        env.installResources(bundleRev);
    }

    private void validateBundleRevision(R bundleRevision, OSGiMetaData metadata) throws BundleException {
        if (metadata.getBundleManifestVersion() > 1) {
            new BundleRevisionValidatorR4().validateBundleRevision(bundleRevision, metadata);
        } else {
            new BundleRevisionValidatorR3().validateBundleRevision(bundleRevision, metadata);
        }
    }

    // Process the Bundle-NativeCode header if there is one
    private void processNativeCode(R bundleRevision, OSGiMetaData metadata, Deployment dep) {
        if (metadata.getBundleNativeCode() != null) {
            NativeCode nativeCodePlugin = getFrameworkState().getNativeCode();
            nativeCodePlugin.deployNativeCode(dep);
        }
    }

    @Override
    R getBundleRevision() {
        return bundleRevision;
    }
}
