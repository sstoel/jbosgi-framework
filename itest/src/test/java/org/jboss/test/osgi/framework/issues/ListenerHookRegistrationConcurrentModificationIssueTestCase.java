package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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


import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Registering a ListenerHook while another thread adds or removes service listeners fails with
 * {@code ConcurrentModificationException}: {@code registerService} builds the "existing listeners" collection for the
 * hook's initial {@code added()} call by iterating the per-bundle listener lists without the monitor that guards them.
 *
 * <h3>Severity</h3>
 * major. registerService throws an unchecked exception that the API does not allow; the bundle registering the hook
 * (typically from its activator) fails to start. Concurrent addServiceListener/removeServiceListener is normal
 * (ServiceTracker.open/close, DS, parallel bundle starts; in WildFly deployments start on several MSC threads). The
 * window grows with the number of service listeners in the framework.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl.getServiceListenerInfos}
 * (called by {@code ServiceManagerImpl.registerService}).
 *
 * <h3>Found by</h3>
 * static workstream: SpotBugs 4.9.8 JLM_JSR166_UTILCONCURRENT_MONITORENTER / IS2-style triage of FrameworkEventsImpl:
 * every access to the {@code serviceListeners} map and its ArrayList values is inside
 * {@code synchronized (serviceListeners)} except {@code getServiceListenerInfos} (category: collections shared across
 * threads without synchronization); confirmed with a stress test.
 *
 * <h3>Spec reference</h3>
 * {@code BundleContext.registerService} javadoc (osgi.core 8.0.0) lists only IllegalArgumentException,
 * SecurityException and IllegalStateException ("If this BundleContext is no longer valid"); OSGi Core R8 55.9.5.1
 * {@code ListenerHook.added}: "immediately after registration of this hook, this method will be called to provide the
 * current collection of service listeners which had been added prior to the hook being registered."; R8 55.7.5 Multi
 * Threading: "the hooks can be called any time".
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:245-255}
 * ({@code getServiceListenerInfos}: {@code for (Entry<XBundle, List<ServiceListenerRegistration>> entry :
 * serviceListeners.entrySet()) for (ServiceListenerRegistration aux : entry.getValue())} - the values are plain
 * ArrayLists that addServiceListener/removeServiceListener modify under {@code synchronized (serviceListeners)},
 * lines 211-241 and 268-290), called without that monitor from
 * {@code ServiceManagerImpl.java:108} ({@code registerService} of a ListenerHook). Pre-existing: identical on WF41.
 *
 * <h3>Root cause</h3>
 * Missing {@code synchronized (serviceListeners)} (or a copy-on-write list) in getServiceListenerInfos.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ListenerHookRegistrationConcurrentModificationIssueTestCase
 * </pre>
 * The test registers 2000 service listeners on the system context, lets a second thread add and remove one more
 * listener on the same context in a loop, and registers ListenerHooks (up to 300, or 20 s) on the test thread. Hooks
 * are not unregistered while the second thread runs (that would hit the deadlock of static/06).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: registerService(ListenerHook) must not fail while other threads add/remove service
 * listeners, but attempt 1 threw java.util.ConcurrentModificationException | at
 * java.base/java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1096) | at
 * java.base/java.util.ArrayList$Itr.next(ArrayList.java:1050) | at
 * org.jboss.osgi.framework.internal.FrameworkEventsImpl.getServiceListenerInfos(FrameworkEventsImpl.java:249) | at
 * org.jboss.osgi.framework.internal.ServiceManagerImpl.registerService(ServiceManagerImpl.java:108)
 * </pre>
 * 5 of 5 runs failed on the first registration attempt (JDK 25).
 *
 * <h3>Expected</h3>
 * Every registerService returns normally; each hook gets a consistent snapshot of the existing listeners.
 *
 * <h3>Reference behaviour</h3>
 * Equinox and Felix build the initial listener collection under their listener lock (Equinox
 * ServiceRegistry.notifyNewListenerHook, Felix EventDispatcher.getAllServiceListeners); not run as a differential.
 *
 * <h3>Suggested fix</h3>
 * Take {@code synchronized (serviceListeners)} while copying the ListenerInfos in getServiceListenerInfos (and, to
 * not miss listeners added between the snapshot and the registration, take the snapshot after the hook is in the
 * registry).
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 *
 * <h3>Related</h3>
 * static/06 ListenerHookUnregisterDeadlockIssueTestCase (same listener bookkeeping, different defect: lock order).
 */
public class ListenerHookRegistrationConcurrentModificationIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testRegisterListenerHookWhileListenersChange() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/listener-hook-cme").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        final BundleContext context = framework.getBundleContext();

        for (int i = 0; i < 2000; i++)
            context.addServiceListener(new NoopListener(), "(objectClass=com.acme.static07.Foo" + i + ")");

        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> listenerThreadFailure = new AtomicReference<Throwable>();
        Thread listenerThread = new Thread(new Runnable() {
            public void run() {
                while (!stop.get()) {
                    ServiceListener listener = new NoopListener();
                    try {
                        context.addServiceListener(listener, "(objectClass=com.acme.static07.Bar)");
                        context.removeServiceListener(listener);
                    } catch (Throwable th) {
                        listenerThreadFailure.compareAndSet(null, th);
                        return;
                    }
                }
            }
        }, "static07-listeners");
        listenerThread.setDaemon(true);

        List<ServiceRegistration<ListenerHook>> hooks = new ArrayList<ServiceRegistration<ListenerHook>>();
        Throwable registerFailure = null;
        int attempts = 0;
        listenerThread.start();
        try {
            long deadline = System.currentTimeMillis() + 20000;
            while (attempts < 300 && System.currentTimeMillis() < deadline) {
                attempts++;
                try {
                    hooks.add(context.registerService(ListenerHook.class, new NoopListenerHook(), null));
                } catch (Throwable th) {
                    registerFailure = th;
                    break;
                }
            }
        } finally {
            stop.set(true);
            listenerThread.join(20000);
            for (ServiceRegistration<ListenerHook> hook : hooks)
                hook.unregister();
        }
        assertNull("registerService(ListenerHook) must not fail while other threads add/remove service listeners, but attempt "
                + attempts + " threw " + stackOf(registerFailure), registerFailure);
        assertNull("addServiceListener/removeServiceListener failed: " + stackOf(listenerThreadFailure.get()), listenerThreadFailure.get());
    }

    private static String stackOf(Throwable th) {
        if (th == null)
            return null;
        StringWriter sw = new StringWriter();
        th.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, lines.length); i++)
            sb.append(lines[i].trim()).append(" | ");
        return sb.toString();
    }

    static class NoopListener implements ServiceListener {
        public void serviceChanged(ServiceEvent event) {
        }
    }

    static class NoopListenerHook implements ListenerHook {
        public void added(Collection<ListenerHook.ListenerInfo> listeners) {
        }

        public void removed(Collection<ListenerHook.ListenerInfo> listeners) {
        }
    }
}
