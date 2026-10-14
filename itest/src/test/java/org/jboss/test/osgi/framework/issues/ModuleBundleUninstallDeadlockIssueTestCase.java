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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.osgi.framework.spi.AbstractBundleRevisionAdaptor;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.VirtualFileResourceLoader;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XBundleRevisionBuilder;
import org.jboss.osgi.resolver.XBundleRevisionBuilderFactory;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.spi.OSGiMetaDataProcessor;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Permanent deadlocks when a bundle that adapts a module (AbstractBundleAdaptor, e.g. a WildFly module deployment
 * with its own ModuleBundleContext) is uninstalled while another thread unregisters a service that the module bundle
 * uses, or while the module bundle's code removes (or adds) a service listener: uninstall holds the bundle object's
 * monitor while it closes the bundle context (unregistering and releasing services, delivering UNREGISTERING,
 * removing listeners), and the framework takes the same monitor from inside the service state monitor
 * (unregisterService -&gt; BundleServices.get(bundle) -&gt; adapt -&gt; getModuleBundleContext) and from inside the
 * service listener monitor (ServiceListenerRegistration -&gt; bundle.getBundleContext()).
 *
 * <h3>Severity</h3>
 * critical. Java monitors, no timeout: the undeploying thread and the other thread hang forever; the second scenario
 * also holds the framework-wide service listener monitor, so every later addServiceListener/removeServiceListener,
 * service registration/unregistration (event delivery) and ServiceTracker open/close blocks, i.e. every bundle start and
 * stop and every OSGi deployment operation in WildFly. Trigger: undeploying a module deployment that uses OSGi services
 * through its bundle context (ServiceTracker, injected BundleContext) while a provider bundle stops, or while the
 * deployment's own code closes a ServiceTracker (e.g. in a container shutdown callback on another thread).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.spi.AbstractBundleAdaptor} ({@code uninstall},
 * {@code getModuleBundleContext}, {@code adapt(BundleServices)}) with
 * {@code org.jboss.osgi.framework.internal.ServiceManagerImpl.unregisterService} and
 * {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl} (add/removeServiceListener).
 *
 * <h3>Found by</h3>
 * concurrency workstream: lock order recorder (bytecode agent, {@code agents/concurrency/lockrec}) over the framework
 * itest suite (ModuleBundleContextTestCase): edges AbstractBundleAdaptor monitor -&gt; ServiceStateImpl monitor
 * (uninstall:371 -&gt; ModuleBundleContext.close -&gt; unregisterService), ServiceStateImpl monitor -&gt;
 * AbstractBundleAdaptor monitor (unregisterService:313/322 -&gt; BundleServices.get:64 -&gt; adapt:210 -&gt;
 * getModuleBundleContext), service listener monitor -&gt; AbstractBundleAdaptor monitor
 * (FrameworkEventsImpl.removeServiceListener:264 -&gt; ServiceListenerRegistration.&lt;init&gt;:764 -&gt;
 * getBundleContext:442) and AbstractBundleAdaptor monitor -&gt; service listener monitor (close -&gt;
 * removeServiceListeners); made deterministic with an UNREGISTERING ServiceListener that waits until the other thread
 * is BLOCKED.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.7.3 Synchronization Pitfalls
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "Generally, a bundle that calls a
 * listener should not hold any Java monitors. This means that neither the Framework nor the originator of a
 * synchronous event should be in a monitor when a callback is initiated." ... "Java monitors do not have time-outs.
 * Therefore, the code will hang forever until the system is reset". Bundle.uninstall (10.1.5.37),
 * ServiceRegistration.unregister (10.1.30.3), BundleContext.removeServiceListener (10.1.7.27): no blocking specified;
 * Bundle, BundleContext, ServiceRegistration: "Concurrency Thread-safe".
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/spi/AbstractBundleAdaptor.java:371-377} ({@code uninstall}:
 * {@code synchronized (this) { bundleContext.close(); ... }} - ModuleBundleContext.close -&gt; release -&gt;
 * BundleServices.release (unregister/unget, UNREGISTERING delivered) and FrameworkEvents.removeServiceListeners),
 * {@code :451-457} ({@code private synchronized ModuleBundleContext getModuleBundleContext()}), {@code :209-211}
 * ({@code adapt(BundleServices.class)} calls it); {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:287, 313-322}
 * ({@code synchronized (serviceState)} around {@code ungetService(bundle, ...)} and {@code BundleServices.get(bundle)});
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:261-264, 764}
 * ({@code synchronized (serviceListeners)} around {@code new ServiceListenerRegistration(bundle, ...)}, whose
 * constructor calls {@code bundle.getBundleContext()}). Introduced with the module bundle context, commit 5a017edc
 * ("Give bundles that adapt a module their own bundle context", 2026-10-11), which is on both WF41 and WF41-osgi-r8.
 *
 * <h3>Root cause</h3>
 * The module bundle's monitor is used both as an inner lock (taken by framework code that already holds the service
 * state monitor or the service listener monitor) and as an outer lock around the context close, which takes those
 * monitors and calls back into foreign code. Lock pairs: AbstractBundleAdaptor monitor vs ServiceStateImpl monitor
 * (test 1) and AbstractBundleAdaptor monitor vs the FrameworkEventsImpl service listener monitor (test 2).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ModuleBundleUninstallDeadlockIssueTestCase
 * </pre>
 * Each test uses its own framework with a module bundle installed as in xservice/AbstractModuleIntegrationTest.
 * testUninstallWhileUnregisteringUsedService: the module bundle uses service S; S is unregistered on the test thread,
 * and S's UNREGISTERING listener uninstalls the module bundle on another thread and waits (at most 5 s) until that
 * thread is BLOCKED. testRemoveListenerWhileUninstalling: the module bundle registered service T and a listener L;
 * the uninstall runs on another thread, T's UNREGISTERING listener (called while the uninstall holds the module
 * bundle's monitor) starts a thread that removes L through the module bundle's context and waits until it is BLOCKED.
 * About 12 s per test; a jstack is printed when the threads hang. The frameworks are left running (they cannot be
 * stopped once deadlocked).
 *
 * <h3>Observed</h3>
 * <pre>
 * testUninstallWhileUnregisteringUsedService: java.lang.AssertionError: uninstall of a module bundle and a concurrent
 * unregister() of a service it uses must both return, but after 10 s concurrency07-uninstall-1 alive=true,
 * concurrency07-unregister alive=true; JVM monitor deadlock:
 *   "concurrency07-unregister" waits for org.jboss.osgi.framework.spi.AbstractBundleAdaptor@75d7297d held by
 *   "concurrency07-uninstall-1" at ...AbstractBundleAdaptor.getModuleBundleContext(AbstractBundleAdaptor.java)
 *   "concurrency07-uninstall-1" waits for org.jboss.osgi.framework.internal.ServiceStateImpl@433b1597 held by
 *   "concurrency07-unregister" at ...ServiceStateImpl.hasPrototypeValues(ServiceStateImpl.java:334)
 * testRemoveListenerWhileUninstalling: java.lang.AssertionError: uninstall of a module bundle and a concurrent
 * removeServiceListener() through its context must both return, but after 10 s ...; JVM monitor deadlock:
 *   "concurrency07-uninstall-2" waits for java.util.concurrent.ConcurrentHashMap@1eabfad5 held by
 *   "concurrency07-remove-listener" at ...FrameworkEventsImpl.removeServiceListeners(FrameworkEventsImpl.java:288)
 *   "concurrency07-remove-listener" waits for org.jboss.osgi.framework.spi.AbstractBundleAdaptor@257b3561 held by
 *   "concurrency07-uninstall-2" at ...AbstractBundleAdaptor.getModuleBundleContext(AbstractBundleAdaptor.java)
 * </pre>
 * (jstack "Found one Java-level deadlock" with both stacks in run.log). 2 of 2 runs failed (JDK 25).
 *
 * <h3>Expected</h3>
 * All calls return: the uninstall completes, the unregistration completes, the listener removal completes (or is a
 * no-op on an invalid context).
 *
 * <h3>Reference behaviour</h3>
 * Not applicable (module bundles are a jbosgi/WildFly integration feature); Equinox and Felix do not hold a bundle
 * monitor while closing a bundle context (their uninstall holds the per-bundle state change lock, which
 * unregisterService/addServiceListener never take).
 *
 * <h3>Suggested fix</h3>
 * In AbstractBundleAdaptor.uninstall take the context out under the monitor ({@code ctx = bundleContext;
 * bundleContext = null; bundleState.set(UNINSTALLED)}) and call {@code ctx.close()} after leaving it; create the
 * context eagerly or with a CAS so that getModuleBundleContext needs no monitor, and do not call it (via adapt) from
 * inside the service state monitor (see also concurrency/01 for moving the release out of that monitor).
 * Verified with a trial patch (issues/concurrency/07-.../fix.diff: close the context outside the monitor, then mark the
 * bundle UNINSTALLED): both tests pass and the xservice module bundle tests still pass (18 tests).
 *
 * <h3>Regression vs WF41</h3>
 * no (5a017edc is on both branches).
 *
 * <h3>Related</h3>
 * concurrency/01, concurrency/02 (other cycles through the ServiceStateImpl monitor of unregisterService), static/06
 * (service listener monitor held while calling foreign code), tests/05 (module bundle lifecycle).
 */
