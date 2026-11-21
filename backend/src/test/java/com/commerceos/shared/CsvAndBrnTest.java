package com.commerceos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.identity.business.BusinessRegistrationNumber;
import com.commerceos.shared.error.ApiException;
import com.commerceos.support.Fixtures;
import org.junit.jupiter.api.Test;

class CsvAndBrnTest {
    @Test
    void brnChecksum() {
        for (int i = 0; i < 50; i++) {
            String v = Fixtures.validBrn();
            assertThat(BusinessRegistrationNumber.normalize(v)).isEqualTo(v);
        }
        assertThatThrownBy(() -> BusinessRegistrationNumber.normalize("1234567890")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BusinessRegistrationNumber.normalize("12345")).isInstanceOf(ApiException.class);
    }
}
