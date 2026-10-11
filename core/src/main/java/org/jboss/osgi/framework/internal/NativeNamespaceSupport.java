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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;
import org.jboss.osgi.metadata.Parameter;
import org.jboss.osgi.metadata.ParameterizedAttribute;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.resource.Namespace;

/**
 * Maps the Bundle-NativeCode header and the framework properties to the osgi.native namespace.
 *
 * @since 11-Oct-2026
 */
final class NativeNamespaceSupport {

    // Hide ctor
    private NativeNamespaceSupport() {
    }

    /**
     * The attributes of the osgi.native capability that the system bundle provides.
     *
     * The framework and system properties are available to the selection filter of a native code clause.
     * They are read when the filter is evaluated, like the framework properties themselves.
     */
    static Map<String, Object> getCapabilityAttributes(final BundleManagerPlugin bundleManager) {
        return new AbstractMap<String, Object>() {

            @Override
            public Object get(Object key) {
                if (NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE.equals(key))
                    return NativeCodeImpl.getOSNames((String) bundleManager.getProperty(Constants.FRAMEWORK_OS_NAME));
                if (NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE.equals(key))
                    return NativeCodeImpl.getProcessors((String) bundleManager.getProperty(Constants.FRAMEWORK_PROCESSOR));
                if (NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE.equals(key)) {
                    try {
                        return Version.parseVersion((String) bundleManager.getProperty(Constants.FRAMEWORK_OS_VERSION));
                    } catch (RuntimeException ex) {
                        return Version.emptyVersion;
                    }
                }
                if (NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE.equals(key))
                    return bundleManager.getProperty(Constants.FRAMEWORK_LANGUAGE);

                return key instanceof String ? bundleManager.getProperty((String) key) : null;
            }

            @Override
            public boolean containsKey(Object key) {
                return get(key) != null;
            }

            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                Map<String, Object> snapshot = new HashMap<String, Object>();
                for (String key : bundleManager.getProperties().keySet())
                    putIfPresent(snapshot, key);
                putIfPresent(snapshot, NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE);
                putIfPresent(snapshot, NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE);
                putIfPresent(snapshot, NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE);
                putIfPresent(snapshot, NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE);
                return Collections.unmodifiableSet(snapshot.entrySet());
            }

            private void putIfPresent(Map<String, Object> snapshot, String key) {
                Object value = get(key);
                if (value != null)
                    snapshot.put(key, value);
            }
        };
    }

    /**
     * The directives of the osgi.native requirement that corresponds to the given Bundle-NativeCode clauses
     *
     * @return null if there are no native code clauses
     */
    static Map<String, String> getRequirementDirectives(List<ParameterizedAttribute> params) {
        List<String> clauses = new ArrayList<String>();
        boolean optional = false;
        for (ParameterizedAttribute param : params) {
            if ("*".equals(param.getAttribute())) {
                optional = true;
                continue;
            }
            String clause = getClauseFilter(param);
            if (clause.length() > 0 && !clauses.contains(clause))
                clauses.add(clause);
        }
        if (clauses.isEmpty())
            return null;

        Map<String, String> dirs = new HashMap<String, String>();
        dirs.put(Namespace.REQUIREMENT_FILTER_DIRECTIVE, clauses.size() == 1 ? clauses.get(0) : "(|" + join(clauses) + ")");
        if (optional)
            dirs.put(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE, Namespace.RESOLUTION_OPTIONAL);
        return dirs;
    }

    private static String getClauseFilter(ParameterizedAttribute param) {
        List<String> parts = new ArrayList<String>();
        addApproxMatch(parts, NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE, param.getAttribute(Constants.BUNDLE_NATIVECODE_OSNAME));
        addApproxMatch(parts, NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE, param.getAttribute(Constants.BUNDLE_NATIVECODE_PROCESSOR));

        List<String> ranges = new ArrayList<String>();
        for (String range : getValues(param.getAttribute(Constants.BUNDLE_NATIVECODE_OSVERSION)))
            ranges.add(new VersionRange(range).toFilterString(NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE));
        if (!ranges.isEmpty())
            parts.add(ranges.size() == 1 ? ranges.get(0) : "(|" + join(ranges) + ")");

        addApproxMatch(parts, NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE, param.getAttribute(Constants.BUNDLE_NATIVECODE_LANGUAGE));

        for (String filter : getValues(param.getAttribute(Constants.SELECTION_FILTER_ATTRIBUTE)))
            parts.add(filter);

        if (parts.isEmpty())
            return "";
        return parts.size() == 1 ? parts.get(0) : "(&" + join(parts) + ")";
    }

    private static void addApproxMatch(List<String> parts, String attname, Parameter param) {
        List<String> matches = new ArrayList<String>();
        for (String value : getValues(param))
            matches.add("(" + attname + "~=" + escape(value) + ")");
        if (!matches.isEmpty())
            parts.add(matches.size() == 1 ? matches.get(0) : "(|" + join(matches) + ")");
    }

    private static List<String> getValues(Parameter param) {
        List<String> result = new ArrayList<String>();
        Object value = param != null ? param.getValue() : null;
        if (value instanceof Collection) {
            for (Object aux : (Collection<?>) value)
                result.add(String.valueOf(aux));
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '*' || ch == '\\')
                result.append('\\');
            result.append(ch);
        }
        return result.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts)
            result.append(part);
        return result.toString();
    }
}
