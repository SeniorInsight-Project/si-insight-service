package com.seniorinsight.insight;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.seniorinsight.insight", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String DOMAIN = "com.seniorinsight.insight.domain..";
    private static final String APPLICATION = "com.seniorinsight.insight.application..";
    private static final String ADAPTERS = "com.seniorinsight.insight.adapters..";
    private static final String ADAPTERS_IN = "com.seniorinsight.insight.adapters.in..";
    private static final String ADAPTERS_OUT = "com.seniorinsight.insight.adapters.out..";

    @ArchTest
    static final ArchRule dominioNaoDependeDeSpringNemJpa = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .because("o domínio é Java puro");

    @ArchTest
    static final ArchRule dominioNaoDependeDeApplicationNemAdapters = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(APPLICATION, ADAPTERS);

    @ArchTest
    static final ArchRule applicationNaoDependeDeAdapters = noClasses()
            .that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAPackage(ADAPTERS);

    @ArchTest
    static final ArchRule adaptersInNaoDependeDeAdaptersOut = noClasses()
            .that().resideInAPackage(ADAPTERS_IN)
            .should().dependOnClassesThat().resideInAPackage(ADAPTERS_OUT);

    @ArchTest
    static final ArchRule adaptersOutNaoDependeDeAdaptersIn = noClasses()
            .that().resideInAPackage(ADAPTERS_OUT)
            .should().dependOnClassesThat().resideInAPackage(ADAPTERS_IN);
}
