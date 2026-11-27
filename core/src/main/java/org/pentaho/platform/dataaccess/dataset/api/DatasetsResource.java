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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetFormatException;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.service.DatasetService;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare;
import org.pentaho.platform.dataaccess.dataset.service.StoredDataset;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Datasets REST API, version 2: {@code /pentaho/plugin/data-access/api/v2/datasets}.
 * <p>
 * JSON in and out. Every read returns the dataset's version as an {@code ETag}; every update and
 * delete must send it back as {@code If-Match}, and is refused with 412 if the dataset changed in
 * between (428 if the header is missing). Errors are always JSON:
 * {@code {"code": "stale-version", "message": "…", "path": "columns[1].name", "problems": [...]}},
 * never an HTML error page. The v1 data source endpoints are unchanged.
 */
@Path( "/data-access/api/v2/datasets" )
@Produces( MediaType.APPLICATION_JSON )
public class DatasetsResource {

  private static final Log LOG = LogFactory.getLog( DatasetsResource.class );
  private static final String BASE = "data-access/api/v2/datasets/";

  private final Supplier<DatasetService> service;
  private final DatasetJson json = new DatasetJson();
  private final ObjectMapper mapper = DatasetJson.mapper();

  /** @param service built per request, so it sees the caller's session (see the Spring wiring) */
  public DatasetsResource( Supplier<DatasetService> service ) {
    this.service = service;
  }

  /** {@code {"datasets": [{id, name, description, owner, sourceType, version, updated}]}}, by name. */
  @GET
  public Response list() {
    return handle( () -> {
      ObjectNode body = mapper.createObjectNode();
      ArrayNode items = body.putArray( "datasets" );
      for ( StoredDataset s : service.get().list() ) {
        DatasetDefinition d = s.getDefinition();
        ObjectNode item = items.addObject();
        item.put( "id", d.getId() );
        item.put( "name", d.getName() );
        putIfPresent( item, "description", d.getDescription() );
        putIfPresent( item, "owner", d.getOwner() );
        if ( d.getSource() != null && d.getSource().getType() != null ) {
          item.put( "sourceType", d.getSource().getType().name() );
        }
        item.put( "version", s.getVersion() );
        item.put( "updated", s.getUpdated().toString() );
      }
      return Response.ok( mapper.writeValueAsString( body ) ).build();
    } );
  }

  /** Creates a draft; 201 with its location and version. Without an id, one is made from the name. */
  @POST
  @Consumes( MediaType.APPLICATION_JSON )
  public Response create( String body ) {
    return handle( () -> {
      StoredDataset created = service.get().create( parse( body ) );
      URI location = URI.create( BASE + URLEncoder.encode( created.getDefinition().getId(), StandardCharsets.UTF_8 ) );
      return definition( Response.created( location ), created );
    } );
  }

  @GET
  @Path( "{id}" )
  public Response get( @PathParam( "id" ) String id, @HeaderParam( HttpHeaders.IF_NONE_MATCH ) String ifNoneMatch ) {
    return handle( () -> {
      StoredDataset stored = service.get().get( id );
      if ( ifNoneMatch != null && stored.getVersion().equals( version( ifNoneMatch ) ) ) {
        return Response.notModified( etag( stored ) ).build();
      }
      return definition( Response.ok(), stored );
    } );
  }

  /** Replaces the draft. Requires If-Match with the version last read. */
  @PUT
  @Path( "{id}" )
  @Consumes( MediaType.APPLICATION_JSON )
  public Response update( @PathParam( "id" ) String id, @HeaderParam( HttpHeaders.IF_MATCH ) String ifMatch, String body ) {
    return handle( () -> {
      DatasetService s = service.get();
      return definition( Response.ok(), s.update( id, parse( body ), expected( s, id, ifMatch ) ) );
    } );
  }

  /** Deletes the dataset. Requires If-Match with the version last read. */
  @DELETE
  @Path( "{id}" )
  public Response delete( @PathParam( "id" ) String id, @HeaderParam( HttpHeaders.IF_MATCH ) String ifMatch ) {
    return handle( () -> {
      DatasetService s = service.get();
      s.delete( id, expected( s, id, ifMatch ) );
      return Response.noContent().build();
    } );
  }

  /** {@code {"valid": true|false, "problems": [{path, message}]}}: what blocks publishing. */
  @POST
  @Path( "{id}/validate" )
  public Response validate( @PathParam( "id" ) String id ) {
    return handle( () -> {
      List<DatasetProblem> problems = service.get().validate( id );
      ObjectNode body = mapper.createObjectNode();
      body.put( "valid", problems.isEmpty() );
      problemsInto( body, problems );
      return Response.ok( mapper.writeValueAsString( body ) ).build();
    } );
  }

  /**
   * Who the dataset is shared with: {@code {"restricted": false, "entries": []}}, or restricted to
   * users and roles with READ or WRITE access. The ETag is the sharing's own version.
   */
  @GET
  @Path( "{id}/acl" )
  public Response getAcl( @PathParam( "id" ) String id ) {
    return handle( () -> share( Response.ok(), service.get().getSharing( id ) ) );
  }

