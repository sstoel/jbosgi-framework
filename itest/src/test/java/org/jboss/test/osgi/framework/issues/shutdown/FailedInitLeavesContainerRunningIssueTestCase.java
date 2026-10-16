/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues.shutdown;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.junit.Test;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * A failed {@code Framework.init()} leaves the framework's service container running: its non-daemon "MSC service
 * thread"s keep the launcher JVM alive forever, {@code stop()} does nothing, and {@code waitForStop(3000)} blocks 5 s and
 * returns {@code null}.
 *
 * <h3>Severity</h3>
 * major. A launcher whose framework cannot be initialized (an I/O error or a broken file in the storage area, a
 * storage area left incomplete by connect/24 or connect/07, a framework restarted by {@code Framework.update()} whose
 * init fails, see Found by) reports the BundleException, returns from {@code main} - and the process never exits: an
 * unattended service hangs at boot instead of failing (no restart by the service manager), a CLI tool never returns, a
 * test JVM never ends. The application cannot release the resources either: {@code stop()} on the INSTALLED framework is
 * a no-op and {@code waitForStop} returns {@code null} (a launcher doing {@code waitForStop(0).getType()} gets a
 * NullPointerException after waiting). Every failed init in a long-running JVM leaks one container (8 threads, the class
 * loaders of the framework modules).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkProxy.init} (failure path),
 * {@code FrameworkProxy.waitForStop} / {@code BundleManagerPlugin.waitForStop}, {@code BundleManagerPlugin.shutdownManager}.
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round): {@code Framework.update()} under load ({@code ShutdownExplore2TestCase.f04}: the
 * restart failed reading a half-written bundle-N/bundle-persistent.properties, the framework stayed INSTALLED and a
 * following {@code waitForStop(60000)} blocked 62 s and returned null), then child-JVM launcher experiments
 * ({@code explore/sd-fw/diff/FailedInitExit*.java}): init failing because of an unreadable storage file, a failing module
 * connector activator (connect/03 trigger) or a concurrent stop (f01) - the JVM never exits in all of them.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.launch.Framework.init()} javadoc (osgi.core 8.0.0): "@throws BundleException If this
 * Framework could not be initialized." - the framework stays INSTALLED; it holds nothing it could release later
 * (stop() only acts when STARTING/ACTIVE).<br>
 * {@code Framework.stop()} javadoc: "6. All resources held by this Framework are released. This includes threads, bundle
 * class loaders, open files, etc."<br>
 * {@code Framework.waitForStop(long)} javadoc: "This method will only wait if called when this Framework is in the
 * STARTING, ACTIVE, or STOPPING states. Otherwise it will return immediately. ... @return A Framework Event indicating the
 * reason this method returned."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java} (WF41-osgi-r8 d7b20d08) lines
 * 140-148 create the MSC {@code ServiceContainer} (when the builder has none, i.e. always for the embedded framework,
 * {@code FrameworkBuilderImpl.createServiceContainer}: non-daemon "MSC service thread" pool) and install the framework
 * services; the catch blocks at lines 194-202 only set the manager state to INSTALLED and rethrow: the container is
 * never shut down. {@code stop(int)} (line 276-279) calls {@code BundleManagerPlugin.shutdownManager}, which returns at
 * once unless the state is STARTING/ACTIVE (BundleManagerPlugin.java:935-937), and that method is the only caller of
 * {@code ShutdownContainer.shutdown()} (line 985). {@code waitForStop} (FrameworkProxy.java:291-293,
 * BundleManagerPlugin.java:998-1008) does not look at the state: it waits for the termination of the never-stopped
 * container (2 s grace + timeout) and has no event source (system bundle never injected, cachedSystemBundle null), so it
 * returns {@code null}. Unchanged from WF41.
 *
 * <h3>Root cause</h3>
 * The init failure path does not release what init created (service container, executors); the only release path is
 * the shutdown of a STARTING/ACTIVE framework.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=FailedInitLeavesContainerRunningIssueTestCase
 * </pre>
 * The test starts a launcher in a child JVM ({@code Launcher.main}, class path of the test JVM) on a storage area whose
 * {@code bundle-0/bundle-persistent.properties} cannot be read (a directory in its place; works as root too), so that
 * {@code init()} fails with "JBOSGI011246: Cannot initialize Framework" caused by
 * {@code FileNotFoundException (Is a directory)}; the launcher calls {@code stop()} and {@code waitForStop(3000)} and returns
 * from {@code main}. The parent waits 30 s for the child to exit, takes a {@code jstack} of it if it does not, kills it
 * and puts the non-daemon threads into the assertion message.
 *
 * <h3>Observed</h3>
 * <pre>
 * After a failed Framework.init() the framework must not hold any resources (no threads), stop() and waitForStop() must
 * return at once, and the launcher JVM must exit, but: [the launcher JVM did not exit within 30 s after main returned
 * (killed); non-daemon threads in its thread dump:
 * "MSC service thread 1-2" #38 ... waiting on condition
 *    java.lang.Thread.State: TIMED_WAITING (parking)
 *     - parking to wait for  (a org.jboss.threads.EnhancedQueueExecutor)
 * "MSC service thread 1-3" #39 ... waiting on condition
 * (8 non-daemon threads in total), on the INSTALLED framework waitForStop must return a FrameworkEvent immediately, but:
 * LAUNCHER waitForStop(3000) returned null after 5014 ms]
 * launcher output: [LAUNCHER init failed as expected: org.osgi.framework.BundleException: JBOSGI011246: Cannot initialize
 * Framework caused by java.io.FileNotFoundException: .../storage/bundle-0/bundle-persistent.properties (Is a directory),
 * state 2, LAUNCHER waitForStop(3000) returned null after 5014 ms, LAUNCHER main returns, live non-daemon threads
 * [MSC service thread 1-3, ..., MSC service thread 1-5]]
 * </pre>
 *
 * <h3>Expected</h3>
 * init() fails with the BundleException and releases everything it created; the launcher JVM exits when main returns;
 * {@code waitForStop} on the INSTALLED framework returns a FrameworkEvent (STOPPED) immediately.
 *
 * <h3>Reference behaviour</h3>
 * Same child-JVM launcher with every storage file unreadable ({@code explore/sd-fw/diff/FailedInitExit3.java},
 * {@code failedinit3-results.txt}): Equinox 3.21.0 fails already in {@code newFramework} and the JVM exits; Felix 7.0.5
 * recovers (init succeeds), stop + waitForStop 1 ms, JVM exits; jbosgi: JVM did not exit within 20 s. Felix shares a
 * similar leak when a ModuleConnector's {@code initialize} throws (FelixDispatchQueue thread left,
 * {@code failedinit-results.txt}).
 *
 * <h3>Suggested fix</h3>
 * In the catch blocks of {@code FrameworkProxy.init} release what init created: shut down the framework's executors and,
 * when the framework created the container ({@code allowContainerShutdown}), shut the container down (and keep the system
 * bundle as event source); in {@code waitForStop} return a STOPPED event immediately when the framework is neither
 * STARTING, ACTIVE nor STOPPING. Verified as a trial fix (d7b20d08 + {@code BundleManagerPlugin.releaseAfterFailedInit()}
 * called from both catch blocks + the state check in {@code FrameworkProxy.waitForStop}): the test passes in 0.25 s and
 * all {@code org.jboss.test.osgi.framework.launch} tests still pass (60 tests).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the test fails the same way on origin/WF41 5a017edc: JVM did not exit, waitForStop null after 5012 ms).<br>
 * Related: connect/24 and connect/07 (incomplete/cleaned bundle storage makes init fail) and connect/03 (a failing
 * connector activator fails init) are triggers of this defect; their fixes remove those triggers but not the leak on the
 * remaining init failures (I/O errors, unreadable files, the system bundle's storage), which is fixed only here.
 */
public class FailedInitLeavesContainerRunningIssueTestCase {

    /**
     * A launcher in its own JVM: Framework.init() fails, the launcher reports the error, calls stop() and
     * waitForStop() like a careful launcher would, and returns from main. The JVM must exit.
     */
    public static class Launcher {
        public static void main(String[] args) throws Exception {
            File storage = new File(args[0]);
            Map<String, String> props = new HashMap<String, String>();
            props.put(Constants.FRAMEWORK_STORAGE, storage.getAbsolutePath());
            Framework framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
            try {
                framework.init();
                System.out.println("LAUNCHER init returned normally, state " + framework.getState());
            } catch (BundleException ex) {
                System.out.println("LAUNCHER init failed as expected: " + ex + " caused by " + ex.getCause() + ", state " + framework.getState());
            }
            framework.stop();
            long start = System.nanoTime();
            FrameworkEvent event = framework.waitForStop(3000);
            long elapsed = (System.nanoTime() - start) / 1000000;
            System.out.println("LAUNCHER waitForStop(3000) returned " + (event != null ? "event type " + event.getType() : "null") + " after " + elapsed + " ms");
            List<String> threads = new ArrayList<String>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && !thread.isDaemon() && thread != Thread.currentThread())
                    threads.add(thread.getName());
            }
            System.out.println("LAUNCHER main returns, live non-daemon threads " + threads);
        }
    }

    @Test
    public void testJvmExitsAfterFailedInit() throws Exception {
        // A storage area that the framework cannot read: the system bundle's persistent properties are unreadable
        // (here: a directory took the place of the file, e.g. after a broken restore; a file without read
        // permission or an I/O error has the same effect)
        File storage = new File("target/issues-shutdown/failed-init/storage").getAbsoluteFile();
        deleteRecursive(storage);
        File properties = new File(storage, "bundle-0/bundle-persistent.properties");
        assertTrue(properties.mkdirs());

        File output = new File("target/issues-shutdown/failed-init/launcher.out").getAbsoluteFile();
        List<String> command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
        command.add(Launcher.class.getName());
        command.add(storage.getPath());
        Process launcher = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
        boolean exited = launcher.waitFor(30, TimeUnit.SECONDS);
        String dump = "";
        if (!exited) {
            // The thread dump of the launcher JVM shows what keeps it alive
            File jstack = new File("target/issues-shutdown/failed-init/launcher-jstack.txt").getAbsoluteFile();
            Process js = new ProcessBuilder(System.getProperty("java.home") + File.separator + "bin" + File.separator + "jstack", Long.toString(launcher.pid()))
                    .redirectErrorStream(true).redirectOutput(jstack).start();
            if (js.waitFor(30, TimeUnit.SECONDS))
                dump = nonDaemonThreads(new String(Files.readAllBytes(jstack.toPath()), StandardCharsets.UTF_8));
            launcher.destroyForcibly();
            launcher.waitFor(10, TimeUnit.SECONDS);
        }
        List<String> lines = new ArrayList<String>();
        for (String line : Files.readAllLines(output.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("LAUNCHER"))
                lines.add(line);
        }
        System.out.println(lines);

        if (!lines.toString().contains("init failed as expected"))
            fail("The test setup must make Framework.init() fail, but the launcher printed " + lines);
        String waitForStop = null;
        for (String line : lines)
            if (line.contains("waitForStop"))
                waitForStop = line;
        List<String> problems = new ArrayList<String>();
        if (!exited)
            problems.add("the launcher JVM did not exit within 30 s after main returned (killed); non-daemon threads in its thread dump:\n" + dump);
        if (waitForStop == null || waitForStop.contains("returned null") || !waitForStop.matches(".* after [0-9]{1,3} ms"))
            problems.add("on the INSTALLED framework waitForStop must return a FrameworkEvent immediately, but: " + waitForStop);
        assertTrue("After a failed Framework.init() the framework must not hold any resources (no threads), stop() and waitForStop() must return at once, and the launcher JVM must exit, but: "
                + problems + "\nlauncher output: " + lines, problems.isEmpty());
    }

    private static String nonDaemonThreads(String jstack) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String block : jstack.split("\n\n")) {
            String first = block.trim().split("\n")[0];
            if (first.startsWith("\"") && !first.contains(" daemon ") && !first.startsWith("\"VM ") && !first.startsWith("\"GC ") && !first.startsWith("\"G1 ")
                    && !first.startsWith("\"DestroyJavaVM")) {
                if (count++ < 2) {
                    String[] frames = block.trim().split("\n");
                    for (int i = 0; i < Math.min(frames.length, 5); i++)
                        sb.append(frames[i]).append('\n');
                }
            }
        }
        return sb.append("(").append(count).append(" non-daemon threads in total)").toString();
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null)
            for (File child : children)
                deleteRecursive(child);
        file.delete();
    }
}
