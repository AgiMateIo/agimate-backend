package ru.agimate.controlapi.connectors.core.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CredentialField — объявление поля ввода")
class CredentialFieldTest {

    @Test
    @DisplayName("обе factory создают однострочное поле")
    void defaultsToSingleLine() {
        assertFalse(CredentialField.required("Key", CredentialField.Type.SECRET).multiline());
        assertFalse(CredentialField.optional("Token", CredentialField.Type.SECRET).multiline());
        assertFalse(CredentialField.required("Host", CredentialField.Type.TEXT).multiline());
    }

    @Test
    @DisplayName("multiline — только раскладка: секретность и хранение не меняются")
    void multilineKeepsSecretStorage() {
        CredentialField field = CredentialField.optional("Private key", CredentialField.Type.SECRET).withMultiline();

        assertTrue(field.multiline());
        assertTrue(field.secret());
        assertFalse(field.opens("-----BEGIN KEY-----\nAAAA\n-----END KEY-----"));
    }

    @Test
    @DisplayName("multiline не обрезает секрет: пробелы и переводы строк значимы")
    void multilineDoesNotTrimSecret() {
        CredentialField field = CredentialField.optional("Private key", CredentialField.Type.SECRET).withMultiline();

        assertEquals(" line1\nline2 ", field.normalize(" line1\nline2 "));
    }
}