  /** Replaces the sharing. Owner or administrator only; requires If-Match with the sharing's ETag. */
  @PUT
  @Path( "{id}/acl" )
  @Consumes( MediaType.APPLICATION_JSON )
  public Response setAcl( @PathParam( "id" ) String id, @HeaderParam( HttpHeaders.IF_MATCH ) String ifMatch, String body ) {
    return handle( () -> {
      DatasetService s = service.get();
      DatasetShare share = parseShare( body );
      String expected = ifMatch == null || ifMatch.trim().isEmpty() ? null
        : "*".equals( ifMatch.trim() ) ? s.getSharing( id ).tag() : version( ifMatch );
      return share( Response.ok(), s.setSharing( id, share, expected ) );
    } );
  }

  /**
   * The source's first rows: {@code {"columns": [{name, type, sourceType, nullable, table}], "rows": [[…]],
   * "truncated": bool}}. {@code limit} is 1 to 1000 (100 by default); {@code table} picks a table of a
   * TABLES source.
   */
  @POST
  @Path( "{id}/sample" )
  public Response sample( @PathParam( "id" ) String id, @QueryParam( "limit" ) Integer limit, @QueryParam( "table" ) String table ) {
    return handle( () -> Response.ok( mapper.writeValueAsString( service.get().sample( id, limit, table ) ) ).build() );
  }

  /** Publishing generates the metadata and Mondrian files (CWBA-63). */
  @POST
  @Path( "{id}/publish" )
  public Response publish( @PathParam( "id" ) String id ) {
    return handle( () -> {
      service.get().get( id );
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "Publishing arrives with the generators (CWBA-63)" );
    } );
  }

  // ------------------------------------------------------------------------------------------------

  private DatasetDefinition parse( String body ) {
    if ( body == null || body.trim().isEmpty() ) {
      throw new DatasetApiException( Code.INVALID_DEFINITION, "Send the dataset definition as JSON in the request body" );
    }
    try {
      return json.read( body );
    } catch ( DatasetFormatException e ) {
      throw new DatasetApiException( Code.INVALID_DEFINITION, e.getPath(), e.getMessage(), null, e );
    }
  }

  private DatasetShare parseShare( String body ) {
    if ( body == null || body.trim().isEmpty() ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "Send the sharing as JSON in the request body" );
    }
    try {
      return mapper.readValue( body, DatasetShare.class );
    } catch ( JsonProcessingException e ) {
      DatasetFormatException f = DatasetJson.explain( e );
      throw new DatasetApiException( Code.INVALID_REQUEST, f.getPath(), f.getMessage(), null, e );
    }
  }

  private Response share( Response.ResponseBuilder builder, DatasetShare share ) throws JsonProcessingException {
    return builder.tag( new EntityTag( share.tag() ) ).entity( mapper.writeValueAsString( share ) ).type( MediaType.APPLICATION_JSON ).build();
  }

  private Response definition( Response.ResponseBuilder builder, StoredDataset stored ) {
    return builder.tag( etag( stored ) ).entity( json.write( stored.getDefinition() ) ).type( MediaType.APPLICATION_JSON ).build();
  }

  private static EntityTag etag( StoredDataset stored ) {
    return new EntityTag( stored.getVersion() );
  }

  /** The version an If-Match names; "*" means whatever is current. Null when absent. */
  private static String expected( DatasetService s, String id, String ifMatch ) {
    if ( ifMatch == null || ifMatch.trim().isEmpty() ) {
      return null;
    }
    return "*".equals( ifMatch.trim() ) ? s.get( id ).getVersion() : version( ifMatch );
  }

  /** {@code "3"}, {@code W/"3"} or {@code 3} -> {@code 3}. */
  static String version( String tag ) {
    String t = tag.trim();
    if ( t.startsWith( "W/" ) ) {
      t = t.substring( 2 );
    }
    if ( t.length() >= 2 && t.startsWith( "\"" ) && t.endsWith( "\"" ) ) {
      t = t.substring( 1, t.length() - 1 );
    }
    return t;
  }

  private Response handle( Action action ) {
    try {
      return action.run();
    } catch ( DatasetApiException e ) {
      return error( e.getCode(), e.getMessage(), e.getPath(), e.getProblems() );
    } catch ( Exception e ) {
      LOG.error( "Datasets API request failed", e );
      return error( Code.INTERNAL, "The server could not complete the request; see the server log", "", List.of() );
    }
  }

  private Response error( Code code, String message, String path, List<DatasetProblem> problems ) {
    ObjectNode body = mapper.createObjectNode();
    body.put( "code", code.wire() );
    body.put( "message", message );
    putIfPresent( body, "path", path );
    if ( !problems.isEmpty() ) {
      problemsInto( body, problems );
    }
    String text;
    try {
      text = mapper.writeValueAsString( body );
    } catch ( Exception e ) {
      text = "{\"code\":\"internal\"}";
    }
    return Response.status( code.getStatus() ).entity( text ).type( MediaType.APPLICATION_JSON ).build();
  }

  private static void problemsInto( ObjectNode body, List<DatasetProblem> problems ) {
    ArrayNode list = body.putArray( "problems" );
    for ( DatasetProblem p : problems ) {
      list.addObject().put( "path", p.getPath() ).put( "message", p.getMessage() );
    }
  }

  private static void putIfPresent( ObjectNode node, String field, String value ) {
    if ( value != null && !value.isEmpty() ) {
      node.put( field, value );
    }
  }

  @FunctionalInterface
  private interface Action {
    Response run() throws Exception;
  }
}
