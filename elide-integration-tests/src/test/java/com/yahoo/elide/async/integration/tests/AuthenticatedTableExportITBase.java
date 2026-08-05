/*
 * Copyright 2026, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.integration.tests;

import static com.yahoo.elide.test.jsonapi.JsonApiDSL.attr;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.attributes;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.data;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.datum;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.id;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.resource;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.type;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.yahoo.elide.async.integration.tests.framework.AuthenticatedAsyncIntegrationTestApplicationResourceConfig;
import com.yahoo.elide.async.integration.tests.framework.HeaderAuthFilter;
import com.yahoo.elide.core.datastore.DataStore;
import com.yahoo.elide.core.datastore.test.DataStoreTestHarness;
import com.yahoo.elide.core.exceptions.HttpStatus;
import com.yahoo.elide.initialization.IntegrationTest;
import com.yahoo.elide.jsonapi.JsonApi;
import com.yahoo.elide.jsonapi.resources.JsonApiEndpoint;
import com.yahoo.elide.test.jsonapi.elements.Resource;

import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Executors;

/**
 * Base for the /export/{id} owner-or-admin authorization integration tests.
 *
 * <p>Unlike {@link AsyncApiIT}, this boots the main, graphQL AND {@code /export/*} servlets against
 * {@link AuthenticatedAsyncIntegrationTestApplicationResourceConfig}, which registers
 * {@link HeaderAuthFilter}. That lets the same endpoint be replayed as distinct principals via the
 * {@code User} request header (and the admin role via {@code Roles: admin}) so a test can create an
 * export as one caller and attempt to download it as another.
 */
public abstract class AuthenticatedTableExportITBase extends IntegrationTest {

    @Getter
    private final Integer port;

    private static final Resource ENDERS_GAME = resource(
            type("book"),
            attributes(
                    attr("title", "Ender's Game"),
                    attr("genre", "Science Fiction"),
                    attr("language", "English")
            )
    );

    private static final Resource GAME_OF_THRONES = resource(
            type("book"),
            attributes(
                    attr("title", "Song of Ice and Fire"),
                    attr("genre", "Mythology Fiction"),
                    attr("language", "English")
            )
    );

    private static final Resource FOR_WHOM_THE_BELL_TOLLS = resource(
            type("book"),
            attributes(
                    attr("title", "For Whom the Bell Tolls"),
                    attr("genre", "Literary Fiction"),
                    attr("language", "English")
            )
    );

    protected AuthenticatedTableExportITBase() {
        super(AuthenticatedAsyncIntegrationTestApplicationResourceConfig.class,
                JsonApiEndpoint.class.getPackage().getName());
        this.port = super.getRestAssuredPort();
    }

    @Override
    protected DataStoreTestHarness createHarness() {
        DataStoreTestHarness dataStoreTestHarness = super.createHarness();
        return new DataStoreTestHarness() {
            @Override
            public DataStore getDataStore() {
                return new AsyncDelayDataStore(dataStoreTestHarness.getDataStore());
            }

            @Override
            public void cleanseTestData() {
                dataStoreTestHarness.cleanseTestData();
            }
        };
    }

    @Override
    public void modifyServletContextHandler() {
        // Async executor used by the Async lifecycle hooks.
        this.servletContextHandler.setAttribute(
                AuthenticatedAsyncIntegrationTestApplicationResourceConfig.ASYNC_EXECUTOR_ATTR,
                Executors.newFixedThreadPool(5));

        // Initialize the Export download endpoint, pointed at the SAME authenticated config so the
        // /export/{id} servlet also runs behind HeaderAuthFilter.
        ServletHolder exportServlet = servletContextHandler.addServlet(ServletContainer.class, "/export/*");
        exportServlet.setInitOrder(3);
        exportServlet.setInitParameter("jersey.config.server.provider.packages",
                com.yahoo.elide.async.resources.ExportApiEndpoint.class.getPackage().getName());
        exportServlet.setInitParameter("jakarta.ws.rs.Application",
                AuthenticatedAsyncIntegrationTestApplicationResourceConfig.class.getName());

        try {
            this.servletContextHandler.setAttribute(
                    AuthenticatedAsyncIntegrationTestApplicationResourceConfig.STORAGE_DESTINATION_ATTR,
                    Files.createTempDirectory("authAsyncIT"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Seed the three books used by the JSONAPI book export proof. Created as principal "1" so the
     * created rows are readable during the create round-trip.
     */
    protected void seedBooks() {
        createBook(ENDERS_GAME);
        createBook(GAME_OF_THRONES);
        createBook(FOR_WHOM_THE_BELL_TOLLS);
    }

    private void createBook(Resource book) {
        given()
                .header(HeaderAuthFilter.USER_HEADER, "1")
                .contentType(JsonApi.MEDIA_TYPE)
                .accept(JsonApi.MEDIA_TYPE)
                .body(datum(book).toJSON())
                .post("/book")
                .then()
                .statusCode(HttpStatus.SC_CREATED);
    }

    /**
     * Create a JSONAPI CSV tableExport as the given principal and return once it has been accepted.
     *
     * @param exportId client-supplied export uuid
     * @param query the JSONAPI query the export should run
     * @param user the owning principal (sent via the {@code User} header)
     */
    protected void createTableExport(String exportId, String query, String user) {
        given()
                .header(HeaderAuthFilter.USER_HEADER, user)
                .contentType(JsonApi.MEDIA_TYPE)
                .body(
                        data(
                                resource(
                                        type("tableExport"),
                                        id(exportId),
                                        attributes(
                                                attr("query", query),
                                                attr("queryType", "JSONAPI_V1_0"),
                                                attr("status", "QUEUED"),
                                                attr("asyncAfterSeconds", "0"),
                                                attr("resultType", "CSV")
                                        )
                                )
                        ).toJSON())
                .when()
                .post("/tableExport")
                .then()
                .statusCode(org.apache.http.HttpStatus.SC_CREATED);
    }

    /**
     * Poll the tableExport metadata (as the owning principal, since metadata is owner-or-admin
     * gated) until it reaches COMPLETE, then return the export download id parsed from result.url.
     */
    protected String pollForExportId(String exportId, String user) throws InterruptedException {
        Response response = null;
        int i = 0;
        while (i < 1000) {
            Thread.sleep(10);
            response = given()
                    .header(HeaderAuthFilter.USER_HEADER, user)
                    .accept("application/vnd.api+json")
                    .get("/tableExport/" + exportId);

            String status = response.jsonPath().getString("data.attributes.status");
            if ("COMPLETE".equals(status)) {
                break;
            }
            assertEquals("PROCESSING", status, "Async TableExport Request has failed.");
            i++;
            assertNotEquals(1000, i, "Async TableExport Request not completed.");
        }

        String url = response.jsonPath().getString("data.attributes.result.url");
        // url = http://localhost:<port>/export/<id[.ext]>
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * Perform a download GET against /export/{id}. A null {@code user} means an anonymous request
     * (no {@code User} header, hence a null principal in HeaderAuthFilter).
     */
    protected Response download(String downloadId, String user, boolean admin) {
        RequestSpecification spec = given();
        if (user != null) {
            spec = spec.header(HeaderAuthFilter.USER_HEADER, user);
        }
        if (admin) {
            spec = spec.header(HeaderAuthFilter.ROLES_HEADER, "admin");
        }
        return spec.when().get("/export/" + downloadId);
    }
}
