/*
 * Copyright 2021, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yahoo.elide.async.models.TableExport;
import com.yahoo.elide.async.resources.ExportApiEndpoint.ExportApiProperties;
import com.yahoo.elide.async.service.dao.AsyncApiDao;
import com.yahoo.elide.async.service.storageengine.FileResultStorageEngine;
import com.yahoo.elide.async.service.storageengine.ResultStorageEngine;
import com.yahoo.elide.core.filter.expression.FilterExpression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * ExportAPiEndpoint Test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExportApiEndpointTest {

    private static final String QUERY_ID = "1";
    private static final String OWNER = "owner-1";

    private ExportApiEndpoint endpoint;
    private ResultStorageEngine engine;
    private AsyncApiDao asyncApiDao;
    private AsyncResponse asyncResponse;
    private HttpServletResponse response;
    private SecurityContext securityContext;
    private ExportApiProperties exportApiProperties;
    private ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);

    @BeforeEach
    public void setup() {
        engine = mock(FileResultStorageEngine.class);
        asyncApiDao = mock(AsyncApiDao.class);
        asyncResponse = mock(AsyncResponse.class);
        response = mock(HttpServletResponse.class);
        securityContext = mock(SecurityContext.class);

        // The export being downloaded is owned by OWNER.
        TableExport export = new TableExport();
        export.setId(QUERY_ID);
        export.setPrincipalName(OWNER);
        when(asyncApiDao.loadAsyncApiByFilter(any(FilterExpression.class), eq(TableExport.class)))
                .thenReturn(Collections.singletonList(export));
    }

    @Test
    public void testGet() {
        int maxDownloadTimeSeconds = 1;
        int maxDownloadTimeMilliSeconds = (int) TimeUnit.SECONDS.toMillis(maxDownloadTimeSeconds);
        when(engine.getResultsByID(QUERY_ID)).thenReturn(outputStream -> {
            try {
                outputStream.write("result".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        // Authorized caller: the export's owner.
        when(securityContext.getUserPrincipal()).thenReturn((Principal) () -> OWNER);

        exportApiProperties = new ExportApiProperties(Executors.newFixedThreadPool(1), Duration.ofSeconds(maxDownloadTimeSeconds));
        endpoint = new ExportApiEndpoint(engine, exportApiProperties, asyncApiDao);
        endpoint.get(QUERY_ID, response, securityContext, asyncResponse);

        // Timeout(int) succeeds as soon as the function to be verified is called.
        // It waits maximum upto value of "int" for function to be called.
        verify(engine, timeout(maxDownloadTimeMilliSeconds)).getResultsByID(QUERY_ID);
        verify(asyncResponse, timeout(maxDownloadTimeMilliSeconds)).resume(responseCaptor.capture());
        final Response res = responseCaptor.getValue();

        assertEquals(200, res.getStatus());
    }

    @Test
    public void testGetUnauthorizedReturnsNotFound() {
        // A non-owner, non-admin caller must not be able to download another principal's export.
        when(securityContext.getUserPrincipal()).thenReturn((Principal) () -> "someone-else");

        exportApiProperties = new ExportApiProperties(Executors.newFixedThreadPool(1), Duration.ofSeconds(1));
        endpoint = new ExportApiEndpoint(engine, exportApiProperties, asyncApiDao);
        endpoint.get(QUERY_ID, response, securityContext, asyncResponse);

        verify(asyncResponse, timeout(1000)).resume(responseCaptor.capture());
        assertEquals(404, responseCaptor.getValue().getStatus(),
                "a non-owner must get 404, not the export contents");
        // The result must never be read for an unauthorized caller.
        verify(engine, never()).getResultsByID(QUERY_ID);
    }
}
