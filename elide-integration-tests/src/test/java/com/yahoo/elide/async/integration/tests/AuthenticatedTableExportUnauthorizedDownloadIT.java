/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.integration.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yahoo.elide.core.exceptions.HttpStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.restassured.response.Response;

/**
 * Proof 1: Broken Access Control on the async export download endpoint.
 *
 * <p>Principal "1" creates a book export, then the {@code /export/{id}} download is replayed as
 * other callers. Pre-fix, any caller who learned the id could stream another principal's export;
 * post-fix the endpoint enforces the same owner-or-admin rule as the metadata route and returns 404
 * (non-disclosure) to non-owners while still serving the legitimate owner (and admins).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthenticatedTableExportUnauthorizedDownloadIT extends AuthenticatedTableExportITBase {

    private static final String EXPORT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String BOOK_QUERY = "/book?sort=title&fields%5Bbook%5D=title";

    // One of the exported values; if it appears in a response body, the export data leaked.
    private static final String SECRET_TITLE = "For Whom the Bell Tolls";

    @BeforeEach
    public void setup() {
        seedBooks();
    }

    @Test
    public void nonOwnerAndAnonymousCannotDownloadOthersExportButOwnerCan() throws InterruptedException {
        // Owner (principal "1") creates the export and we resolve its download id.
        createTableExport(EXPORT_ID, BOOK_QUERY, "1");
        String downloadId = pollForExportId(EXPORT_ID, "1");

        // A different principal ("2") must NOT be able to download it: 404 + no data.
        Response asOtherPrincipal = download(downloadId, "2", false);
        asOtherPrincipal.then().statusCode(HttpStatus.SC_NOT_FOUND);
        assertFalse(asOtherPrincipal.asString().contains(SECRET_TITLE),
                "Non-owner download leaked exported data");

        // An anonymous caller (no User header, null principal) must NOT be able to download: 404 + no data.
        Response asAnonymous = download(downloadId, null, false);
        asAnonymous.then().statusCode(HttpStatus.SC_NOT_FOUND);
        assertFalse(asAnonymous.asString().contains(SECRET_TITLE),
                "Anonymous download leaked exported data");

        // Sanity: the legitimate owner ("1") can still download and gets the data (200).
        Response asOwner = download(downloadId, "1", false);
        assertEquals(HttpStatus.SC_OK, asOwner.getStatusCode(), "Owner was denied their own export");
        assertTrue(asOwner.asString().contains(SECRET_TITLE),
                "Owner download did not contain the exported data");

        // An admin (any principal + Roles: admin) can also download (200).
        Response asAdmin = download(downloadId, "9", true);
        assertEquals(HttpStatus.SC_OK, asAdmin.getStatusCode(), "Admin was denied the export");
        assertTrue(asAdmin.asString().contains(SECRET_TITLE),
                "Admin download did not contain the exported data");
    }
}
