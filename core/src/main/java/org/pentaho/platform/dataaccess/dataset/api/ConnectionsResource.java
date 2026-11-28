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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.pentaho.platform.dataaccess.dataset.service.DatasetService;

import java.util.function.Supplier;

/** What dataset authors need to know about connections: for now, the tables to pick from. */
@Path( "/data-access/api/v2/connections" )
@Produces( MediaType.APPLICATION_JSON )
public class ConnectionsResource {

  private final Supplier<DatasetService> service;

  public ConnectionsResource( Supplier<DatasetService> service ) {
    this.service = service;
  }

  /** {@code {"tables": [{schema, name, type, qualifiedName}], "truncated": bool}}, by schema and name. */
  @GET
  @Path( "{name}/tables" )
  public Response tables( @PathParam( "name" ) String name ) {
    return DatasetsResource.respond( () -> Response.ok( DatasetsResource.MAPPER.writeValueAsString( service.get().connectionTables( name ) ) ).build() );
  }
}
