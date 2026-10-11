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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.resolver.XBundle;
import org.osgi.dto.DTO;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.framework.dto.FrameworkDTO;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.startlevel.dto.BundleStartLevelDTO;
import org.osgi.framework.startlevel.dto.FrameworkStartLevelDTO;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.framework.wiring.dto.BundleRevisionDTO;
import org.osgi.framework.wiring.dto.BundleWireDTO;
import org.osgi.framework.wiring.dto.BundleWiringDTO;
import org.osgi.framework.wiring.dto.FrameworkWiringDTO;
import org.osgi.resource.dto.CapabilityDTO;
import org.osgi.resource.dto.CapabilityRefDTO;
import org.osgi.resource.dto.RequirementDTO;
import org.osgi.resource.dto.RequirementRefDTO;
import org.osgi.resource.dto.WireDTO;

/**
 * Creates the DTOs that a bundle can be adapted to.
 *
 * This only uses the public bundle API, so that it also works for bundles that adapt a module.
 *
 * @since 11-Oct-2026
 */
public final class DTOFactory {

    // Hide ctor
    private DTOFactory() {
    }

    /**
     * Adapt the given bundle to a DTO type.
     *
     * @return null if the type is not a DTO type that is supported for the given bundle
     */
    @SuppressWarnings("unchecked")
    public static <T> T adapt(XBundle bundle, Class<T> type) {
        boolean uninstalled = bundle.getState() == Bundle.UNINSTALLED;
        if (type == BundleDTO.class) {
            return (T) createBundleDTO(bundle);
        } else if (type == BundleStartLevelDTO.class) {
            return uninstalled ? null : (T) createBundleStartLevelDTO(bundle);
        } else if (type == BundleRevisionDTO.class) {
            BundleRevision brev = uninstalled ? null : bundle.adapt(BundleRevision.class);
            return brev != null ? (T) new Graph().getRevisionDTO(brev) : null;
        } else if (type == BundleRevisionDTO[].class) {
            if (uninstalled)
                return null;
            List<BundleRevisionDTO> result = new ArrayList<BundleRevisionDTO>();
            for (BundleRevision brev : getRevisions(bundle))
                result.add(new Graph().getRevisionDTO(brev));
            return (T) result.toArray(new BundleRevisionDTO[result.size()]);
        } else if (type == BundleWiringDTO.class) {
            BundleWiring wiring = uninstalled ? null : bundle.adapt(BundleWiring.class);
            return wiring != null && wiring.isInUse() ? (T) createBundleWiringDTO(wiring) : null;
        } else if (type == BundleWiringDTO[].class) {
            if (uninstalled)
                return null;
            // Only wirings that are in use have capabilities, requirements and wires
            List<BundleWiringDTO> result = new ArrayList<BundleWiringDTO>();
            for (BundleRevision brev : getRevisions(bundle)) {
                BundleWiring wiring = brev.getWiring();
                if (wiring != null && wiring.isInUse())
                    result.add(createBundleWiringDTO(wiring));
            }
            return (T) result.toArray(new BundleWiringDTO[result.size()]);
        } else if (type == ServiceReferenceDTO[].class) {
            // Only a bundle with a valid bundle context has registered services
            if (uninstalled || bundle.getBundleContext() == null)
                return null;
            List<ServiceReferenceDTO> result = getServiceReferenceDTOs(bundle);
            return (T) result.toArray(new ServiceReferenceDTO[result.size()]);
        } else if (type == FrameworkDTO.class && bundle.getBundleId() == 0) {
            return (T) createFrameworkDTO(bundle);
        } else if (type == FrameworkWiringDTO.class && bundle.getBundleId() == 0) {
            return (T) createFrameworkWiringDTO(bundle);
        } else if (type == FrameworkStartLevelDTO.class && bundle.getBundleId() == 0) {
            FrameworkStartLevel fwkStartLevel = bundle.adapt(FrameworkStartLevel.class);
            if (fwkStartLevel == null)
                return null;
            FrameworkStartLevelDTO dto = new FrameworkStartLevelDTO();
            dto.startLevel = fwkStartLevel.getStartLevel();
            dto.initialBundleStartLevel = fwkStartLevel.getInitialBundleStartLevel();
            return (T) dto;
        }
        return null;
    }

