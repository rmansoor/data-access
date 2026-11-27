/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2026 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/

package org.pentaho.platform.dataaccess.dataset.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the Datasets API's OpenAPI document and the dataset definition schema it refers to, side by
 * side, so the relative reference in the document resolves.
 */
@Path( "/data-access/api/v2" )
public class DatasetsApiDocResource {

  static final String OPENAPI = "/org/pentaho/platform/dataaccess/dataset/api/openapi.json";
  static final String SCHEMA = "/org/pentaho/platform/dataaccess/dataset/dataset-definition.schema.json";

  private final String openApi = text( OPENAPI );
  private final String schema = text( SCHEMA );

  @GET
  @Path( "openapi.json" )
  public Response openApi() {
    return serve( openApi, "application/json" );
  }

  @GET
  @Path( "dataset-definition.schema.json" )
  public Response definitionSchema() {
    return serve( schema, "application/schema+json" );
  }

  private static Response serve( String body, String type ) {
    CacheControl cache = new CacheControl();
    cache.setMaxAge( 3600 );
    return Response.ok( body, type + "; charset=UTF-8" ).cacheControl( cache ).build();
  }

  static String text( String resource ) {
    try ( InputStream in = DatasetsApiDocResource.class.getResourceAsStream( resource ) ) {
      if ( in == null ) {
        throw new IllegalStateException( resource + " is missing from the plugin" );
      }
      return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
    } catch ( IOException e ) {
      throw new UncheckedIOException( e );
    }
  }
}
