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

import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;
import static org.osgi.framework.Constants.SYSTEM_BUNDLE_LOCATION;
import static org.osgi.framework.namespace.ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.AbstractIntegrationService;
import org.jboss.osgi.framework.spi.FrameworkModuleProvider;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.ModuleManager;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.framework.spi.SystemPaths;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.metadata.spi.ElementParser;
import org.jboss.osgi.resolver.XEnvironment;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Version;

/**
 * A plugin for the system bundle.
 *
 * @author thomas.diesler@jboss.com
 * @since 04-Apr-2011
 */
final class SystemBundlePlugin extends AbstractIntegrationService<SystemBundleState> {

    private final FrameworkState frameworkState;
    private SystemBundleState bundleState;

    SystemBundlePlugin(FrameworkState frameworkState) {
        super(IntegrationServices.SYSTEM_BUNDLE_INTERNAL);
        this.frameworkState = frameworkState;
    }

    @Override
    protected void addServiceDependencies(ServiceBuilder<SystemBundleState> builder) {
        builder.addDependency(Services.ENVIRONMENT, XEnvironment.class, frameworkState.injectedEnvironment);
        builder.addDependency(IntegrationServices.STORAGE_MANAGER_PLUGIN, StorageManager.class, frameworkState.injectedStorageManager);
        builder.addDependency(IntegrationServices.FRAMEWORK_MODULE_PLUGIN, FrameworkModuleProvider.class, frameworkState.injectedModuleProvider);
        builder.addDependency(IntegrationServices.LOCK_MANAGER_PLUGIN, LockManager.class, frameworkState.injectedLockManager);
        builder.addDependency(IntegrationServices.MODULE_MANGER_PLUGIN, ModuleManager.class, frameworkState.injectedModuleManager);
        builder.addDependency(IntegrationServices.SYSTEM_PATHS_PLUGIN, SystemPaths.class, frameworkState.injectedSystemPaths);
        builder.addDependency(FrameworkEnvironment.SERVICE_NAME, FrameworkEnvironment.class, frameworkState.injectedFrameworkEnvironment);
        builder.setInitialMode(Mode.ON_DEMAND);
    }

    @Override
    protected SystemBundleState createServiceValue(StartContext startContext) throws StartException {
        StorageState storageState = null;
        BundleManagerPlugin bundleManager = frameworkState.getBundleManager();
        try {
            // Framework extensions need a framework module that can take their content
            if (frameworkState.getFrameworkModuleProvider().supportsExtensions())
                bundleManager.setProperty(Constants.SUPPORTS_FRAMEWORK_EXTENSION, Boolean.TRUE.toString());

            OSGiMetaData metadata = createMetaData(bundleManager);
            storageState = createStorageState();
            SystemBundleRevision brev = new SystemBundleRevision(frameworkState, metadata, storageState);
            bundleState = new SystemBundleState(frameworkState, brev);
            bundleState.changeState(Bundle.STARTING);
            addToEnvironment(brev);
            bundleState.createBundleContext();
            bundleManager.injectedSystemBundle.inject(bundleState);
        } catch (BundleException ex) {
            if (storageState != null) {
                StorageManager storagePlugin = frameworkState.getStorageManager();
                storagePlugin.deleteStorageState(storageState);
            }
            throw new StartException(ex);
        }
        return bundleState;
    }

    @Override
    public void stop(StopContext context) {
        BundleManagerPlugin bundleManager = frameworkState.getBundleManager();
        bundleManager.injectedSystemBundle.uninject();
    }

    private StorageState createStorageState() {
        try {
            StorageManager storagePlugin = frameworkState.getStorageManager();
            return storagePlugin.createStorageState(0, SYSTEM_BUNDLE_LOCATION, 0, null);
        } catch (IOException ex) {
            throw MESSAGES.illegalStateCannotCreateSystemBundleStorage(ex);
        }
    }