    private static List<ServiceReferenceDTO> getServiceReferenceDTOs(Bundle bundle) {
        List<ServiceReferenceDTO> result = new ArrayList<ServiceReferenceDTO>();
        ServiceReference<?>[] srefs = bundle.getState() != Bundle.UNINSTALLED ? bundle.getRegisteredServices() : null;
        if (srefs != null) {
            for (ServiceReference<?> sref : srefs) {
                // The service may be unregistered concurrently
                if (sref.getBundle() != null)
                    result.add(createServiceReferenceDTO(sref));
            }
        }
        return result;
    }

    private static List<BundleRevision> getRevisions(Bundle bundle) {
        BundleRevisions brevs = bundle.adapt(BundleRevisions.class);
        return brevs != null ? brevs.getRevisions() : new ArrayList<BundleRevision>();
    }

    private static BundleDTO createBundleDTO(Bundle bundle) {
        BundleDTO dto = new BundleDTO();
        dto.id = bundle.getBundleId();
        dto.lastModified = bundle.getLastModified();
        dto.state = bundle.getState();
        dto.symbolicName = bundle.getSymbolicName();
        dto.version = String.valueOf(bundle.getVersion());
        return dto;
    }

    private static BundleStartLevelDTO createBundleStartLevelDTO(Bundle bundle) {
        BundleStartLevel startLevel = bundle.adapt(BundleStartLevel.class);
        if (startLevel == null)
            return null;
        BundleStartLevelDTO dto = new BundleStartLevelDTO();
        dto.bundle = bundle.getBundleId();
        dto.startLevel = startLevel.getStartLevel();
        dto.activationPolicyUsed = startLevel.isActivationPolicyUsed();
        dto.persistentlyStarted = startLevel.isPersistentlyStarted();
        return dto;
    }

    private static ServiceReferenceDTO createServiceReferenceDTO(ServiceReference<?> sref) {
        ServiceReferenceDTO dto = new ServiceReferenceDTO();
        dto.id = (Long) sref.getProperty(org.osgi.framework.Constants.SERVICE_ID);
        Bundle owner = sref.getBundle();
        dto.bundle = owner != null ? owner.getBundleId() : -1;
        dto.properties = new HashMap<String, Object>();
        for (String key : sref.getPropertyKeys())
            dto.properties.put(key, convertValue(sref.getProperty(key)));

        Bundle[] usingBundles = sref.getUsingBundles();
        dto.usingBundles = new long[usingBundles != null ? usingBundles.length : 0];
        for (int i = 0; i < dto.usingBundles.length; i++)
            dto.usingBundles[i] = usingBundles[i].getBundleId();
        return dto;
    }

