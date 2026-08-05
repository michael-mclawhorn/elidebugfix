/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yahoo.elide.async.models.TableExport;
import com.yahoo.elide.async.service.dao.AsyncApiDao;
import com.yahoo.elide.core.filter.expression.FilterExpression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Collections;

/**
 * Verifies the owner-or-admin gate on export downloads: only the export's owner
 * or an admin may download; other principals, anonymous callers, and unknown ids are denied.
 */
class ExportDownloadAuthorizerTest {

    private static final String EXPORT_ID = "7c1bf67d-f39d-4b1a-8f15-dfe45aeb88b4";
    private static final String OWNER = "principal-1";
    private static final String OTHER = "principal-2";

    private AsyncApiDao asyncApiDao;
    private ExportDownloadAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        asyncApiDao = mock(AsyncApiDao.class);
        authorizer = new ExportDownloadAuthorizer(asyncApiDao);
    }

    private void givenExportOwnedBy(String principalName) {
        TableExport export = new TableExport();
        export.setId(EXPORT_ID);
        export.setPrincipalName(principalName);
        when(asyncApiDao.loadAsyncApiByFilter(any(FilterExpression.class), eq(TableExport.class)))
                .thenReturn(Collections.singletonList(export));
    }

    private void givenNoSuchExport() {
        when(asyncApiDao.loadAsyncApiByFilter(any(FilterExpression.class), eq(TableExport.class)))
                .thenReturn(Collections.emptyList());
    }

    private Principal principal(String name) {
        return () -> name;
    }

    @Test
    void ownerIsAuthorized() {
        givenExportOwnedBy(OWNER);
        assertTrue(authorizer.isAuthorized(EXPORT_ID, principal(OWNER), false),
                "the export's owner must be allowed to download");
    }

    @Test
    void adminIsAuthorizedEvenWhenNotOwner() {
        givenExportOwnedBy(OWNER);
        assertTrue(authorizer.isAuthorized(EXPORT_ID, principal(OTHER), true),
                "an admin must be allowed to download regardless of ownership");
    }

    @Test
    void otherPrincipalIsDenied() {
        givenExportOwnedBy(OWNER);
        assertFalse(authorizer.isAuthorized(EXPORT_ID, principal(OTHER), false),
                "a non-owner, non-admin principal must be denied");
    }

    @Test
    void anonymousIsDeniedForOwnedExport() {
        givenExportOwnedBy(OWNER);
        assertFalse(authorizer.isAuthorized(EXPORT_ID, null, false),
                "an anonymous caller must be denied an owned export");
    }

    @Test
    void unknownExportIsDenied() {
        givenNoSuchExport();
        assertFalse(authorizer.isAuthorized(EXPORT_ID, principal(OWNER), true),
                "a missing export must be denied (even for an admin) so it maps to 404");
    }

    @Test
    void anonymousMatchesNullOwnerExport() {
        // Mirrors AsyncApiOwner: a null-principal caller matches only a null-owner export.
        givenExportOwnedBy(null);
        assertTrue(authorizer.isAuthorized(EXPORT_ID, null, false),
                "a null-owner export is readable by an anonymous caller (owner-parity with metadata)");
    }
}
