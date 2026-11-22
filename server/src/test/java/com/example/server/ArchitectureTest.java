package com.example.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;

@AnalyzeClasses(
    packages = "com.example.server",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule controllersUseApplicationServices =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..mapper..", "..repository..");

  @ArchTest
  static final ArchRule servicesAndPersistenceDoNotDependOnHttp =
      noClasses()
          .that()
          .resideInAnyPackage("..service..", "..repository..", "..mapper..", "..utils..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..controller..");

  @ArchTest
  static final ArchRule dataTypesDoNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAnyPackage("..dto..", "..entity..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..controller..", "..service..", "..repository..", "..mapper..");

  @ArchTest
  static final ArchRule dependenciesAreConstructorInjected =
      noFields().should().beAnnotatedWith(Autowired.class);
}
