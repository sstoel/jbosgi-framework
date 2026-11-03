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
package org.jboss.test.osgi.framework.issues.hooks;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.framework.Bundle;
import org.osgi.framework.hooks.bundle.CollisionHook;

/**
 * A collision hook that runs a probe on another thread while Bundle.update of the target bundle is in its uniqueness
 * check, with the lock of the symbolic name held, and waits for it for a few seconds. It allows the collision.
 */
public final class UpdateCollisionProbe implements CollisionHook {

    private final Bundle target;
    private final Callable<String> probe;
    private final long waitMillis;
    private final AtomicReference<String> result = new AtomicReference<String>("the hook was not called for the update");
    private volatile Thread probeThread;

    public UpdateCollisionProbe(Bundle target, long waitMillis, Callable<String> probe) {
        this.target = target;
        this.waitMillis = waitMillis;
        this.probe = probe;
    }

    @Override
    public void filterCollisions(int operationType, Bundle bundle, Collection<Bundle> collisionCandidates) {
        if (operationType == UPDATING && bundle == target && probeThread == null) {
            final AtomicReference<String> outcome = new AtomicReference<String>();
            probeThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        outcome.set(probe.call());
                    } catch (Throwable th) {
                        outcome.set("failed: " + th);
                    }
                }
            }, "update-collision-probe");
            probeThread.setDaemon(true);
            probeThread.start();
            try {
                probeThread.join(waitMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (probeThread.isAlive())
                result.set("blocked while the update is in its uniqueness check: " + probeThread.getState());
            else
                result.set(outcome.get());
        }
        collisionCandidates.clear();
    }

    /** The outcome of the probe as the hook saw it */
    public String getResult() {
        return result.get();
    }

    /** Wait for the probe thread to end, also when it was blocked */
    public void join() throws InterruptedException {
        Thread thread = probeThread;
        if (thread != null)
            thread.join(60000);
    }
}