    @SuppressWarnings("deprecation")
    private OSGiMetaData createMetaData(BundleManagerPlugin bundleManager) {

        // Initialize the OSGiMetaData
        OSGiMetaDataBuilder builder = OSGiMetaDataBuilder.createBuilder(Constants.SYSTEM_BUNDLE_SYMBOLICNAME, Version.emptyVersion);
        SystemPaths systemPaths = frameworkState.getSystemPaths();

        // Construct framework capabilities from system packages
        for (String packageSpec : systemPaths.getSystemPackages()) {
            builder.addExportPackages(packageSpec);
        }

        // The services of the Log Service that the system bundle registers
        builder.addProvidedCapabilities("osgi.service;objectClass:List<String>=\"org.osgi.service.log.LogService,org.osgi.service.log.LoggerFactory\";uses:=\"org.osgi.service.log\"");
        builder.addProvidedCapabilities("osgi.service;objectClass:List<String>=\"org.osgi.service.log.LogReaderService\";uses:=\"org.osgi.service.log\"");
        builder.addProvidedCapabilities("osgi.service;objectClass:List<String>=\"org.osgi.service.log.admin.LoggerAdmin\";uses:=\"org.osgi.service.log.admin\"");

        // Add framework system capabilities
        String syscaps = (String) bundleManager.getProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT);
        if (syscaps != null) {
            Map<String, List<String>> nameversion = new LinkedHashMap<String, List<String>>();
            for (String piece : ElementParser.parseDelimitedString(syscaps, ',')) {
                StringBuffer namepart = new StringBuffer();
                StringBuffer versionpart = new StringBuffer();
                OSGiMetaDataBuilder.convertExecutionEnvironmentHeader(piece, namepart, versionpart);
                List<String> versions = nameversion.get(namepart.toString());
                if (versions == null) {
                    versions = new ArrayList<String>();
                    nameversion.put(namepart.toString(), versions);
                }
                if (versionpart.length() > 0) {
                    versions.add(versionpart.toString());
                }
            }
            for (Entry<String, List<String>> entry : nameversion.entrySet()) {
                String namepart = entry.getKey();

                // The 1.x versions and the versions since Java 9 are provided by separate capabilities, so that
                // a requirement for a version below 2.0, like (&(version>=1.3)(!(version>=2.0))), can be satisfied
                List<String> legacy = new ArrayList<String>();
                List<String> modern = new ArrayList<String>();
                for (String version : entry.getValue()) {
                    if (version.equals("1") || version.startsWith("1."))
                        legacy.add(version);
                    else
                        modern.add(version);
                }
                List<List<String>> groups = new ArrayList<List<String>>();
                if (legacy.isEmpty() == false)
                    groups.add(legacy);
                if (modern.isEmpty() == false || legacy.isEmpty())
                    groups.add(modern);

                for (List<String> versions : groups) {
                    String capspec = EXECUTION_ENVIRONMENT_NAMESPACE + ";" + EXECUTION_ENVIRONMENT_NAMESPACE + "=\"" + namepart + "\"";
                    if (versions.size() > 0) {
                        String vlist = "";
                        for (String version : versions) {
                            vlist += "," + version;
                        }
                        // The version attribute is a List<Version>, also for one version (Table 8.2)
                        capspec += ";version:List<Version>=\"" + vlist.substring(1) + "\"";
                    }
                    builder.addProvidedCapabilities(capspec);
                }
            }
        } else {
            // Register capabilities for the OSGi/Minimim execution environments the Framework is known to be backward compatible with
            String capspec = EXECUTION_ENVIRONMENT_NAMESPACE + ";" + EXECUTION_ENVIRONMENT_NAMESPACE + "=\"OSGi/Minimum\";version:List<Version>=\"";
            String vlist = "";
            if (Java.isCompatible(Java.VERSION_1_1)) {
                vlist += ",1.1";
            }
            if (Java.isCompatible(Java.VERSION_1_2)) {
                vlist += ",1.2";
            }
            capspec += vlist.substring(1) + "\"";
            builder.addProvidedCapabilities(capspec);

            // Register capabilities for the JavaSE execution environments the Framework is known to be backward compatible with
            capspec = EXECUTION_ENVIRONMENT_NAMESPACE + ";" + EXECUTION_ENVIRONMENT_NAMESPACE + "=\"JavaSE\";version:List<Version>=\"";
            vlist = "";
            if (Java.isCompatible(Java.VERSION_1_3)) {
                vlist += ",1.3";
            }
            if (Java.isCompatible(Java.VERSION_1_4)) {
                vlist += ",1.4";
            }
            if (Java.isCompatible(Java.VERSION_1_5)) {
                vlist += ",1.5";
            }
            if (Java.isCompatible(Java.VERSION_1_6)) {
                vlist += ",1.6";
            }
            if (Java.isCompatible(Java.VERSION_1_7)) {
                vlist += ",1.7";
            }
            if (Java.isCompatible(Java.VERSION_1_8)) {
                vlist += ",1.8";
            }
            if (Java.isCompatible(Java.VERSION_9)) {
                for (int version = 9; version <= Java.getFeatureVersion(); version++)
                    vlist += "," + version;
            }
            capspec += vlist.substring(1) + "\"";
            builder.addProvidedCapabilities(capspec);
        }

        // Add framework system capabilities
        syscaps = (String) bundleManager.getProperty(Constants.FRAMEWORK_SYSTEMCAPABILITIES);
        if (syscaps != null) {
            List<String> pieces = ElementParser.parseDelimitedString(syscaps, ',');
            builder.addProvidedCapabilities(pieces.toArray(new String[pieces.size()]));
        }

        // Add extra framework system capabilities
        syscaps = (String) bundleManager.getProperty(Constants.FRAMEWORK_SYSTEMCAPABILITIES_EXTRA);
        if (syscaps != null) {
            List<String> pieces = ElementParser.parseDelimitedString(syscaps, ',');
            builder.addProvidedCapabilities(pieces.toArray(new String[pieces.size()]));
        }

        return builder.getOSGiMetaData();
    }

    private void addToEnvironment(SystemBundleRevision sysrev) {
        frameworkState.getFrameworkEnvironment().installResources(sysrev);
    }
}
