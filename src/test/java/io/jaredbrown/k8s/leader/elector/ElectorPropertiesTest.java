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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        final ElectorProperties properties = new ElectorProperties();

        assertEquals(Duration.ofSeconds(120), properties.getLeaseDuration());
        assertEquals(Duration.ofSeconds(60), properties.getRenewDeadline());
        assertEquals(Duration.ofSeconds(5), properties.getRetryPeriod());
    }

    @Test
    void shouldHaveHealthProbeDisabledByDefault() {
        final ElectorProperties properties = new ElectorProperties();

        // Then — disabled with safe defaults so probe-less consumers are unaffected
        assertFalse(properties.isHealthProbeEnabled());
        assertNull(properties.getHealthProbeFilePath());
        assertEquals("healthy", properties.getHealthProbeHealthyContent());
        assertEquals(Duration.ofMinutes(2), properties.getHealthProbeMaxAge());
        assertEquals(3, properties.getHealthProbeFailureThreshold());
        assertEquals(Duration.ofMinutes(5), properties.getHealthProbeDeadlockGrace());
        assertEquals(Duration.ofSeconds(30), properties.getHealthProbeUnhealthyBackoff());
    }

    @Test
    void shouldAllowSettingAllProperties() {
        final ElectorProperties properties = new ElectorProperties();

        properties.setLabelKey("my-label");
        properties.setLockName("my-lock");
        properties.setSelectorLabelKey("app");
        properties.setSelectorLabelValue("my-app");
        properties.setLeaseDuration(Duration.ofSeconds(180));
        properties.setRenewDeadline(Duration.ofSeconds(90));
        properties.setRetryPeriod(Duration.ofSeconds(10));

        assertEquals("my-label", properties.getLabelKey());
        assertEquals("my-lock", properties.getLockName());
        assertEquals("app", properties.getSelectorLabelKey());
        assertEquals("my-app", properties.getSelectorLabelValue());
        assertEquals(Duration.ofSeconds(180), properties.getLeaseDuration());
        assertEquals(Duration.ofSeconds(90), properties.getRenewDeadline());
        assertEquals(Duration.ofSeconds(10), properties.getRetryPeriod());
    }

    @Test
    void shouldFailValidationWhenLabelKeyIsNull() {
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey(null);
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("test-app");

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

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
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("  ");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("test-app");

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

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
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName(null);
        properties.setSelectorLabelValue("test-app");

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

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
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("");
        properties.setSelectorLabelValue("test-app");

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

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
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue(null);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("selectorLabelValue")));
    }

    @Test
    void shouldFailValidationWhenAppNameIsBlank() {
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("   ");

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("selectorLabelValue")));
    }

    @Test
    void shouldFailValidationWhenLeaseDurationIsNull() {
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("test-app");
        properties.setLeaseDuration(null);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

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
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("test-app");
        properties.setRenewDeadline(null);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

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
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("test-app");
        properties.setRetryPeriod(null);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("retryPeriod")));
    }

    @Test
    void shouldFailValidationWhenHealthProbeUnhealthyBackoffIsNull() {
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelValue("test-app");
        properties.setHealthProbeUnhealthyBackoff(null);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertFalse(violations.isEmpty());
        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("healthProbeUnhealthyBackoff")));
    }

    @Test
    void shouldFailValidationWhenLeaseDurationIsZero() {
        final ElectorProperties properties = validProperties();
        properties.setLeaseDuration(Duration.ZERO);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("leaseDuration")));
    }

    @Test
    void shouldFailValidationWhenRenewDeadlineIsNegative() {
        final ElectorProperties properties = validProperties();
        properties.setRenewDeadline(Duration.ofSeconds(-1));

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("renewDeadline")));
    }

    @Test
    void shouldFailValidationWhenRetryPeriodIsZero() {
        // Given: a zero retry period would spin lockLoop in a tight busy-loop against Redis.
        final ElectorProperties properties = validProperties();
        properties.setRetryPeriod(Duration.ZERO);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("retryPeriod")));
    }

    @Test
    void shouldFailValidationWhenHealthProbeUnhealthyBackoffIsZero() {
        final ElectorProperties properties = validProperties();
        properties.setHealthProbeUnhealthyBackoff(Duration.ZERO);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("healthProbeUnhealthyBackoff")));
    }

    @Test
    void shouldFailValidationWhenHealthProbeDeadlockGraceIsNegative() {
        final ElectorProperties properties = validProperties();
        properties.setHealthProbeDeadlockGrace(Duration.ofSeconds(-1));

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations
                           .stream()
                           .anyMatch(v -> v
                                   .getPropertyPath()
                                   .toString()
                                   .equals("healthProbeDeadlockGrace")));
    }

    @Test
    void shouldPassValidationWhenHealthProbeDeadlockGraceIsZero() {
        // Given: zero is a deliberate, tested value (breaks the deadlock immediately) - must remain
        // legal even though negative values (which behave identically) are now rejected.
        final ElectorProperties properties = validProperties();
        properties.setHealthProbeDeadlockGrace(Duration.ZERO);

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations.isEmpty());
    }

    private static ElectorProperties validProperties() {
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelKey("app");
        properties.setSelectorLabelValue("test-app");
        return properties;
    }

    @Test
    void shouldPassValidationWhenAllRequiredFieldsAreSet() {
        final ElectorProperties properties = new ElectorProperties();
        properties.setLabelKey("test-label");
        properties.setLockName("test-lock");
        properties.setSelectorLabelKey("app");
        properties.setSelectorLabelValue("test-app");

        final Set<ConstraintViolation<ElectorProperties>> violations = validator.validate(properties);

        assertTrue(violations.isEmpty());
    }
}
