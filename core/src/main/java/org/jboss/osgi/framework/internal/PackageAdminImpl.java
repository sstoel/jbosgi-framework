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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.resolver.XBundle;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.packageadmin.ExportedPackage;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.packageadmin.RequiredBundle;

/**
 * An implementation of the deprecated {@link PackageAdmin} service on top of the wiring API.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 13-Mar-2013
 */
@SuppressWarnings("deprecation")
public final class PackageAdminImpl implements PackageAdmin {

    private final BundleManager bundleManager;

    public PackageAdminImpl(BundleManager bundleManager) {
        this.bundleManager = bundleManager;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Bundle getBundle(Class clazz) {
        Bundle result = null;
        ClassLoader classLoader = clazz != null ? clazz.getClassLoader() : null;
        if (classLoader instanceof BundleReference) {
            result = ((BundleReference) classLoader).getBundle();
        }
        return result;
    }

    @Override
    public void refreshPackages(Bundle[] barray) {
        XBundle systemBundle = bundleManager.getSystemBundle();
        FrameworkWiring fwkWiring = systemBundle.adapt(FrameworkWiring.class);
        Collection<Bundle> bundles = barray != null ? Arrays.asList(barray) : null;
        fwkWiring.refreshBundles(bundles, (FrameworkListener) null);
    }

    @Override
    public boolean resolveBundles(Bundle[] barray) {
        XBundle systemBundle = bundleManager.getSystemBundle();
        FrameworkWiring fwkWiring = systemBundle.adapt(FrameworkWiring.class);
        Collection<Bundle> bundles = barray != null ? Arrays.asList(barray) : null;
        return fwkWiring.resolveBundles(bundles);
    }

    @Override
    public Bundle[] getBundles(String symbolicName, String rangespec) {
        VersionRange vrange = rangespec != null ? new VersionRange(rangespec) : null;
        Set<XBundle> bundles = bundleManager.getBundles(symbolicName, vrange);
        List<Bundle> result = new ArrayList<Bundle>();
        for (Bundle aux : bundles) {
            result.add(aux);
        }
        return !result.isEmpty() ? result.toArray(new Bundle[result.size()]) : null;
    }

    @Override
    public int getBundleType(Bundle bundle) {
        return bundle != null && ((XBundle) bundle).isFragment() ? PackageAdmin.BUNDLE_TYPE_FRAGMENT : 0;
    }

    @Override
    public ExportedPackage[] getExportedPackages(Bundle bundle) {
        List<ExportedPackage> result = new ArrayList<ExportedPackage>();
        Collection<? extends Bundle> bundles = bundle != null ? Collections.singleton(bundle) : bundleManager.getBundles((Integer) null);
        for (Bundle aux : bundles) {
            for (BundleWiring wiring : getWirings(aux)) {
                List<BundleCapability> caps = wiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE);
                for (BundleCapability cap : caps != null ? caps : Collections.<BundleCapability>emptyList()) {
                    result.add(new ExportedPackageImpl(cap, wiring));
                }
            }
        }
        return !result.isEmpty() ? result.toArray(new ExportedPackage[result.size()]) : null;
    }

    @Override
    public ExportedPackage[] getExportedPackages(String name) {
        List<ExportedPackage> result = new ArrayList<ExportedPackage>();
        ExportedPackage[] all = getExportedPackages((Bundle) null);
        for (ExportedPackage aux : all != null ? all : new ExportedPackage[0]) {
            if (aux.getName().equals(name))
                result.add(aux);
        }
        return !result.isEmpty() ? result.toArray(new ExportedPackage[result.size()]) : null;
    }

    @Override
    public ExportedPackage getExportedPackage(String name) {
        // The current export with the highest version
        ExportedPackage result = null;
        ExportedPackage[] exports = getExportedPackages(name);
        for (ExportedPackage aux : exports != null ? exports : new ExportedPackage[0]) {
            if (aux.isRemovalPending() == false && (result == null || aux.getVersion().compareTo(result.getVersion()) > 0))
                result = aux;
        }
        return result;
    }

    @Override
    public RequiredBundle[] getRequiredBundles(String symbolicName) {
        List<RequiredBundle> result = new ArrayList<RequiredBundle>();
        for (XBundle bundle : bundleManager.getBundles((Integer) null)) {
            if (bundle.isFragment() || (symbolicName != null && !symbolicName.equals(bundle.getSymbolicName())))
                continue;
            for (BundleWiring wiring : getWirings(bundle)) {
                result.add(new RequiredBundleImpl(bundle, wiring));
            }
        }
        return !result.isEmpty() ? result.toArray(new RequiredBundle[result.size()]) : null;
    }