    private static FrameworkDTO createFrameworkDTO(XBundle systemBundle) {
        BundleManager bundleManager = systemBundle.adapt(BundleManager.class);
        if (bundleManager == null)
            return null;

        FrameworkDTO dto = new FrameworkDTO();
        dto.bundles = new ArrayList<BundleDTO>();
        dto.services = new ArrayList<ServiceReferenceDTO>();
        for (XBundle bundle : bundleManager.getBundles()) {
            dto.bundles.add(createBundleDTO(bundle));
            dto.services.addAll(getServiceReferenceDTOs(bundle));
        }

        dto.properties = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : bundleManager.getProperties().entrySet()) {
            Object value = bundleManager.getProperty(entry.getKey());
            if (value != null)
                dto.properties.put(entry.getKey(), convertValue(value));
        }
        return dto;
    }

    private static FrameworkWiringDTO createFrameworkWiringDTO(XBundle systemBundle) {
        BundleManager bundleManager = systemBundle.adapt(BundleManager.class);
        if (bundleManager == null)
            return null;

        // Bundles that are uninstalled may still have wirings that are in use
        Set<Bundle> bundles = new LinkedHashSet<Bundle>(bundleManager.getBundles());
        FrameworkWiring frameworkWiring = systemBundle.adapt(FrameworkWiring.class);
        if (frameworkWiring != null)
            bundles.addAll(frameworkWiring.getRemovalPendingBundles());

        Graph graph = new Graph();
        for (Bundle bundle : bundles) {
            for (BundleRevision brev : getRevisions(bundle)) {
                BundleWiring wiring = brev.getWiring();
                if (wiring != null && wiring.isInUse())
                    graph.getNodeDTO(wiring);
            }
        }
        FrameworkWiringDTO dto = new FrameworkWiringDTO();
        dto.wirings = new HashSet<BundleWiringDTO.NodeDTO>(graph.nodes.values());
        dto.resources = new HashSet<BundleRevisionDTO>(graph.resources.values());
        return dto;
    }

    private static BundleWiringDTO createBundleWiringDTO(BundleWiring wiring) {
        Graph graph = new Graph();
        BundleWiringDTO dto = new BundleWiringDTO();
        dto.bundle = wiring.getBundle().getBundleId();
        dto.root = graph.getNodeDTO(wiring).id;
        dto.nodes = new HashSet<BundleWiringDTO.NodeDTO>(graph.nodes.values());
        dto.resources = new HashSet<BundleRevisionDTO>(graph.resources.values());
        return dto;
    }

    /**
     * Convert a value to a type that is valid in a DTO
     */
    static Object convertValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof DTO)
            return value;

        Class<?> type = value.getClass();
        if (type.isArray()) {
            Class<?> componentType = type.getComponentType();
            int length = Array.getLength(value);
            if (componentType.isPrimitive()) {
                Object result = Array.newInstance(componentType, length);
                System.arraycopy(value, 0, result, 0, length);
                return result;
            }
            boolean keepType = componentType == String.class || Number.class.isAssignableFrom(componentType) || componentType == Boolean.class
                    || componentType == Character.class || DTO.class.isAssignableFrom(componentType);
            Object[] result = (Object[]) Array.newInstance(keepType ? componentType : Object.class, length);
            for (int i = 0; i < length; i++)
                result[i] = convertValue(Array.get(value, i));
            return result;
        } else if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object aux : (List<?>) value)
                result.add(convertValue(aux));
            return result;
        } else if (value instanceof Set) {
            Set<Object> result = new HashSet<Object>();
            for (Object aux : (Set<?>) value)
                result.add(convertValue(aux));
            return result;
        } else if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                result.put(convertValue(entry.getKey()), convertValue(entry.getValue()));
            return result;
        }
        return String.valueOf(value);
    }

    /**
     * The revisions and wirings that are reachable from one DTO.
     */
    private static final class Graph {

        final Map<BundleRevision, BundleRevisionDTO> resources = new IdentityHashMap<BundleRevision, BundleRevisionDTO>();
        final Map<BundleWiring, BundleWiringDTO.NodeDTO> nodes = new IdentityHashMap<BundleWiring, BundleWiringDTO.NodeDTO>();
        private int getIdentity(Object obj) {
            return Identities.get(obj);
        }

        BundleRevisionDTO getRevisionDTO(BundleRevision brev) {
            BundleRevisionDTO dto = resources.get(brev);
            if (dto != null)
                return dto;

            dto = new BundleRevisionDTO();
            resources.put(brev, dto);
            dto.id = getIdentity(brev);
            dto.bundle = brev.getBundle().getBundleId();
            dto.symbolicName = brev.getSymbolicName();
            dto.version = String.valueOf(brev.getVersion());
            dto.type = brev.getTypes();
            dto.capabilities = new ArrayList<CapabilityDTO>();
            for (BundleCapability cap : brev.getDeclaredCapabilities(null)) {
                CapabilityDTO capdto = new CapabilityDTO();
                capdto.id = getIdentity(cap);
                capdto.namespace = cap.getNamespace();
                capdto.directives = new HashMap<String, String>(cap.getDirectives());
                capdto.attributes = convertAttributes(cap.getAttributes());
                capdto.resource = getRevisionDTO(cap.getRevision()).id;
                dto.capabilities.add(capdto);
            }
            dto.requirements = new ArrayList<RequirementDTO>();
            for (BundleRequirement req : brev.getDeclaredRequirements(null)) {
                RequirementDTO reqdto = new RequirementDTO();
                reqdto.id = getIdentity(req);
                reqdto.namespace = req.getNamespace();
                reqdto.directives = new HashMap<String, String>(req.getDirectives());
                reqdto.attributes = convertAttributes(req.getAttributes());
                reqdto.resource = getRevisionDTO(req.getRevision()).id;
                dto.requirements.add(reqdto);
            }
            return dto;
        }

        BundleWiringDTO.NodeDTO getNodeDTO(BundleWiring wiring) {
            BundleWiringDTO.NodeDTO dto = nodes.get(wiring);
            if (dto != null)
                return dto;

            dto = new BundleWiringDTO.NodeDTO();
            nodes.put(wiring, dto);
            dto.id = getIdentity(wiring);
            dto.current = wiring.isCurrent();
            dto.inUse = wiring.isInUse();
            dto.resource = getRevisionDTO(wiring.getRevision()).id;
            dto.capabilities = new ArrayList<CapabilityRefDTO>();
            // A wiring that is no longer in use returns null instead of a list
            for (BundleCapability cap : nullSafe(wiring.getCapabilities(null)))
                dto.capabilities.add(getCapabilityRefDTO(cap));
            dto.requirements = new ArrayList<RequirementRefDTO>();
            for (BundleRequirement req : nullSafe(wiring.getRequirements(null)))
                dto.requirements.add(getRequirementRefDTO(req));
            dto.providedWires = new ArrayList<WireDTO>();
            for (BundleWire wire : nullSafe(wiring.getProvidedWires(null))) {
                if (wire.getProviderWiring() != null && wire.getRequirerWiring() != null)
                    dto.providedWires.add(getWireDTO(wire));
            }
            dto.requiredWires = new ArrayList<WireDTO>();
            for (BundleWire wire : nullSafe(wiring.getRequiredWires(null))) {
                if (wire.getProviderWiring() != null && wire.getRequirerWiring() != null)
                    dto.requiredWires.add(getWireDTO(wire));
            }
            return dto;
        }

        private <E> List<E> nullSafe(List<E> list) {
            return list != null ? list : new ArrayList<E>();
        }

        private BundleWireDTO getWireDTO(BundleWire wire) {
            BundleWireDTO dto = new BundleWireDTO();
            dto.capability = getCapabilityRefDTO(wire.getCapability());
            dto.requirement = getRequirementRefDTO(wire.getRequirement());
            dto.provider = getRevisionDTO(wire.getProvider()).id;
            dto.requirer = getRevisionDTO(wire.getRequirer()).id;
            dto.providerWiring = getNodeDTO(wire.getProviderWiring()).id;
            dto.requirerWiring = getNodeDTO(wire.getRequirerWiring()).id;
            return dto;
        }

        private CapabilityRefDTO getCapabilityRefDTO(BundleCapability cap) {
            CapabilityRefDTO dto = new CapabilityRefDTO();
            dto.resource = getRevisionDTO(cap.getRevision()).id;
            dto.capability = getIdentity(cap);
            return dto;
        }

        private RequirementRefDTO getRequirementRefDTO(BundleRequirement req) {
            RequirementRefDTO dto = new RequirementRefDTO();
            dto.resource = getRevisionDTO(req.getRevision()).id;
            dto.requirement = getIdentity(req);
            return dto;
        }

        private Map<String, Object> convertAttributes(Map<String, Object> attributes) {
            Map<String, Object> result = new HashMap<String, Object>();
            for (Map.Entry<String, Object> entry : attributes.entrySet())
                result.put(entry.getKey(), convertValue(entry.getValue()));
            return result;
        }
    }

    /**
     * The ids of the objects that are represented in DTOs.
     *
     * An object keeps its id for as long as it is reachable, so that the DTOs
     * that are obtained from different bundles can be related to each other.
     */
    private static final class Identities {

        private static final Map<Key, Integer> ids = new HashMap<Key, Integer>();
        private static final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
        private static int lastId;

        static synchronized int get(Object obj) {
            Reference<?> stale;
            while ((stale = queue.poll()) != null)
                ids.remove(stale);

            Integer id = ids.get(new Key(obj, null));
            if (id == null) {
                id = ++lastId;
                ids.put(new Key(obj, queue), id);
            }
            return id;
        }

        private static final class Key extends WeakReference<Object> {

            private final int hashCode;

            Key(Object obj, ReferenceQueue<Object> queue) {
                super(obj, queue);
                hashCode = System.identityHashCode(obj);
            }

            @Override
            public int hashCode() {
                return hashCode;
            }

            @Override
            public boolean equals(Object other) {
                if (other == this)
                    return true;
                Object obj = get();
                return obj != null && other instanceof Key && ((Key) other).get() == obj;
            }
        }
    }
}
