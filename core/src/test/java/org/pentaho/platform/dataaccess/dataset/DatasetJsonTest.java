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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatasetJsonTest {

  static final String[] EXAMPLES = { "csv-monthly-orders.json", "sql-top-customers.json", "tables-steelwheels-star.json" };

  private final DatasetJson json = new DatasetJson();

  static String example( String name ) throws IOException {
    try ( InputStream in = DatasetJsonTest.class.getResourceAsStream( name ) ) {
      if ( in == null ) {
        throw new IOException( "Missing test resource " + name );
      }
      return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
    }
  }

  @Test
  public void roundTripsEveryExampleWithoutLoss() throws IOException {
    for ( String name : EXAMPLES ) {
      String original = example( name );
      String written = json.write( json.read( original ) );
      JsonNode before = DatasetJson.mapper().readTree( original );
      JsonNode after = DatasetJson.mapper().readTree( written );
      assertEquals( name, before, after );
    }
  }

  @Test
  public void writesTheSameTextEveryTime() throws IOException {
    for ( String name : EXAMPLES ) {
      String once = json.write( json.read( example( name ) ) );
      String twice = json.write( json.read( once ) );
      assertEquals( name, once, twice );
      assertTrue( name, once.endsWith( "}\n" ) );
    }
  }

  @Test
  public void alwaysWritesTheSchemaVersionAndLeavesDefaultsOut() {
    DatasetDefinition d = new DatasetDefinition();
    d.setId( "x" );
    String written = json.write( d );
    assertTrue( written, written.contains( "\"schemaVersion\" : 1" ) );
    assertFalse( "empty lists are not written: " + written, written.contains( "columns" ) );
  }

  @Test
  public void writesTheColumnsLeftOutOnlyWhenRecordedEvenWhenNone() {
    DatasetDefinition d = new DatasetDefinition();
    d.setId( "x" );
    assertFalse( json.write( d ).contains( "excludedColumns" ) );
    d.setExcludedColumns( new java.util.ArrayList<>() );
    assertTrue( json.write( d ), json.write( d ).replaceAll( "\\s", "" ).contains( "\"excludedColumns\":[]" ) );
    d.getExcludedColumns().add( new DatasetDefinition.ColumnRef( null, "NOTE" ) );
    DatasetDefinition back = json.read( json.write( d ) );
    assertEquals( 1, back.getExcludedColumns().size() );
    assertEquals( "NOTE", back.getExcludedColumns().get( 0 ).getSourceName() );
    assertEquals( null, back.getExcludedColumns().get( 0 ).getTable() );
    assertTrue( json.read( json.write( new DatasetDefinition() ) ).getExcludedColumns() == null );
  }

  @Test
  public void readsTheModelIntoTypedObjects() throws IOException {
    DatasetDefinition d = json.read( example( "tables-steelwheels-star.json" ) );
    assertEquals( DatasetDefinition.SourceType.TABLES, d.getSource().getType() );
    assertEquals( 3, d.getSource().getTables().size() );
    assertEquals( DatasetDefinition.JoinType.LEFT_OUTER, d.getRelationships().get( 1 ).getJoinType() );
    assertEquals( "pat", d.getSecurity().getRules().get( 1 ).getUser() );
    assertEquals( "Steel Wheels sales.xmi", d.getLegacy().getDomainId() );
    assertTrue( d.getModel().getDimensions().get( 2 ).isTime() );
  }

  @Test
  public void namesAnUnknownFieldByItsPath() {
    assertFails( "{\"schemaVersion\":1,\"id\":\"a\",\"columns\":[{\"name\":\"x\",\"type\":\"STRING\"},{\"name\":\"y\",\"colour\":\"red\"}]}",
      "columns[1].colour", "is not a known field" );
  }

  @Test
  public void listsTheAllowedValuesForABadEnum() {
    assertFails( "{\"schemaVersion\":1,\"columns\":[{\"name\":\"x\",\"type\":\"TEXT\"}]}",
      "columns[0].type", "must be one of STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, not \"TEXT\"" );
  }

  @Test
  public void reportsAValueOfTheWrongType() {
    assertFails( "{\"schemaVersion\":1,\"columns\":[{\"name\":\"x\",\"hidden\":\"perhaps\"}]}", "columns[0].hidden", "has the wrong type" );
  }

  @Test
  public void reportsMalformedJsonWithItsLocation() {
    assertFails( "{\n  \"schemaVersion\": 1,\n  \"id\": \n}", "", "is not valid JSON at line 4" );
  }

  @Test
  public void refusesADocumentThatIsNotAnObject() {
    assertFails( "[1, 2]", "", "must be a JSON object" );
  }

  @Test
  public void refusesAMissingOrNewerSchemaVersion() {
    assertFails( "{\"id\":\"a\"}", "schemaVersion", "is required" );
    assertFails( "{\"schemaVersion\":99,\"id\":\"a\"}", "schemaVersion", "99 is newer than this server supports (1)" );
    assertFails( "{\"schemaVersion\":\"1\",\"id\":\"a\"}", "schemaVersion", "must be a whole number" );
  }

  @Test
  public void refusesDuplicateKeys() {
    assertFails( "{\"schemaVersion\":1,\"id\":\"a\",\"id\":\"b\"}", "", "is not valid JSON" );
  }

  @Test
  public void buildsPathsFromJacksonReferences() {
    try {
      json.read( "{\"schemaVersion\":1,\"model\":{\"measures\":[{\"name\":\"m\",\"aggregation\":\"MEDIAN\"}]}}" );
      fail( "expected a format error" );
    } catch ( DatasetFormatException e ) {
      assertEquals( "model.measures[0].aggregation", e.getPath() );
    }
  }

  private void assertFails( String input, String path, String messagePart ) {
    try {
      json.read( input );
      fail( "expected a format error for " + input );
    } catch ( DatasetFormatException e ) {
      assertEquals( "path for " + input, path, e.getPath() );
      assertTrue( "message \"" + e.getMessage() + "\" should contain \"" + messagePart + "\"", e.getMessage().contains( messagePart ) );
    }
  }
}
