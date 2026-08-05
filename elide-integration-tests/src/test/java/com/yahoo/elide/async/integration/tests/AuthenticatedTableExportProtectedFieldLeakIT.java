/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.integration.tests;

import static com.yahoo.elide.test.jsonapi.JsonApiDSL.attr;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.attributes;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.datum;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.resource;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.type;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yahoo.elide.async.integration.tests.framework.HeaderAuthFilter;
import com.yahoo.elide.core.exceptions.HttpStatus;
import com.yahoo.elide.jsonapi.JsonApi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.restassured.response.Response;

/**
 * Proof 2 (the headline): a field-level protected-data leak via the export download endpoint.
 *
 * <p>The async export runs the query as its owning principal, so its result file can contain
 * field-level protected data that a different caller would never be allowed to read directly. This
 * exercises {@code example.FilterExpressionCheckObj}, whose {@code name} field carries
 * {@code @ReadPermission("checkRestrictUser")} -- the field is only readable when the row id equals
 * the caller's (integer) principal name.
 *
 * <p>Principal "1" owns row id 1, so principal "1" sees {@code name}; principal "2" does not. When
 * principal "1" exports the row, the result file contains the protected value -- and pre-fix the
 * unauthenticated/other-principal {@code /export/{id}} download would disclose it. Post-fix the
 * download is owner-or-admin gated and returns 404 to a non-owner, so the protected value never
 * leaks through the export channel.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthenticatedTableExportProtectedFieldLeakIT extends AuthenticatedTableExportITBase {

    private static final String EXPORT_ID = "bbbbbbbb-0000-0000-0000-000000000001";
    // The row id is 1 (first filterExpressionCheckObj created against the freshly-cleansed store),
    // which matches principal "1" -- so only principal "1" may read the name field.
    private static final String PROTECTED_VALUE = "visible-only-to-principal-1";
    private static final String FIELD_QUERY =
            "/filterExpressionCheckObj?fields%5BfilterExpressionCheckObj%5D=name";

    private void createProtectedRow() {
        given()
                .header(HeaderAuthFilter.USER_HEADER, "1")
                .contentType(JsonApi.MEDIA_TYPE)
                .accept(JsonApi.MEDIA_TYPE)
                .body(
                        datum(
                                resource(
                                        type("filterExpressionCheckObj"),
                                        attributes(
                                                attr("name", PROTECTED_VALUE)
                                        )
                                )
                        ).toJSON())
                .post("/filterExpressionCheckObj")
                .then()
                .statusCode(HttpStatus.SC_CREATED);
    }

    @Test
    public void exportDownloadDoesNotLeakProtectedFieldToNonOwner() throws InterruptedException {
        createProtectedRow();

        // Baseline: direct reads confirm the field-level permission behaves as expected.
        // Owner ("1") sees the protected value.
        Response ownerDirect = given()
                .header(HeaderAuthFilter.USER_HEADER, "1")
                .accept("application/vnd.api+json")
                .param("fields[filterExpressionCheckObj]", "name")
                .get("/filterExpressionCheckObj/1");
        assertEquals(HttpStatus.SC_OK, ownerDirect.getStatusCode(), "Owner direct read failed");
        assertTrue(ownerDirect.asString().contains(PROTECTED_VALUE),
                "Owner should see the protected field directly");

        // A different principal ("2") does NOT get the protected value (Elide either 403s or omits
        // the field); either way the value must not be present.
        Response otherDirect = given()
                .header(HeaderAuthFilter.USER_HEADER, "2")
                .accept("application/vnd.api+json")
                .param("fields[filterExpressionCheckObj]", "name")
                .get("/filterExpressionCheckObj/1");
        assertFalse(otherDirect.asString().contains(PROTECTED_VALUE),
                "Non-owner direct read leaked the protected field");

        // Owner ("1") exports the row; the export runs as principal "1", so its result file
        // legitimately contains the protected value.
        createTableExport(EXPORT_ID, FIELD_QUERY, "1");
        String downloadId = pollForExportId(EXPORT_ID, "1");

        // Sanity: the owner can download the export and the protected value is genuinely in the file
        // (so the 404s below are protecting real, sensitive data -- not an empty result).
        Response asOwner = download(downloadId, "1", false);
        assertEquals(HttpStatus.SC_OK, asOwner.getStatusCode(), "Owner was denied their own export");
        assertTrue(asOwner.asString().contains(PROTECTED_VALUE),
                "Owner export download did not contain the protected value");

        // Headline: a different principal ("2") downloading the export gets 404 and NO protected data.
        Response asOtherPrincipal = download(downloadId, "2", false);
        asOtherPrincipal.then().statusCode(HttpStatus.SC_NOT_FOUND);
        assertFalse(asOtherPrincipal.asString().contains(PROTECTED_VALUE),
                "Export download leaked the protected field to a non-owner principal");

        // Headline: an anonymous caller downloading the export gets 404 and NO protected data.
        Response asAnonymous = download(downloadId, null, false);
        asAnonymous.then().statusCode(HttpStatus.SC_NOT_FOUND);
        assertFalse(asAnonymous.asString().contains(PROTECTED_VALUE),
                "Export download leaked the protected field to an anonymous caller");
    }
}