public class ModuleBundleUninstallDeadlockIssueTestCase extends OSGiTest {

    private Framework framework;
    private boolean deadlocked;

    @After
    public void tearDown() throws Exception {
        // A deadlocked framework cannot be stopped; leave it to the JVM exit
        if (framework != null && !deadlocked) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testUninstallWhileUnregisteringUsedService() throws Exception {
        final BundleContext syscontext = startFramework("a");
        final XBundle moduleBundle = installModuleBundle("concurrency07-module-a");
        BundleContext moduleContext = moduleBundle.getBundleContext();

        // S, used by the module bundle
        ServiceRegistration<Runnable> sreg = syscontext.registerService(Runnable.class, new Noop(), null);
        final ServiceReference<Runnable> sref = sreg.getReference();
        if (moduleContext.getService(sref) == null)
            fail("precondition: the module bundle must get S");

        // On UNREGISTERING of S (the unregistering thread holds S's monitor): uninstall the module bundle on another thread
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Thread uninstalling = new Thread(new Runnable() {
            public void run() {
                try {
                    moduleBundle.uninstall();
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "concurrency07-uninstall-1");
        uninstalling.setDaemon(true);
        final AtomicBoolean armed = new AtomicBoolean(true);
        syscontext.addServiceListener(new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                if (event.getType() == ServiceEvent.UNREGISTERING && event.getServiceReference().equals(sref) && armed.getAndSet(false)) {
                    uninstalling.start();
                    waitUntilBlocked(uninstalling);
                }
            }
        });

        Thread unregistering = unregisterThread("concurrency07-unregister", sreg, failure);
        unregistering.start();
        unregistering.join(10000);
        uninstalling.join(1000);
        assertNoHang("uninstall of a module bundle and a concurrent unregister() of a service it uses", uninstalling, unregistering, failure);
    }

    @Test
    public void testRemoveListenerWhileUninstalling() throws Exception {
        final BundleContext syscontext = startFramework("b");
        final XBundle moduleBundle = installModuleBundle("concurrency07-module-b");
        final BundleContext moduleContext = moduleBundle.getBundleContext();

        // The module bundle registered T and a service listener L
        final ServiceRegistration<Runnable> treg = moduleContext.registerService(Runnable.class, new Noop(), null);
        final ServiceListener listener = new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
            }
        };
        moduleContext.addServiceListener(listener, "(objectClass=com.acme.concurrency07.None)");

        // On UNREGISTERING of T (delivered by the uninstall while it holds the module bundle's monitor): remove L through
        // the module bundle's context on another thread, as the module's own code does when it closes a ServiceTracker
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Thread removing = new Thread(new Runnable() {
            public void run() {
                try {
                    moduleContext.removeServiceListener(listener);
                } catch (IllegalStateException ex) {
                    // the context became invalid: fine
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "concurrency07-remove-listener");
        removing.setDaemon(true);
        final AtomicBoolean armed = new AtomicBoolean(true);
        syscontext.addServiceListener(new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                if (event.getType() == ServiceEvent.UNREGISTERING && event.getServiceReference().equals(treg.getReference()) && armed.getAndSet(false)) {
                    removing.start();
                    waitUntilBlocked(removing);
                }
            }
        });

        Thread uninstalling = new Thread(new Runnable() {
            public void run() {
                try {
                    moduleBundle.uninstall();
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "concurrency07-uninstall-2");
        uninstalling.setDaemon(true);
        uninstalling.start();
        uninstalling.join(10000);
        removing.join(1000);
        assertNoHang("uninstall of a module bundle and a concurrent removeServiceListener() through its context", uninstalling, removing, failure);
    }

    private BundleContext startFramework(String name) throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency07-module-deadlock-" + name).getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        return framework.getBundleContext();
    }

    /** Install a bundle that adapts a module, as xservice/AbstractModuleIntegrationTest does */
    private XBundle installModuleBundle(String name) throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(Noop.class);
        VirtualFile virtualFile = toVirtualFile(archive);
        ModuleIdentifier identifier = ModuleIdentifier.fromString(archive.getName());
        ModuleSpec.Builder specBuilder = ModuleSpec.build(identifier);
        specBuilder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(new VirtualFileResourceLoader(virtualFile)));
        specBuilder.addDependency(DependencySpec.createLocalDependencySpec());
        BundleManager bundleManager = framework.adapt(BundleManager.class);
        ServiceContainer serviceContainer = bundleManager.getServiceContainer();
        FrameworkModuleLoader moduleLoader = (FrameworkModuleLoader) serviceContainer.getRequiredService(IntegrationServices.FRAMEWORK_MODULE_LOADER_PLUGIN).getValue();
        moduleLoader.addModuleSpec(Mockito.mock(XBundleRevision.class), specBuilder.create());
        final Module module = moduleLoader.getModuleLoader().loadModule(identifier);

        final BundleContext context = framework.getBundleContext();
        XBundleRevisionBuilder builder = XBundleRevisionBuilderFactory.create(new XBundleRevisionBuilderFactory() {
            @Override
            public XBundleRevision createResource() {
                return new AbstractBundleRevisionAdaptor(context, module);
            }
        });
        XBundleRevision brev = builder.loadFrom(module).getResource();
        OSGiMetaData metadata = OSGiMetaDataProcessor.getOsgiMetaData(brev);
        brev.putAttachment(IntegrationConstants.OSGI_METADATA_KEY, metadata);
        ((XBundle) context.getBundle()).adapt(XEnvironment.class).installResources(brev);
        return brev.getBundle();
    }

    private void assertNoHang(String what, Thread first, Thread second, AtomicReference<Throwable> failure) {
        if (first.isAlive() || second.isAlive()) {
            String deadlock = describeMonitorDeadlock(first.getName(), second.getName());
            deadlocked = true;
            System.out.println(jstack());
            fail(what + " must both return, but after 10 s " + first.getName() + " alive=" + first.isAlive() + ", " + second.getName() + " alive="
                    + second.isAlive() + (deadlock != null ? "; JVM monitor deadlock:" + deadlock : ""));
        }
        if (failure.get() != null)
            throw new AssertionError(what + " failed", failure.get());
    }

    private static void waitUntilBlocked(Thread thread) {
        long deadline = System.currentTimeMillis() + 5000;
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.currentTimeMillis() < deadline)
            Thread.yield();
    }

