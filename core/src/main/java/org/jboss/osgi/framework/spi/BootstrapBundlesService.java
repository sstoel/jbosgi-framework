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
package org.jboss.osgi.framework.spi;

import org.jboss.msc.service.LifecycleListener;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;

public abstract class BootstrapBundlesService<T> extends AbstractIntegrationService<T> {

    private final ServiceName baseName;
    private final IntegrationServices.BootstrapPhase phase;
    private LifecycleListener listener;

    public BootstrapBundlesService(ServiceName baseName, IntegrationServices.BootstrapPhase phase) {
        super(IntegrationServices.BootstrapPhase.serviceName(baseName, phase));
        this.baseName = baseName;
        this.phase = phase;
    }

    @Override
    public ServiceController<T> install(ServiceTarget serviceTarget, LifecycleListener listener) {
        this.listener = listener;
        return super.install(serviceTarget, listener);
    }

    protected LifecycleListener getServiceListener() {
        return listener;
    }

    public ServiceName getPreviousService() {
        return IntegrationServices.BootstrapPhase.serviceName(baseName, phase.previous());
    }

    public ServiceName getNextService() {
        return IntegrationServices.BootstrapPhase.serviceName(baseName, phase.next());
    }

    @Override
    protected T createServiceValue(StartContext startContext) throws StartException {
        return null;
    }
}