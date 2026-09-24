package com.yeremitech.mailplatform.api.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CampaignCsvParserTest {
    private static ByteArrayInputStream csv(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void supportsBomQuotedCommaNewlineAndVariables() throws Exception {
        var recipients = CampaignCsvParser.parse(csv("\uFEFFemail,display_name,var_name,var_city\r\n"
                + "ana@example.test,\"Ana, Pérez\",Ana,\"Lima\nPerú\"\r\n"
                + "beto@example.test,Beto,Beto,Arequipa\r\n"), false);
        assertEquals(2, recipients.size());
        assertEquals("Ana, Pérez", recipients.getFirst().displayName());
        assertEquals("Lima\nPerú", recipients.getFirst().variables().get("city"));
    }

    @Test void rejectsDuplicateAndInvalidAddressBeforeStaging() {
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(
                "email,display_name\na@example.test,A\nA@example.test,B"), false));
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(
                "email,display_name\nnot-an-email,A"), false));
    }

    @Test void marketingRequiresExplicitIndividualConsent() {
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(
                "email,display_name\na@example.test,A"), true));
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(
                "email,display_name,consent\na@example.test,A,false"), true));
        assertEquals(1, assertDoesNotThrow(() -> CampaignCsvParser.parse(csv(
                "email,display_name,consent\na@example.test,A,true"), true)).size());
    }

    @Test void rejectsMalformedCsvUnexpectedColumnsAndInvalidUtf8() {
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(
                "email,display_name,unknown\na@example.test,A,B"), false));
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(
                "email,display_name\na@example.test,\"A"), false));
        assertThrows(IOException.class, () -> CampaignCsvParser.parse(new ByteArrayInputStream(
                new byte[]{(byte)0xff,(byte)0xfe}), false));
    }

    @Test void rejectsCampaignOverRecipientLimit() {
        StringBuilder text = new StringBuilder("email,display_name\n");
        for (int i = 0; i <= CampaignCsvParser.MAX_RECIPIENTS; i++) {
            text.append("p").append(i).append("@example.test,Person\n");
        }
        assertThrows(IllegalArgumentException.class, () -> CampaignCsvParser.parse(csv(text.toString()), false));
    }
}
