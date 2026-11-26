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

package org.pentaho.platform.dataaccess.dataset;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads and writes dataset definitions as JSON.
 * <p>
 * Reading is strict: unknown fields, values of the wrong type and unknown enum values are errors
 * that name the field ({@link DatasetFormatException#getPath()}), because a silently dropped field
 * is lost work. Older files are upgraded first ({@link DatasetUpgrades}). Writing is stable: the
 * same definition always produces the same text, so stored files diff cleanly in version history
 * and in Git. No Java serialization or XStream is involved.
 */
public final class DatasetJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder()
    .enable( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES )
    .enable( DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES )
    .enable( DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS )
    .enable( DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY )
    .enable( SerializationFeature.INDENT_OUTPUT )
    .enable( SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS )
    .build();

  private final DatasetUpgrades upgrades;

  public DatasetJson() {
    this( DatasetUpgrades.standard() );
  }

  public DatasetJson( DatasetUpgrades upgrades ) {
    this.upgrades = upgrades;
  }

  /** The mapper, configured as above; for callers that need the JSON tree (for example the REST layer). */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public DatasetDefinition read( String json ) {
    try {
      return bind( MAPPER.readTree( json ) );
    } catch ( JsonProcessingException e ) {
      throw translate( e );
    }
  }

  public DatasetDefinition read( InputStream in ) throws IOException {
    try {
      return bind( MAPPER.readTree( in ) );
    } catch ( JsonProcessingException e ) {
      throw translate( e );
    }
  }

  /** The definition as JSON text, ending with a newline. */
  public String write( DatasetDefinition definition ) {
    try {
      return MAPPER.writeValueAsString( definition ) + "\n";
    } catch ( JsonProcessingException e ) {
      throw new IllegalStateException( "A dataset definition could not be written as JSON", e );
    }
  }

  private DatasetDefinition bind( JsonNode tree ) throws JsonProcessingException {
    if ( tree == null || tree.isMissingNode() ) {
      throw new DatasetFormatException( "", "is empty" );
    }
    if ( !tree.isObject() ) {
      throw new DatasetFormatException( "", "must be a JSON object" );
    }
    ObjectNode upgraded = upgrades.upgrade( (ObjectNode) tree );
    return MAPPER.treeToValue( upgraded, DatasetDefinition.class );
  }

  private static DatasetFormatException translate( JsonProcessingException e ) {
    if ( e instanceof JsonMappingException ) {
      JsonMappingException mapping = (JsonMappingException) e;
      String path = pathOf( mapping.getPath() );
      if ( e instanceof UnrecognizedPropertyException ) {
        return new DatasetFormatException( path, "is not a known field", e );
      }
      if ( e instanceof InvalidFormatException && ( (InvalidFormatException) e ).getTargetType() != null
        && ( (InvalidFormatException) e ).getTargetType().isEnum() ) {
        InvalidFormatException invalid = (InvalidFormatException) e;
        String allowed = Arrays.stream( invalid.getTargetType().getEnumConstants() )
          .map( String::valueOf ).collect( Collectors.joining( ", " ) );
        return new DatasetFormatException( path, "must be one of " + allowed + ", not \"" + invalid.getValue() + "\"", e );
      }
      if ( !mapping.getPath().isEmpty() ) {
        return new DatasetFormatException( path, "has the wrong type (" + mapping.getOriginalMessage().split( "\n" )[ 0 ] + ")", e );
      }
    }
    JsonLocation at = e.getLocation();
    String where = at == null ? "" : " at line " + at.getLineNr() + ", column " + at.getColumnNr();
    return new DatasetFormatException( "", "is not valid JSON" + where + ": " + e.getOriginalMessage(), e );
  }

  /** "columns[2].type" from Jackson's reference chain. */
  static String pathOf( List<JsonMappingException.Reference> references ) {
    StringBuilder path = new StringBuilder();
    for ( JsonMappingException.Reference ref : references ) {
      if ( ref.getFieldName() != null ) {
        if ( path.length() > 0 ) {
          path.append( '.' );
        }
        path.append( ref.getFieldName() );
      } else if ( ref.getIndex() >= 0 ) {
        path.append( '[' ).append( ref.getIndex() ).append( ']' );
      }
    }
    return path.toString();
  }
}
