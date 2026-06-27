package com.migration.platform.quality;

import com.migration.platform.quality.Pii.Category;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiTest {

    @Test
    void detectsByColumnNameAndValue() {
        assertThat(Pii.detect("email_address", null)).isEqualTo(Category.EMAIL);
        assertThat(Pii.detect("ssn", null)).isEqualTo(Category.SSN);
        assertThat(Pii.detect("last_name", null)).isEqualTo(Category.NAME);
        assertThat(Pii.detect("random", "jane.doe@example.com")).isEqualTo(Category.EMAIL);
        assertThat(Pii.detect("random", "hello world")).isEqualTo(Category.NONE);
    }

    @Test
    void maskingIsIrreversibleButShapePreserving() {
        assertThat(Pii.mask("jane.doe@example.com", Category.EMAIL)).isEqualTo("***@example.com");
        assertThat(Pii.mask("4111111111111111", Category.CREDIT_CARD)).endsWith("1111").startsWith("****");
        assertThat(Pii.mask("Jane", Category.NAME)).isEqualTo("J****");
        assertThat(Pii.mask(null, Category.EMAIL)).isNull();
    }
}
