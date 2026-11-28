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

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DatasetValidatorTest {

  private final DatasetJson json = new DatasetJson();
  private final DatasetValidator validator = new DatasetValidator();

  private List<String> problems( String definition ) {
    return validator.validate( json.read( definition ) ).stream().map( DatasetProblem::toString ).collect( Collectors.toList() );
  }

  @Test
  public void acceptsEveryExample() throws IOException {
    for ( String name : DatasetJsonTest.EXAMPLES ) {
      assertEquals( name, List.of(), validator.validate( json.read( DatasetJsonTest.example( name ) ) ) );
    }
  }

  @Test
  public void requiresTheBasics() {
    assertEquals( List.of(
      "id: is required",
      "name: is required",
      "source: is required",
      "columns: needs at least one column" ), problems( "{\"schemaVersion\":1}" ) );
  }

  @Test
  public void refusesIdsThatCouldEscapeTheRepositoryFolder() {
    List<String> found = problems( "{\"schemaVersion\":1,\"id\":\"../etc\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\"select 1\"},"
      + "\"columns\":[{\"name\":\"a\",\"type\":\"INTEGER\"}]}" );
    assertEquals( 1, found.size() );
    assertTrue( found.get( 0 ), found.get( 0 ).startsWith( "id: may use letters" ) );
  }

  @Test
  public void checksEachSourceTypeForWhatItNeeds() {
    String cols = ",\"columns\":[{\"name\":\"a\",\"type\":\"STRING\"}]}";
    assertEquals( List.of( "source.connection: is required", "source.tables: needs at least one table" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"TABLES\"}" + cols ) );
    assertEquals( List.of( "source.sql: is required", "source.tables: is not used by a SQL source; remove it" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\" \",\"tables\":[\"T\"]}" + cols ) );
    assertEquals( List.of( "source.file.path: is required", "source.file.encoding: \"UTF-99\" is not an encoding this server supports",
        "source.file.delimiter: must be a single character" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"FILE\",\"file\":{\"format\":\"CSV\",\"encoding\":\"UTF-99\",\"delimiter\":\";;\"}}" + cols ) );
    assertEquals( List.of( "source.tables[1]: \"T\" is listed twice" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"TABLES\",\"connection\":\"c\",\"tables\":[\"T\",\"T\"]}" + cols ) );
  }

  @Test
  public void checksTheColumnsLeftOut() {
    String head = "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"TABLES\",\"connection\":\"c\",\"tables\":[\"A\",\"B\"]},"
      + "\"columns\":[{\"name\":\"ID\",\"table\":\"A\",\"type\":\"INTEGER\"},{\"name\":\"B_ID\",\"table\":\"B\",\"sourceName\":\"ID\",\"type\":\"INTEGER\"}],"
      + "\"relationships\":[{\"fromTable\":\"A\",\"fromColumn\":\"ID\",\"toTable\":\"B\",\"toColumn\":\"ID\",\"joinType\":\"INNER\",\"cardinality\":\"MANY_TO_ONE\"}],";
    assertEquals( List.of(), problems( head + "\"excludedColumns\":[{\"table\":\"A\",\"sourceName\":\"NOTE\"}]}" ) );
    assertEquals( List.of(), problems( head + "\"excludedColumns\":[]}" ) );
    assertEquals( List.of(
      "excludedColumns[0].sourceName: is required",
      "excludedColumns[1].table: \"C\" is not one of the source's tables",
      "excludedColumns[2]: \"ID\" is kept as a column, so it can't also be left out" ),
      problems( head + "\"excludedColumns\":[{\"table\":\"A\"},{\"table\":\"C\",\"sourceName\":\"X\"},{\"table\":\"B\",\"sourceName\":\"ID\"}]}" ) );
  }

  @Test
  public void findsDuplicateColumnsIgnoringCaseAndMissingTypes() {
    assertEquals( List.of( "columns[1].name: \"Region\" is used by another column", "columns[2].type: is required" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\"q\"},"
        + "\"columns\":[{\"name\":\"region\",\"type\":\"STRING\"},{\"name\":\"Region\",\"type\":\"STRING\"},{\"name\":\"x\"}]}" ) );
  }

  @Test
  public void requiresATableForEachColumnWhenThereAreSeveralTables() {
    assertEquals( List.of( "columns[0].table: is required when the source has more than one table",
        "columns[1].table: \"Z\" is not one of the source's tables" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"TABLES\",\"connection\":\"c\",\"tables\":[\"A\",\"B\"]},"
        + "\"columns\":[{\"name\":\"x\",\"type\":\"STRING\"},{\"name\":\"y\",\"table\":\"Z\",\"type\":\"STRING\"}]}" ) );
  }

  @Test
  public void checksTheModelAgainstTheColumns() {
    assertEquals( List.of(
        "model.dimensions[0].levels[1]: \"city\" is not a column of this dataset",
        "model.dimensions[1].levels: needs at least one level",
        "model.measures[0].name: \"region\" is used by another dimension or measure",
        "model.measures[0].aggregation: SUM needs a number column; \"region\" is STRING (use COUNT or COUNT_DISTINCT)",
        "model.measures[1].column: is required",
        "model.measures[1].aggregation: is required" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\"q\"},"
        + "\"columns\":[{\"name\":\"region\",\"type\":\"STRING\"},{\"name\":\"amount\",\"type\":\"DECIMAL\"}],"
        + "\"model\":{\"dimensions\":[{\"name\":\"Region\",\"levels\":[\"region\",\"city\"]},{\"name\":\"Empty\"}],"
        + "\"measures\":[{\"name\":\"region\",\"column\":\"region\",\"aggregation\":\"SUM\"},{\"name\":\"Other\"}]}}" ) );
  }

  @Test
  public void countsWorkOnAnyColumnType() {
    assertEquals( List.of(), problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\"q\"},"
      + "\"columns\":[{\"name\":\"region\",\"type\":\"STRING\"}],"
      + "\"model\":{\"measures\":[{\"name\":\"Regions\",\"column\":\"region\",\"aggregation\":\"COUNT_DISTINCT\"}]}}" ) );
  }

  @Test
  public void checksRelationshipsAgainstTheTables() {
    assertEquals( List.of(
        "relationships[0].toTable: \"Z\" is not one of the source's tables",
        "relationships[0].toColumn: is required",
        "relationships[1].toTable: must be a different table from fromTable",
        "relationships[1].joinType: is required" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"TABLES\",\"connection\":\"c\",\"tables\":[\"A\",\"B\"]},"
        + "\"columns\":[{\"name\":\"x\",\"table\":\"A\",\"type\":\"STRING\"}],"
        + "\"relationships\":[{\"fromTable\":\"A\",\"fromColumn\":\"k\",\"toTable\":\"Z\",\"joinType\":\"INNER\"},"
        + "{\"fromTable\":\"A\",\"fromColumn\":\"k\",\"toTable\":\"A\",\"toColumn\":\"k\"}]}" ) );
    assertEquals( List.of( "relationships: are only used by a TABLES source" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\"q\"},"
        + "\"columns\":[{\"name\":\"x\",\"type\":\"STRING\"}],"
        + "\"relationships\":[{\"fromTable\":\"A\",\"fromColumn\":\"k\",\"toTable\":\"B\",\"toColumn\":\"k\",\"joinType\":\"INNER\"}]}" ) );
  }

  @Test
  public void checksSecurityRulesAndRefresh() {
    assertEquals( List.of(
        "security.rules[0]: needs either a role or a user, not both",
        "security.rules[1]: needs either a role or a user, not both",
        "security.rules[1].column: \"zone\" is not a column of this dataset",
        "security.rules[1].values: needs at least one value",
        "refresh.cron: is required for a scheduled refresh",
        "refresh.timeZone: \"Mars/Olympus\" is not a known time zone" ),
      problems( "{\"schemaVersion\":1,\"id\":\"d\",\"name\":\"n\",\"source\":{\"type\":\"SQL\",\"connection\":\"c\",\"sql\":\"q\"},"
        + "\"columns\":[{\"name\":\"region\",\"type\":\"STRING\"}],"
        + "\"security\":{\"rules\":[{\"role\":\"R\",\"user\":\"u\",\"column\":\"region\",\"values\":[\"EU\"]},{\"column\":\"zone\"}]},"
        + "\"refresh\":{\"mode\":\"SCHEDULED\",\"timeZone\":\"Mars/Olympus\"}}" ) );
  }
}