    private static Thread unregisterThread(String name, final ServiceRegistration<?> reg, final AtomicReference<Throwable> failure) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    reg.unregister();
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    /** The JVM monitor deadlock of the given threads (ThreadMXBean), or null if there is none */
    static String describeMonitorDeadlock(String... names) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.findDeadlockedThreads();
        if (ids == null)
            return null;
        java.util.List<String> wanted = java.util.Arrays.asList(names);
        StringBuilder info = new StringBuilder();
        for (ThreadInfo ti : mx.getThreadInfo(ids, true, true)) {
            if (!wanted.contains(ti.getThreadName()))
                continue;
            info.append("\n  \"").append(ti.getThreadName()).append("\" waits for ").append(ti.getLockName()).append(" held by \"")
                    .append(ti.getLockOwnerName()).append("\" at ").append(ti.getStackTrace()[0]);
        }
        return info.length() > 0 ? info.toString() : null;
    }

    /** A thread dump of the deadlocked threads of this JVM, taken in-process (an external jstack can hang) */
    static String jstack() {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] ids = mx.findDeadlockedThreads();
            StringBuilder sb = new StringBuilder("---- in-process thread dump " + ProcessHandle.current().pid() + " ----\n");
            if (ids == null) {
                sb.append("no Java-level deadlock found\n");
                return sb.toString();
            }
            sb.append("Found one Java-level deadlock:\n");
            for (ThreadInfo ti : mx.getThreadInfo(ids, true, true)) {
                if (ti == null)
                    continue;
                sb.append('"').append(ti.getThreadName()).append("\" ").append(ti.getThreadState())
                        .append(" waiting to lock ").append(ti.getLockName())
                        .append(" held by \"").append(ti.getLockOwnerName()).append("\"\n");
                for (StackTraceElement ste : ti.getStackTrace())
                    sb.append("\tat ").append(ste).append('\n');
            }
            return sb.toString();
        } catch (Exception ex) {
            return "thread dump failed: " + ex;
        }
    }

    public static class Noop implements Runnable {
        public void run() {
        }
    }
}
