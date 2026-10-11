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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResource;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;

/**
 * An {@link XEnvironment} that finds the osgi.native capability of the system bundle.
 *
 * The environment looks up capabilities by the value of the namespace attribute, which it takes from the requirement filter.
 * Capabilities in the osgi.native namespace do not have such an attribute and are therefore not found.
 *
 * @since 11-Oct-2026
 */
final class NativeNamespaceEnvironment implements XEnvironment {

    private final XEnvironment delegate;
    private final BundleManagerPlugin bundleManager;

    NativeNamespaceEnvironment(XEnvironment delegate, BundleManagerPlugin bundleManager) {
        this.delegate = delegate;
        this.bundleManager = bundleManager;
    }

    @Override
    public List<Capability> findProviders(Requirement req) {
        List<Capability> result = delegate.findProviders(req);
        if (result.isEmpty() && NativeNamespace.NATIVE_NAMESPACE.equals(req.getNamespace()) && req instanceof XRequirement) {
            // Only the system bundle provides capabilities in the osgi.native namespace
            XBundleRevision sysrev = bundleManager.getSystemBundle().getBundleRevision();
            result = new ArrayList<Capability>();
            for (Capability cap : sysrev.getCapabilities(NativeNamespace.NATIVE_NAMESPACE)) {
                if (((XRequirement) req).matches(cap))
                    result.add(cap);
            }
        }
        return result;
    }

    @Override
    public void installResources(XResource... resources) {
        delegate.installResources(resources);
    }

    @Override
    public void uninstallResources(XResource... resources) {
        delegate.uninstallResources(resources);
    }

    @Override
    public XResource getResourceById(long identifier) {
        return delegate.getResourceById(identifier);
    }

    @Override
    public Iterator<XResource> getResources(Collection<String> types) {
        return delegate.getResources(types);
    }

    @Override
    public Long nextResourceIdentifier(Long value, String symbolicName) {
        return delegate.nextResourceIdentifier(value, symbolicName);
    }

    @Override
    public Map<Resource, Wiring> updateWiring(Map<Resource, List<Wire>> delta) {
        return delegate.updateWiring(delta);
    }

    @Override
    public Map<Resource, Wiring> getWirings() {
        return delegate.getWirings();
    }
}