    @Override
    public Bundle[] getFragments(Bundle bundle) {
        Set<Bundle> result = new LinkedHashSet<Bundle>();
        BundleWiring wiring = bundle != null ? bundle.adapt(BundleWiring.class) : null;
        List<BundleWire> wires = wiring != null ? wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE) : null;
        for (BundleWire wire : wires != null ? wires : Collections.<BundleWire>emptyList()) {
            result.add(wire.getRequirer().getBundle());
        }
        return !result.isEmpty() ? result.toArray(new Bundle[result.size()]) : null;
    }

    @Override
    public Bundle[] getHosts(Bundle bundle) {
        Set<Bundle> result = new LinkedHashSet<Bundle>();
        BundleWiring wiring = bundle != null ? bundle.adapt(BundleWiring.class) : null;
        List<BundleWire> wires = wiring != null ? wiring.getRequiredWires(HostNamespace.HOST_NAMESPACE) : null;
        for (BundleWire wire : wires != null ? wires : Collections.<BundleWire>emptyList()) {
            result.add(wire.getProvider().getBundle());
        }
        return !result.isEmpty() ? result.toArray(new Bundle[result.size()]) : null;
    }

    // The wirings of the bundle that are in use: the current one and those of removal pending revisions
    private static List<BundleWiring> getWirings(Bundle bundle) {
        List<BundleWiring> result = new ArrayList<BundleWiring>();
        BundleRevisions revisions = bundle.adapt(BundleRevisions.class);
        for (BundleRevision brev : revisions != null ? revisions.getRevisions() : Collections.<BundleRevision>emptyList()) {
            BundleWiring wiring = brev.getWiring();
            if (wiring != null && wiring.isInUse() && (brev.getTypes() & BundleRevision.TYPE_FRAGMENT) == 0)
                result.add(wiring);
        }
        return result;
    }

    private static final class ExportedPackageImpl implements ExportedPackage {

        private final BundleCapability capability;
        private final BundleWiring wiring;

        ExportedPackageImpl(BundleCapability capability, BundleWiring wiring) {
            this.capability = capability;
            this.wiring = wiring;
        }

        @Override
        public String getName() {
            return (String) capability.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
        }

        @Override
        public Bundle getExportingBundle() {
            return wiring.isInUse() ? wiring.getBundle() : null;
        }

        @Override
        public Bundle[] getImportingBundles() {
            if (wiring.isInUse() == false)
                return null;
            // The bundles that import the package, and those that require the exporting bundle
            Set<Bundle> result = new LinkedHashSet<Bundle>();
            List<BundleWire> wires = wiring.getProvidedWires(PackageNamespace.PACKAGE_NAMESPACE);
            for (BundleWire wire : wires != null ? wires : Collections.<BundleWire>emptyList()) {
                if (wire.getCapability().equals(capability))
                    result.add(wire.getRequirer().getBundle());
            }
            wires = wiring.getProvidedWires(BundleNamespace.BUNDLE_NAMESPACE);
            for (BundleWire wire : wires != null ? wires : Collections.<BundleWire>emptyList()) {
                result.add(wire.getRequirer().getBundle());
            }
            return result.toArray(new Bundle[result.size()]);
        }

        @Override
        public String getSpecificationVersion() {
            return getVersion().toString();
        }

        @Override
        public Version getVersion() {
            Object version = capability.getAttributes().get(PackageNamespace.CAPABILITY_VERSION_ATTRIBUTE);
            return version instanceof Version ? (Version) version : Version.emptyVersion;
        }

        @Override
        public boolean isRemovalPending() {
            return wiring.isCurrent() == false;
        }
    }

    private static final class RequiredBundleImpl implements RequiredBundle {

        private final Bundle bundle;
        private final BundleWiring wiring;

        RequiredBundleImpl(Bundle bundle, BundleWiring wiring) {
            this.bundle = bundle;
            this.wiring = wiring;
        }

        @Override
        public String getSymbolicName() {
            return wiring.getRevision().getSymbolicName();
        }

        @Override
        public Bundle getBundle() {
            return wiring.isInUse() ? bundle : null;
        }

        @Override
        public Bundle[] getRequiringBundles() {
            if (wiring.isInUse() == false)
                return null;
            Set<Bundle> result = new LinkedHashSet<Bundle>();
            List<BundleWire> wires = wiring.getProvidedWires(BundleNamespace.BUNDLE_NAMESPACE);
            for (BundleWire wire : wires != null ? wires : Collections.<BundleWire>emptyList()) {
                result.add(wire.getRequirer().getBundle());
            }
            return result.toArray(new Bundle[result.size()]);
        }

        @Override
        public Version getVersion() {
            return wiring.getRevision().getVersion();
        }

        @Override
        public boolean isRemovalPending() {
            return wiring.isCurrent() == false;
        }
    }
}
