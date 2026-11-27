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

package org.pentaho.platform.dataaccess.dataset.source;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Loading uploaded files into staging tables, against a real database (HSQLDB in memory). */
public class CsvStagerTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Connection db;
  private final AtomicLong clock = new AtomicLong( 1_800_000_000_000L );
  private String schema;

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:stage" + UUID.randomUUID(), "SA", "" );
  }

  @After
  public void tearDown() throws Exception {
    db.close();
  }

  private CsvStager stager( int detectionRows, long maxRows, int failOnBatch ) {
    CsvSourceAdapter csv = new CsvSourceAdapter( name -> {
      Path p = folder.getRoot().toPath().resolve( name );
      return Files.exists( p ) ? Optional.of( p ) : Optional.empty();
    }, new SourceLimits( 30, detectionRows ) );
    return new CsvStager( csv, TestConnections.pooled( db, "Staging", failOnBatch ), () -> "Staging", () -> schema, maxRows,
      clock::incrementAndGet );
  }

  private CsvStager stager() {
    return stager( 100, 1_000_000, -1 );
  }

  private Source file( String name, String text ) throws Exception {
    Files.write( folder.getRoot().toPath().resolve( name ), text.getBytes( StandardCharsets.UTF_8 ) );
    FileSource f = new FileSource();
    f.setPath( name );
    f.setHeaderRow( true );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setFile( f );
    return s;
  }

  private List<String> tables() throws Exception {
    List<String> names = new ArrayList<>();
    try ( ResultSet rs = db.getMetaData().getTables( null, null, "DS_%", new String[] { "TABLE" } ) ) {
      while ( rs.next() ) {
        names.add( rs.getString( "TABLE_SCHEM" ) + "." + rs.getString( "TABLE_NAME" ) );
      }
    }
    return names;
  }

  @Test
  public void loadsEveryRowInBatchesWithDetectedTypesAndSafeColumnNames() throws Exception {
    StringBuilder csv = new StringBuilder( "Order Id,Amount ($),Placed,Shipped,Customer,customer,2nd note\n" );
    int rows = CsvStager.BATCH * 2 + 17;
    for ( int i = 1; i <= rows; i++ ) {
      csv.append( i ).append( ',' ).append( i ).append( ".25,01/31/2026," ).append( i % 2 == 0 ).append( ",\"Smith, " )
        .append( i ).append( "\",x," ).append( i == 3 ? "" : "abc" ).append( '\n' );
    }
    StagedTable t = stager().stage( "monthly-orders", file( "orders.csv", csv.toString() ) );

    assertEquals( "Staging", t.getConnection() );
    assertTrue( t.getTable(), t.getTable().matches( "DS_MONTHLY_ORDERS_[0-9A-Z]+" ) );
    assertTrue( t.getTable().length() <= 30 );
    assertEquals( rows, t.getRows() );
    assertEquals( List.of(
        "Order Id->ORDER_ID:INTEGER(BIGINT)", "Amount ($)->AMOUNT:DECIMAL(DECIMAL(6,2))", "Placed->PLACED:DATE(DATE)",
        "Shipped->SHIPPED:BOOLEAN(BOOLEAN)", "Customer->CUSTOMER:STRING(VARCHAR(11))", "customer->CUSTOMER_2:STRING(VARCHAR(1))",
        "2nd note->C_2ND_NOTE:STRING(VARCHAR(3))" ),
      t.getColumns().stream().map( Object::toString ).collect( Collectors.toList() ) );
    assertTrue( t.getWarnings().isEmpty() );

    try ( Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT COUNT(*), SUM(ORDER_ID) FROM \"" + t.getTable() + "\"" ) ) {
      rs.next();
      assertEquals( rows, rs.getLong( 1 ) );
      assertEquals( (long) rows * ( rows + 1 ) / 2, rs.getLong( 2 ) );
    }
    try ( Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT * FROM \"" + t.getTable() + "\" WHERE ORDER_ID = 3" ) ) {
      rs.next();
      assertEquals( new BigDecimal( "3.25" ), rs.getBigDecimal( "AMOUNT" ) );
      assertEquals( Date.valueOf( "2026-01-31" ), rs.getDate( "PLACED" ) );
      assertFalse( rs.getBoolean( "SHIPPED" ) );
      assertEquals( "Smith, 3", rs.getString( "CUSTOMER" ) );
      assertEquals( "", rs.getString( "C_2ND_NOTE" ) );
    }
  }

  @Test
  public void loadsAColumnAsTextWhenALaterValueDoesNotFitAndSaysSo() throws Exception {
    Source s = file( "late.csv", "id,qty,stamp\n1,5,2026-01-31 09:30:00\n2,6,2026-02-01 10:00:00\n3,n/a,2026-02-02 11:15:00\n4,,\n" );
    StagedTable t = stager( 2, 1_000_000, -1 ).stage( "late", s );

    assertEquals( List.of( "id->ID:INTEGER(BIGINT)", "qty->QTY:STRING(VARCHAR(3))", "stamp->STAMP:TIMESTAMP(TIMESTAMP)" ),
      t.getColumns().stream().map( Object::toString ).collect( Collectors.toList() ) );
    assertEquals( List.of( "Column \"qty\" was detected as INTEGER but line 4 has \"n/a\"; it was loaded as text" ), t.getWarnings() );
    try ( Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT QTY, STAMP FROM \"" + t.getTable() + "\" ORDER BY ID" ) ) {
      rs.next();
      assertEquals( "5", rs.getString( 1 ) );
      assertEquals( Timestamp.valueOf( "2026-01-31 09:30:00" ), rs.getTimestamp( 2 ) );
      rs.next();
      rs.next();
      assertEquals( "n/a", rs.getString( 1 ) );
      rs.next();
      assertEquals( "", rs.getString( 1 ) );
      assertNull( rs.getTimestamp( 2 ) );
    }
  }

  @Test
  public void makesANewTableEachLoadAndDropsOnlyWhatItIsTold() throws Exception {
    Source s = file( "a.csv", "x\n1\n" );
    CsvStager stager = stager();
    StagedTable first = stager.stage( "a", s );
    StagedTable second = stager.stage( "a", s );
    assertFalse( first.getTable().equals( second.getTable() ) );
    assertEquals( 2, tables().size() );

    stager.drop( "Staging", first.getTable() );
    assertEquals( List.of( "PUBLIC." + second.getTable() ), tables() );
    stager.drop( "Staging", first.getTable() ); // already gone: logged, not thrown
  }

  @Test
  public void putsTablesInTheStagingSchemaWhenOneIsSet() throws Exception {
    try ( Statement st = db.createStatement() ) {
      st.execute( "CREATE SCHEMA STAGE" );
    }
    schema = "STAGE";
    StagedTable t = stager().stage( "a", file( "a.csv", "x\n1\n" ) );
    assertTrue( t.getTable(), t.getTable().startsWith( "STAGE.DS_A_" ) );
    assertEquals( List.of( t.getTable() ), tables() );
    stager().drop( "Staging", t.getTable() );
    assertTrue( tables().isEmpty() );
  }

  @Test
  public void dropsThePartlyLoadedTableWhenTheLoadFails() throws Exception {
    StringBuilder csv = new StringBuilder( "x\n" );
    for ( int i = 0; i < CsvStager.BATCH * 3; i++ ) {
      csv.append( i ).append( '\n' );
    }
    Source s = file( "big.csv", csv.toString() );
    DatasetApiException e = expect( Code.SOURCE_FAILED, () -> stager( 100, 1_000_000, 2 ).stage( "big", s ) );
    assertTrue( e.getMessage(), e.getMessage().contains( "disk full" ) );
    assertTrue( tables().isEmpty() );
  }

  @Test
  public void refusesFilesOverTheRowLimitBeforeTouchingTheDatabase() throws Exception {
    Source s = file( "many.csv", "x\n1\n2\n3\n" );
    DatasetApiException e = expect( Code.SOURCE_FAILED, () -> stager( 100, 2, -1 ).stage( "many", s ) );
    assertTrue( e.getMessage(), e.getMessage().contains( "more than 2 rows" ) );
    assertTrue( tables().isEmpty() );
  }

  @Test
  public void reportsLinesWithMoreValuesThanColumns() throws Exception {
    // Detection sees the header and first row only, so the wider lines after it are the extra ones.
    StagedTable t = stager( 2, 1_000_000, -1 ).stage( "wide", file( "wide.csv", "a,b\n1,2\n3,4,5\n6,7,8,9\n" ) );
    assertEquals( 3, t.getRows() );
    assertEquals( List.of( "2 line(s) had more values than the 2 columns detected; the extra values were left out" ), t.getWarnings() );
  }

  @Test
  public void namesTheStagingConnectionWhenItIsMissing() throws Exception {
    Source s = file( "a.csv", "x\n1\n" );
    CsvSourceAdapter csv = new CsvSourceAdapter( name -> Optional.of( folder.getRoot().toPath().resolve( name ) ), SourceLimits.standard() );
    expect( Code.SOURCE_FAILED, () -> new CsvStager( csv, TestConnections.pooled( db, "Staging" ), () -> "Gone", () -> null, 10,
      clock::incrementAndGet ).stage( "a", s ) );
    expect( Code.NOT_IMPLEMENTED, () -> new CsvStager( csv, TestConnections.pooled( db, "Staging" ), () -> " ", () -> null, 10,
      clock::incrementAndGet ).stage( "a", s ) );
  }

  @Test
  public void makesIdentifiersAnyDatabaseAccepts() {
    assertEquals( "order_id", CsvStager.identifier( "Order Id", "c", 30 ) );
    assertEquals( "c_2026", CsvStager.identifier( "2026", "c", 30 ) );
    assertEquals( "column_4", CsvStager.identifier( "  $%  ", "column_4", 30 ) );
    assertEquals( "uber_gro_e", CsvStager.identifier( "Über Große", "c", 30 ) );
    assertEquals( "abcde", CsvStager.identifier( "abcde_fgh", "c", 6 ) );
  }

  @Test
  public void parsesTheTypesADatasetChoosesStrictly() {
    assertEquals( 42L, CsvStager.parseAs( " +42 ", ColumnType.INTEGER ) );
    assertEquals( new BigDecimal( "-1.50" ), CsvStager.parseAs( "-1.50", ColumnType.DECIMAL ) );
    assertEquals( Boolean.TRUE, CsvStager.parseAs( "Yes", ColumnType.BOOLEAN ) );
    assertEquals( Boolean.FALSE, CsvStager.parseAs( "0", ColumnType.BOOLEAN ) );
    assertEquals( "2026-01-31", CsvStager.parseAs( "2026-01-31", ColumnType.DATE ) );
    assertEquals( "2026-01-31", CsvStager.parseAs( "01/31/2026", ColumnType.DATE ) );
    assertEquals( "2026-01-31T09:30", CsvStager.parseAs( "2026-01-31 09:30", ColumnType.TIMESTAMP ) );
    assertEquals( "2026-01-31T00:00", CsvStager.parseAs( "2026-01-31", ColumnType.TIMESTAMP ) );
    assertNull( CsvStager.parseAs( "  ", ColumnType.INTEGER ) );
    assertEquals( "007", CsvStager.parseAs( "007", ColumnType.STRING ) );
    for ( Object[] bad : new Object[][] { { "1.5", ColumnType.INTEGER }, { "n/a", ColumnType.DECIMAL },
      { "maybe", ColumnType.BOOLEAN }, { "31/31/2026", ColumnType.DATE } } ) {
      try {
        CsvStager.parseAs( (String) bad[ 0 ], (ColumnType) bad[ 1 ] );
        fail( "parsed " + bad[ 0 ] + " as " + bad[ 1 ] );
      } catch ( RuntimeException expected ) {
        // refused
      }
    }
  }

  private static DatasetApiException expect( Code code, Runnable call ) {
    try {
      call.run();
      fail( "expected " + code );
      return null;
    } catch ( DatasetApiException e ) {
      assertEquals( e.getMessage(), code, e.getCode() );
      return e;
    }
  }
}
