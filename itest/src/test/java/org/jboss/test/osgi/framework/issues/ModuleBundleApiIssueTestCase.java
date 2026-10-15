/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.security.AllPermission;
import java.util.Collections;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.osgi.framework.spi.AbstractBundleRevisionAdaptor;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.VirtualFileResourceLoader;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XBundleRevisionBuilderFactory;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

/**
 * A module bundle (AbstractBundleAdaptor, as installed by the WildFly subsystem for module capabilities and module
 * deployments) answers hasPermission with false although the platform does not support permissions, and
 * getSignerCertificates does not reject an invalid signers type.
 *
 * <h2>Severity</h2>
 * minor (code that guards an action with Bundle.hasPermission, e.g. a whiteboard or management agent, refuses module
 * bundles; ordinary bundles of the same framework return true).
 *
 * <h2>Component</h2>
 * jbosgi-framework, module core, class org.jboss.osgi.framework.spi.AbstractBundleAdaptor.
 *
 * <h2>Found by</h2>
 * Tests workstream, round 3 coverage follow-up of the adaptor SPI (hasPermission and getSignerCertificates have zero
 * coverage in the itest and the TCK).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, Bundle.hasPermission(Object) javadoc: "If the Java Runtime Environment does not support permissions,
 * this method always returns true." (JDK 25: the Security Manager is permanently disabled, JEP 486, and
 * System.getSecurityManager() is null.) Bundle.getSignerCertificates(int) javadoc: "Throws: IllegalArgumentException -
 * If the specified signersType is not SIGNERS_ALL or SIGNERS_TRUSTED."
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/spi/AbstractBundleAdaptor.java:406-408 ({@code hasPermission} returns
 * {@code false} unconditionally) and :460-462 ({@code getSignerCertificates} returns an empty map for any type).
 * Ordinary bundles do it right: internal/AbstractBundleState.java:354-360 returns true when there is no security
 * manager. Pre-existing on WF41.
 *
 * <h2>Root cause</h2>
 * Stub implementations in the adaptor SPI.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ModuleBundleApiIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full only works around tests/01, LoggerImplementationMissingIssueTestCase.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testHasPermissionWithoutSecurityManager: Expected hasPermission(AllPermission) of a module bundle to be true
 *   without a security manager, but it was false expected:&lt;true&gt; but was:&lt;false&gt;
 * testSignerCertificatesInvalidType: Expected IllegalArgumentException from getSignerCertificates(3) of a module
 *   bundle, but it returned normally
 * </pre>
 *
 * <h2>Expected</h2>
 * hasPermission(AllPermission) is true without a security manager (as for the ordinary bundles of the same framework);
 * getSignerCertificates(3) throws IllegalArgumentException.
 *
 * <h2>Reference behaviour</h2>
 * jbosgi's own ordinary bundles and the Framework object (FrameworkProxy) behave as expected; Equinox/Felix
 * hasPermission returns true without a security manager.
 *
 * <h2>Suggested fix</h2>
 * Implement AbstractBundleAdaptor.hasPermission like AbstractBundleState.hasPermission (false for a non-Permission,
 * true without a security manager, otherwise check the module's protection domain) and validate signersType like
 * FrameworkProxy.getSignerCertificates.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; this test class fails with the same 2 assertions against origin/WF41).
 */
public class ModuleBundleApiIssueTestCase extends OSGiFrameworkTest {

    private Module module;
    private VirtualFile virtualFile;
    private XBundleRevision brev;

    @After
    public void tearDown() throws Exception {
        if (brev != null) {
            XBundle bundle = brev.getBundle();
            if (bundle.getState() != Bundle.UNINSTALLED)
                bundle.uninstall();
            ((XBundle) getSystemContext().getBundle()).adapt(XEnvironment.class).uninstallResources(brev);
        }
        if (module != null) {
            XBundleRevision mock = Mockito.mock(XBundleRevision.class);
            Mockito.when(mock.getModuleIdentifier()).thenReturn(module.getIdentifier());
            getModuleLoader().removeModule(mock);
        }
        VFSUtils.safeClose(virtualFile);
        super.tearDown();
    }

    private FrameworkModuleLoader getModuleLoader() throws Exception {
        BundleManager bundleManager = getFramework().adapt(BundleManager.class);
        return (FrameworkModuleLoader) bundleManager.getServiceContainer().getRequiredService(IntegrationServices.FRAMEWORK_MODULE_LOADER_PLUGIN).getValue();
    }

    /** Install a module bundle with an activator, like the WildFly subsystem installs module resources */
    private XBundle installModuleBundle(String name) throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(ModuleBundleApiIssueTestCase.class);
        virtualFile = toVirtualFile(archive);
        ModuleIdentifier identifier = ModuleIdentifier.fromString(name);
        ModuleSpec.Builder specBuilder = ModuleSpec.build(identifier);
        specBuilder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(new VirtualFileResourceLoader(virtualFile)));
        specBuilder.addDependency(DependencySpec.createSystemDependencySpec(Collections.singleton("org/osgi/framework")));
        specBuilder.addDependency(DependencySpec.createLocalDependencySpec());
        FrameworkModuleLoader moduleLoader = getModuleLoader();
        moduleLoader.addModuleSpec(Mockito.mock(XBundleRevision.class), specBuilder.create());
        module = moduleLoader.getModuleLoader().loadModule(identifier);

        final BundleContext context = getSystemContext();
        XBundleRevisionBuilderFactory factory = new XBundleRevisionBuilderFactory() {
            @Override
            public XBundleRevision createResource() {
                return new AbstractBundleRevisionAdaptor(context, module);
            }
        };
        OSGiMetaDataBuilder metaBuilder = OSGiMetaDataBuilder.createBuilder(name, Version.parseVersion("1.0"));
        metaBuilder.addImportPackages("org.osgi.framework");
        OSGiMetaData metadata = metaBuilder.getOSGiMetaData();
        brev = XBundleRevisionBuilderFactory.create(factory).loadFrom(metadata).getResource();
        brev.putAttachment(IntegrationConstants.OSGI_METADATA_KEY, metadata);
        ((XBundle) context.getBundle()).adapt(XEnvironment.class).installResources(brev);
        return brev.getBundle();
    }

    @Test
    public void testHasPermissionWithoutSecurityManager() throws Exception {
        XBundle bundle = installModuleBundle("module-bundle-permission");
        Bundle ordinary = getSystemContext().getBundle(0);
        boolean expected = System.getSecurityManager() == null;
        assertEquals("Precondition: an ordinary bundle answers " + expected, expected, ordinary.hasPermission(new AllPermission()));
        assertEquals("Expected hasPermission(AllPermission) of a module bundle to be " + expected + " without a security manager, but it was "
                + bundle.hasPermission(new AllPermission()), expected, bundle.hasPermission(new AllPermission()));
    }

    @Test
    public void testSignerCertificatesInvalidType() throws Exception {
        XBundle bundle = installModuleBundle("module-bundle-signers");
        try {
            bundle.getSignerCertificates(3);
            fail("Expected IllegalArgumentException from getSignerCertificates(3) of a module bundle, but it returned normally");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }
}
