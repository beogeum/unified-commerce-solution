package com.commerceos.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** 모듈 경계 규칙(일부). 모의 연동은 커넥터 SPI 뒤에만 있고, 암호화 원시 API 는 shared.crypto 에서만 쓴다. */
class ArchitectureTest {
    static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.commerceos");

    @Test
    void mockConnectors_notReferencedOutsideMockPackage() {
        noClasses().that().resideOutsideOfPackage("..integration.mock..")
                .should().dependOnClassesThat().resideInAPackage("..integration.mock..").check(MAIN);
    }

    @Test
    void cryptoPrimitives_onlyInSharedCrypto() {
        noClasses().that().resideOutsideOfPackage("..shared.crypto..")
                .should().dependOnClassesThat().resideInAPackage("javax.crypto..").check(MAIN);
    }

    @Test
    void channelAndLogistics_doNotDependOnIntegrationInternals() {
        noClasses().that().resideInAnyPackage("..channel..", "..logistics..", "..identity..")
                .should().dependOnClassesThat().resideInAPackage("..integration..").check(MAIN);
    }
}
