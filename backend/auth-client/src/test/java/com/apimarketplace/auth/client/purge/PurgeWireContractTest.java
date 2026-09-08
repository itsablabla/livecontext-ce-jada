package com.apimarketplace.auth.client.purge;

import com.apimarketplace.auth.client.AuthClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * auth-service serves {@code InternalPurgeLogController.PurgeLogEntry}; this client reads
 * {@code PurgeRecord}. They are two records in two modules whose only link is the JSON field
 * names. A rename on either side would deserialise to nulls, and a null subject stalls every
 * follower (by design, see PurgeFollower). So the names are pinned here, on the wire form,
 * and the auth side pins the same three names on its record.
 */
@DisplayName("Purge wire contract")
class PurgeWireContractTest {

    @Test
    @DisplayName("PurgeRecord reads exactly the field names auth-service writes: seq, subjectType, subjectId")
    void fieldNamesArePinned() throws Exception {
        String wire = "{\"seq\":7,\"subjectType\":\"ORG\",\"subjectId\":\"11111111-1111-1111-1111-111111111111\",\"purgedAt\":\"2026-09-02T12:00:00Z\"}";

        PurgeRecord record = new ObjectMapper().readValue(wire, PurgeRecord.class);

        assertEquals(new PurgeRecord(7, "ORG", "11111111-1111-1111-1111-111111111111"), record);
        assertTrue(record.isOrganization());
    }

    @Test
    @DisplayName("An unknown extra field from a newer auth-service is ignored, not fatal")
    void extraFieldsIgnored() throws Exception {
        PurgeRecord record = new ObjectMapper().readValue("{\"seq\":1,\"subjectType\":\"USER\",\"subjectId\":\"42\",\"future\":true}", PurgeRecord.class);
        assertTrue(record.isUser());
    }

    /** The follower reads an empty page as "nothing new"; the client must never throw. */
    @Test
    @DisplayName("AuthClient.getPurges fails EMPTY on a transport error")
    void getPurgesFailsEmpty() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        AuthClient client = new AuthClient(rest, "http://auth.invalid");
        // getPurges uses the bounded (short-timeout) template AuthClient builds for itself,
        // not the injected one, so it is swapped by reflection for a mock that throws the
        // transport error: the call must come back empty rather than propagate.
        java.lang.reflect.Field bounded = AuthClient.class.getDeclaredField("boundedRestTemplate");
        bounded.setAccessible(true);
        RestTemplate boundedMock = mock(RestTemplate.class);
        when(boundedMock.exchange(anyString(), eq(HttpMethod.GET), any(), any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connection refused"));
        bounded.set(client, boundedMock);

        List<PurgeRecord> page = client.getPurges(0, 200);

        assertTrue(page.isEmpty());
    }
}
