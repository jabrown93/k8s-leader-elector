package io.jaredbrown.k8s.leader.elector;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectorPropertiesTest {

    private Validator validator;
    private ValidatorFactory validatorFactory;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void shouldHaveDefaultValues() {
        // When
        final ElectorProperties properties = new ElectorProperties();

        // Then
        assertEquals(Duration.ofSeconds(120), properties.getLeaseDuration());
        assertEquals(Duration.ofSeconds(60), properties.getRenewDeadline());
        assertEquals(Duration.ofSeconds(5), properties.getRetryPeriod());
    }

    @Test
    void shouldAllowSettingAllProperties() {
        // Given
        final ElectorProperties properties = new ElectorProperties();

        // When
        properties.setLabelKey("my-label");
        properties.setLockName("my-lock");
        properties.setAppName("my-app");
        properties.setLeaseDuration(Duration.ofSeconds(180));
        properties.setRenewDeadline(Duration.ofSeconds(90));
        properties.setRetryPeriod(Duration.ofSeconds(10));

        // Then
        assertEquals("my-label", properties.getLabelKey());
        assertEquals("my-lock", properties.getLockName());
        assertEquals("my-app", properties.getAppName());
        assertEquals(Duration.ofSeconds(180), properties.getLeaseDuration());
        assertEquals(Duration.ofSeconds(90), properties.getRenewDeadline());
        assertEquals(Duration.ofSeconds(10), properties.getRetryPeriod());
    }

    @Test
    void shouldFailValidationWhenLabelKeyIsNull() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey(null);
        properties.setLockName("test-lock");
        properties.setAppName("test-app");

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("labelKey")));
    }

    @Test
    void shouldFailValidationWhenLabelKeyIsBlank() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("  ");
        properties.setLockName("test-lock");
        properties.setAppName("test-app");

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("labelKey")));
    }

    @Test
    void shouldFailValidationWhenLockNameIsNull() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName(null);
        properties.setAppName("test-app");

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("lockName")));
    }

    @Test
    void shouldFailValidationWhenLockNameIsBlank() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("");
        properties.setAppName("test-app");

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("lockName")));
    }

    @Test
    void shouldFailValidationWhenAppNameIsNull() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setAppName(null);

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("appName")));
    }

    @Test
    void shouldFailValidationWhenAppNameIsBlank() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setAppName("   ");

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("appName")));
    }

    @Test
    void shouldFailValidationWhenLeaseDurationIsNull() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setAppName("test-app");
        properties.setLeaseDuration(null);

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("leaseDuration")));
    }

    @Test
    void shouldFailValidationWhenRenewDeadlineIsNull() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setAppName("test-app");
        properties.setRenewDeadline(null);

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("renewDeadline")));
    }

    @Test
    void shouldFailValidationWhenRetryPeriodIsNull() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setAppName("test-app");
        properties.setRetryPeriod(null);

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("retryPeriod")));
    }

    @Test
    void shouldPassValidationWhenAllRequiredFieldsAreSet() {
        // Given
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setAppName("test-app");

        // When
        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        // Then
        assertTrue(violations.isEmpty());
    }
}
