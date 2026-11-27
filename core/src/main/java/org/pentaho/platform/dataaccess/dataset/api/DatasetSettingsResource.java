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

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.pentaho.platform.dataaccess.dataset.DatasetFormatException;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.service.DatasetService;
import org.pentaho.platform.dataaccess.dataset.source.StagingSettings;

import java.util.function.Supplier;

/** Server-wide dataset settings: for now, where uploaded files are loaded. */
@Path( "/data-access/api/v2/settings" )
@Produces( MediaType.APPLICATION_JSON )
public class DatasetSettingsResource {

  private final Supplier<DatasetService> service;

  public DatasetSettingsResource( Supplier<DatasetService> service ) {
    this.service = service;
  }

  /** {@code {"connection", "schema", "saved": {connection, schema}, "defaults": {connection, schema}}}. */
  @GET
  @Path( "staging" )
  public Response getStaging() {
    return DatasetsResource.respond( () -> Response.ok( DatasetsResource.MAPPER.writeValueAsString( service.get().getStaging() ) ).build() );
  }

  /** Administrators only. {@code {"connection", "schema"}}; empty values go back to settings.xml. */
  @PUT
  @Path( "staging" )
  @Consumes( MediaType.APPLICATION_JSON )
  public Response setStaging( String body ) {
    return DatasetsResource.respond( () -> {
      StagingSettings settings;
      if ( body == null || body.trim().isEmpty() ) {
        throw new DatasetApiException( Code.INVALID_REQUEST, "Send the staging settings as JSON in the request body" );
      }
      try {
        settings = DatasetsResource.MAPPER.readValue( body, StagingSettings.class );
      } catch ( JsonProcessingException e ) {
        DatasetFormatException f = DatasetJson.explain( e );
        throw new DatasetApiException( Code.INVALID_REQUEST, f.getPath(), f.getMessage(), null, e );
      }
      return Response.ok( DatasetsResource.MAPPER.writeValueAsString( service.get().setStaging( settings ) ) ).build();
    } );
  }
}
