package ru.agimate.controlapi.controller.manage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CredentialFieldResponse — mapping объявления поля в ответ API")
class CredentialFieldResponseTest {

    @Test
    @DisplayName("однострочное поле доезжает с multiline=false")
    void mapsSingleLineField() {
        CredentialFieldResponse response = CredentialFieldResponse.from(
                CredentialField.required("Host", CredentialField.Type.TEXT));

        assertEquals("Host", response.label());
        assertEquals(CredentialField.Type.TEXT, response.type());
        assertTrue(response.required());
        assertFalse(response.multiline());
    }

    @Test
    @DisplayName("multiline проставляется из объявления и не меняет остальные компоненты")
    void mapsMultilineField() {
        CredentialFieldResponse response = CredentialFieldResponse.from(
                CredentialField.optional("Private key", CredentialField.Type.SECRET).withMultiline());

        assertEquals("Private key", response.label());
        assertEquals(CredentialField.Type.SECRET, response.type());
        assertFalse(response.required());
        assertTrue(response.multiline());
    }
}
