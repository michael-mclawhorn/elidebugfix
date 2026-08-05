/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.integration.tests.framework;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.security.Principal;
import java.util.Arrays;

/**
 * Test-only auth filter that injects the principal named by the {@code User} request header (and
 * grants the admin role when the {@code Roles} header contains {@code admin}). Unlike the stock
 * fixed-principal {@code TestAuthFilter}, this lets an integration test replay the same endpoint as
 * distinct callers -- which is what the /export/{id} BAC repro requires. With no
 * {@code User} header the request is anonymous (null principal).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class HeaderAuthFilter implements ContainerRequestFilter {

    public static final String USER_HEADER = "User";
    public static final String ROLES_HEADER = "Roles";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String user = requestContext.getHeaderString(USER_HEADER);
        String roles = requestContext.getHeaderString(ROLES_HEADER);
        boolean admin = roles != null && Arrays.asList(roles.split(",")).contains("admin");
        Principal principal = (user == null || user.isEmpty()) ? null : () -> user;

        requestContext.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return admin && "admin".equals(role);
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public String getAuthenticationScheme() {
                return null;
            }
        });
    }
}
