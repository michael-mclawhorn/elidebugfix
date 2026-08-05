/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.service;

import com.yahoo.elide.async.models.TableExport;
import com.yahoo.elide.async.service.dao.AsyncApiDao;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.filter.Operator;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.predicates.FilterPredicate;
import com.yahoo.elide.core.type.ClassType;

import java.security.Principal;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;

/**
 * Enforces the {@code TableExport} owner-or-admin authorization model on the export <b>download</b>
 * endpoints.
 *
 * <p>{@code TableExport} metadata is guarded owner-or-admin (see
 * {@code AsyncApiInlineChecks.AsyncApiOwner} / {@code AsyncApiAdmin}), but the {@code /export/{id}}
 * download handlers historically streamed results by id with no such check -- so any caller (or an
 * anonymous one) who learned the URL could download another principal's export. This resolves the
 * owning {@code TableExport} and applies the same owner-or-admin rule before streaming.
 */
public class ExportDownloadAuthorizer {

    private final AsyncApiDao asyncApiDao;

    public ExportDownloadAuthorizer(AsyncApiDao asyncApiDao) {
        this.asyncApiDao = asyncApiDao;
    }

    /**
     * @param tableExportId the export id from the download path
     * @param principal the calling principal (may be {@code null} for an unauthenticated request)
     * @param isAdmin whether the caller holds the admin role
     * @return {@code true} only if the export exists and the caller is its owner or an admin;
     *     {@code false} when the export is missing or the caller is neither (callers should map
     *     both to 404 to preserve the metadata route's non-disclosure behavior).
     */
    public boolean isAuthorized(String tableExportId, Principal principal, boolean isAdmin) {
        TableExport export = loadById(tableExportId);
        if (export == null) {
            return false;
        }
        if (isAdmin) {
            return true;
        }
        String callerName = (principal == null) ? null : principal.getName();
        // Mirrors AsyncApiOwner: a null-principal caller matches only a null-owner export.
        return Objects.equals(callerName, export.getPrincipalName());
    }

    private TableExport loadById(String tableExportId) {
        if (tableExportId == null) {
            return null;
        }
        // The download path id may carry a result-type extension (e.g. "<uuid>.csv") when
        // file extensions are enabled; the TableExport entity id is the bare uuid. UUIDs contain
        // no '.', so strip anything from the first dot onward before resolving the export.
        String id = tableExportId;
        int extension = id.indexOf('.');
        if (extension >= 0) {
            id = id.substring(0, extension);
        }
        Path.PathElement idField =
                new Path.PathElement(ClassType.of(TableExport.class), ClassType.STRING_TYPE, "id");
        FilterExpression idFilter =
                new FilterPredicate(idField, Operator.IN, Collections.singletonList(id));
        Iterator<TableExport> matches =
                asyncApiDao.loadAsyncApiByFilter(idFilter, TableExport.class).iterator();
        return matches.hasNext() ? matches.next() : null;
    }
}
