/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.test.osgi.modules;

import static org.junit.Assert.fail;

import javax.management.MBeanServer;

import org.jboss.modules.LocalDependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleDependencySpec;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.PathFilters;
import org.jboss.osgi.container.loading.SystemLocalLoader;
import org.jboss.osgi.container.loading.VirtualFileResourceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.modules.a.A;
import org.jboss.test.osgi.modules.b.B;
import org.jboss.test.osgi.modules.c.C;
import org.jboss.test.osgi.modules.d.D;
import org.junit.Test;

/**
 * Test low level modules use cases.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 15-Sep-2010
 */
public class ModulesTestCase extends ModulesTestBase
{
   @Test
   public void testNoResourceRoot() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      addModuleSpec(specBuilderA.create());

      assertLoadClassFails(identifierA, A.class.getName());
      assertLoadClassFails(identifierA, B.class.getName());
   }

   @Test
   public void testLocalDependency() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      assertLoadClass(identifierA, A.class.getName());
      assertLoadClass(identifierA, B.class.getName());
   }

   @Test
   public void testExportFilter() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA), getPathFilter(A.class)));
      addModuleSpec(specBuilderA.create());

      assertLoadClass(identifierA, A.class.getName());

      // B can be loaded from moduleA, even though there is an export filter on the ResouceLoader
      assertLoadClass(identifierA, B.class.getName());
   }

   @Test
   public void testDependencyNotWired() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      JavaArchive archiveB = getModuleB();
      ModuleIdentifier identifierB = ModuleIdentifier.create(archiveB.getName());
      ModuleSpec.Builder specBuilderB = ModuleSpec.build(identifierB);
      specBuilderB.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveB)));
      addModuleSpec(specBuilderB.create());

      assertLoadClass(identifierA, A.class.getName());
      assertLoadClass(identifierA, B.class.getName());

      try
      {
         // [TODO] Is it correct to get a NoClassDefFoundError instead of ClassNotFoundException
         Module.loadClass(identifierB, C.class.getName());
         fail("NoClassDefFoundError expected");
      }
      catch (NoClassDefFoundError er)
      {
         // expected
      }

      assertLoadClass(identifierB, D.class.getName());
   }

   @Test
   public void testDependencyWiredNoFilters() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      JavaArchive archiveB = getModuleB();
      ModuleIdentifier identifierB = ModuleIdentifier.create(archiveB.getName());
      ModuleSpec.Builder specBuilderB = ModuleSpec.build(identifierB);
      specBuilderB.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveB)));

      ModuleDependencySpec.Builder moduleDependency = ModuleDependencySpec.build(identifierA);
      specBuilderB.addModuleDependency(moduleDependency.create());
      addModuleSpec(specBuilderB.create());

      assertLoadClass(identifierA, A.class.getName());
      assertLoadClass(identifierA, B.class.getName());

      assertLoadClass(identifierB, A.class.getName());
      assertLoadClass(identifierB, C.class.getName());
   }

   @Test
   public void testDependencyTwoExportersNotWired() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      JavaArchive archiveC = getModuleC();
      ModuleIdentifier identifierC = ModuleIdentifier.create(archiveC.getName());
      ModuleSpec.Builder specBuilderC = ModuleSpec.build(identifierC);
      specBuilderC.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveC)));

      addModuleSpec(specBuilderC.create());

      assertLoadClass(identifierA, A.class.getName(), identifierA);
      assertLoadClass(identifierC, A.class.getName(), identifierC);
   }

   @Test
   public void testDependencyHidesLocal() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      JavaArchive archiveC = getModuleC();
      ModuleIdentifier identifierC = ModuleIdentifier.create(archiveC.getName());
      ModuleSpec.Builder specBuilderC = ModuleSpec.build(identifierC);
      specBuilderC.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveC)));

      ModuleDependencySpec.Builder moduleDependency = ModuleDependencySpec.build(identifierA);
      specBuilderC.addModuleDependency(moduleDependency.create());
      addModuleSpec(specBuilderC.create());

      assertLoadClass(identifierA, A.class.getName());
      assertLoadClass(identifierA, B.class.getName());

      // moduleC also contains A, which however should be loaded from moduleA
      assertLoadClass(identifierC, A.class.getName(), identifierA);
      assertLoadClass(identifierC, B.class.getName());
      assertLoadClass(identifierC, C.class.getName());
   }

   @Test
   public void testDependencyExportFilter() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA), getPathFilter(A.class)));
      addModuleSpec(specBuilderA.create());

      JavaArchive archiveB = getModuleB();
      ModuleIdentifier identifierB = ModuleIdentifier.create(archiveB.getName());
      ModuleSpec.Builder specBuilderB = ModuleSpec.build(identifierB);
      specBuilderB.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveB)));

      ModuleDependencySpec.Builder moduleDependency = ModuleDependencySpec.build(identifierA);
      specBuilderB.addModuleDependency(moduleDependency.create());
      addModuleSpec(specBuilderB.create());

      assertLoadClass(identifierA, A.class.getName());
      assertLoadClass(identifierA, B.class.getName());

      // moduleA has an export filter on A, B is not visible
      assertLoadClass(identifierB, A.class.getName(), identifierA);
      assertLoadClassFails(identifierB, B.class.getName());

      assertLoadClass(identifierB, C.class.getName());
      assertLoadClass(identifierB, D.class.getName());
   }

   @Test
   public void testDependencyImportFilter() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      JavaArchive archiveB = getModuleB();
      ModuleIdentifier identifierB = ModuleIdentifier.create(archiveB.getName());
      ModuleSpec.Builder specBuilderB = ModuleSpec.build(identifierB);
      specBuilderB.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveB)));

      ModuleDependencySpec.Builder moduleDependency = ModuleDependencySpec.build(identifierA);
      // [TODO] Explain the semantics of the export filter on the dependency
      moduleDependency.setImportFilter(getPathFilter(A.class));
      specBuilderB.addModuleDependency(moduleDependency.create());
      addModuleSpec(specBuilderB.create());

      assertLoadClass(identifierA, A.class.getName());
      assertLoadClass(identifierA, B.class.getName());

      // The dependency on moduleA has an import filter on A, B is not visible
      assertLoadClass(identifierB, A.class.getName(), identifierA);
      assertLoadClassFails(identifierB, B.class.getName());

      assertLoadClass(identifierB, C.class.getName());
      assertLoadClass(identifierB, D.class.getName());
   }

   @Test
   public void testSystemLocalLoader() throws Exception
   {
      ModuleIdentifier systemid = ModuleIdentifier.create("jbosgi.system");
      ModuleSpec.Builder systemBuilder = ModuleSpec.build(systemid);
      // [TODO] Explain why LocalLoader cannot provide it's local paths i.e. Set<String> LocalLoader.getLocalPaths()
      // Instead, these need to be given explicitly to LocalDependencySpec.build(LocalLoader, Set<String>)
      SystemLocalLoader localLoader = new SystemLocalLoader(getFilterPaths(A.class, MBeanServer.class));
      LocalDependencySpec.Builder localDependency = LocalDependencySpec.build(localLoader, localLoader.getLoaderPaths());
      // [TODO] Explain the semantics of export/import filters on the local dependency
      // Note, both need to be defined otherwise we get a NPE
      localDependency.setExportFilter(PathFilters.rejectAll());
      localDependency.setImportFilter(PathFilters.acceptAll());
      systemBuilder.addLocalDependency(localDependency.create());
      addModuleSpec(systemBuilder.create());

      assertLoadClass(systemid, A.class.getName());
      assertLoadClass(systemid, MBeanServer.class.getName());
   }

   @Test
   public void testSystemLocalLoaderWithDependency() throws Exception
   {
      JavaArchive archiveA = getModuleA();
      ModuleIdentifier identifierA = ModuleIdentifier.create(archiveA.getName());
      ModuleSpec.Builder specBuilderA = ModuleSpec.build(identifierA);
      specBuilderA.addResourceRoot(new VirtualFileResourceLoader(toVirtualFile(archiveA)));
      addModuleSpec(specBuilderA.create());

      ModuleIdentifier systemid = ModuleIdentifier.create("jbosgi.system");
      ModuleSpec.Builder systemBuilder = ModuleSpec.build(systemid);

      ModuleDependencySpec.Builder moduleDependency = ModuleDependencySpec.build(identifierA);
      systemBuilder.addModuleDependency(moduleDependency.create());

      SystemLocalLoader localLoader = new SystemLocalLoader(getFilterPaths(A.class, MBeanServer.class));
      LocalDependencySpec.Builder localDependency = LocalDependencySpec.build(localLoader, localLoader.getLoaderPaths());
      localDependency.setExportFilter(PathFilters.rejectAll());
      localDependency.setImportFilter(PathFilters.acceptAll());
      systemBuilder.addLocalDependency(localDependency.create());

      addModuleSpec(systemBuilder.create());

      assertLoadClass(systemid, A.class.getName(), identifierA);
      assertLoadClass(systemid, MBeanServer.class.getName());
   }

   private JavaArchive getModuleA()
   {
      JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "moduleA");
      archive.addClasses(A.class, B.class);
      return archive;
   }

   private JavaArchive getModuleB()
   {
      JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "moduleB");
      archive.addClasses(C.class, D.class);
      return archive;
   }

   private JavaArchive getModuleC()
   {
      JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "moduleC");
      archive.addClasses(A.class, C.class);
      return archive;
   }
}
