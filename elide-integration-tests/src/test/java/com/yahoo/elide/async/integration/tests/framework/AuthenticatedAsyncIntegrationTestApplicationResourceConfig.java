/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.integration.tests.framework;

import org.glassfish.hk2.api.ServiceLocator;

import jakarta.inject.Inject;
import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Context;

/**
 * Same wiring as {@link AsyncIntegrationTestApplicationResourceConfig}, but registers
 * {@link HeaderAuthFilter} so the async + export endpoints can be exercised as distinct principals.
 * Used by the /export/{id} owner-or-admin authorization tests.
 */
public class AuthenticatedAsyncIntegrationTestApplicationResourceConfig
        extends AsyncIntegrationTestApplicationResourceConfig {

    @Inject
    public AuthenticatedAsyncIntegrationTestApplicationResourceConfig(
            ServiceLocator injector, @Context ServletContext servletContext) {
        super(injector, servletContext);
        register(HeaderAuthFilter.class);
    }
}
