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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.Test;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The OpenAPI document is written by hand; these checks keep it in step with the code. */
public class OpenApiDocumentTest {

  private static final JsonNode DOC = read();

  private static JsonNode read() {
    try {
      return DatasetJson.mapper().readTree( DatasetsApiDocResource.text( DatasetsApiDocResource.OPENAPI ) );
    } catch ( Exception e ) {
      throw new IllegalStateException( e );
    }
  }

  @Test
  public void describesExactlyTheEndpointsTheCodeServes() {
    Set<String> inCode = new TreeSet<>();
    for ( Class<?> resource : List.of( DatasetsResource.class, ConnectionsResource.class, DatasetSettingsResource.class, DatasetsApiDocResource.class ) ) {
      String base = resource.getAnnotation( Path.class ).value();
      for ( Method m : resource.getDeclaredMethods() ) {
        String verb = m.isAnnotationPresent( GET.class ) ? "get" : m.isAnnotationPresent( POST.class ) ? "post"
          : m.isAnnotationPresent( PUT.class ) ? "put" : m.isAnnotationPresent( DELETE.class ) ? "delete" : null;
        if ( verb != null ) {
          Path p = m.getAnnotation( Path.class );
          String path = base + ( p == null ? "" : "/" + p.value() );
          inCode.add( verb + " " + path );
        }
      }
    }
    Set<String> inDoc = new TreeSet<>();
    for ( Iterator<String> paths = DOC.get( "paths" ).fieldNames(); paths.hasNext(); ) {
      String path = paths.next();
      for ( Iterator<String> verbs = DOC.get( "paths" ).get( path ).fieldNames(); verbs.hasNext(); ) {
        String verb = verbs.next();
        if ( !"parameters".equals( verb ) ) {
          inDoc.add( verb + " " + path );
        }
      }
    }
    assertEquals( inCode, inDoc );
  }

  @Test
  public void listsEveryErrorCode() {
    List<String> documented = new ArrayList<>();
    DOC.at( "/components/schemas/Error/properties/code/enum" ).forEach( n -> documented.add( n.asText() ) );
    List<String> inCode = new ArrayList<>();
    for ( Code c : Code.values() ) {
      inCode.add( c.wire() );
    }
    assertEquals( inCode, documented );
  }

  @Test
  public void resolvesEveryLocalReference() {
    List<String> broken = new ArrayList<>();
    collectBroken( DOC, broken );
    assertTrue( "unresolved: " + broken, broken.isEmpty() );
  }

  @Test
  public void servesTheDocumentAndTheSchemaItRefersTo() throws Exception {
    DatasetsApiDocResource docs = new DatasetsApiDocResource();
    assertEquals( "3.1.0", DatasetJson.mapper().readTree( (String) docs.openApi().getEntity() ).get( "openapi" ).asText() );
    JsonNode schema = DatasetJson.mapper().readTree( (String) docs.definitionSchema().getEntity() );
    assertEquals( "https://json-schema.org/draft/2020-12/schema", schema.get( "$schema" ).asText() );
    assertTrue( docs.definitionSchema().getMediaType().toString().toLowerCase( Locale.ROOT ).startsWith( "application/schema+json" ) );
  }

  private static void collectBroken( JsonNode node, List<String> broken ) {
    if ( node.isObject() ) {
      JsonNode ref = node.get( "$ref" );
      if ( ref != null && ref.asText().startsWith( "#/" ) && DOC.at( ref.asText().substring( 1 ) ).isMissingNode() ) {
        broken.add( ref.asText() );
      }
      node.forEach( child -> collectBroken( child, broken ) );
    } else if ( node.isArray() ) {
      node.forEach( child -> collectBroken( child, broken ) );
    }
  }
}
