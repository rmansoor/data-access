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

import org.duckdb.DuckDBDriver;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileFormat;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ParquetSourceTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Connection db;
  private final AtomicLong clock = new AtomicLong( 1_800_000_000_000L );

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:parquet" + UUID.randomUUID(), "SA", "" );
  }

  @After
  public void tearDown() throws Exception {
    db.close();
  }

  /** Orders: id, region, day, shipped (timestamp), units, revenue (DECIMAL(10,2)), paid (boolean); i from 0. */
  private Path orders( int rows ) throws Exception {
    Path p = folder.getRoot().toPath().resolve( "orders.parquet" );
    try ( Connection c = new DuckDBDriver().connect( "jdbc:duckdb:", new Properties() ); Statement st = c.createStatement() ) {
      st.execute( "COPY (SELECT i AS id, ['EMEA','NA','APAC'][i % 3 + 1] AS region, DATE '2026-01-01' + (i % 28)::INTEGER AS day, "
        + "TIMESTAMP '2026-01-02 09:30:00' + to_days((i % 28)::INTEGER) AS shipped, (i % 40)::INTEGER AS units, "
        + "(100 + i * 0.25)::DECIMAL(10,2) AS revenue, i % 2 = 0 AS paid FROM range(" + rows + ") t(i)) TO '"
        + p.toAbsolutePath().toString().replace( "'", "''" ) + "' (FORMAT PARQUET)" );
    }
    return p;
  }

  private CsvSourceAdapter adapter() {
    return new CsvSourceAdapter( name -> {
      Path p = folder.getRoot().toPath().resolve( name );
      return Files.exists( p ) ? Optional.of( p ) : Optional.empty();
    }, new SourceLimits( 30, 100 ) );
  }

  private static Source source( String path ) {
    FileSource f = new FileSource();
    f.setFormat( FileFormat.PARQUET );
    f.setPath( path );
    f.setHeaderRow( true );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setFile( f );
    return s;
  }

  @Test
  public void samplesWithTheTypesTheFileDeclares() throws Exception {
    orders( 50 );
    Sample sample = adapter().sample( source( "orders.parquet" ), 10, null );
    assertEquals( List.of( "id:INTEGER", "region:STRING", "day:DATE", "shipped:TIMESTAMP", "units:INTEGER", "revenue:DECIMAL", "paid:BOOLEAN" ),
      sample.getColumns().stream().map( c -> c.getName() + ":" + c.getType() ).collect( Collectors.toList() ) );
    List<Object> second = sample.getRows().get( 1 );
    assertEquals( 1L, ( (Number) second.get( 0 ) ).longValue() );
    assertEquals( "NA", second.get( 1 ) );
    assertEquals( "2026-01-02", second.get( 2 ) );
    assertTrue( second.get( 3 ).toString(), second.get( 3 ).toString().startsWith( "2026-01-03T09:30" ) );
    assertEquals( 0, new BigDecimal( "100.25" ).compareTo( new BigDecimal( second.get( 5 ).toString() ) ) );
    assertEquals( Boolean.FALSE, second.get( 6 ) );
    assertEquals( 10, sample.getRows().size() );
    assertTrue( sample.isTruncated() );
  }

  @Test
  public void loadsTheFileIntoATypedTable() throws Exception {
    int n = 25_000;
    orders( n );
    CsvStager stager = new CsvStager( adapter(), TestConnections.pooled( db, "Staging" ), () -> "Staging", () -> null, 1_000_000,
      clock::incrementAndGet );
    StagedTable t = stager.stage( "orders", source( "orders.parquet" ) );
    assertEquals( n, t.getRows() );
    assertEquals( List.of( ColumnType.INTEGER, ColumnType.STRING, ColumnType.DATE, ColumnType.TIMESTAMP, ColumnType.INTEGER,
        ColumnType.DECIMAL, ColumnType.BOOLEAN ),
      t.getColumns().stream().map( StagedTable.Column::getType ).collect( Collectors.toList() ) );
    assertTrue( t.getWarnings().toString(), t.getWarnings().isEmpty() );
    long units = 0;
    BigDecimal revenue = BigDecimal.ZERO;
    for ( int i = 0; i < n; i++ ) {
      units += i % 40;
      revenue = revenue.add( new BigDecimal( "100" ).add( new BigDecimal( "0.25" ).multiply( BigDecimal.valueOf( i ) ) ) );
    }
    try ( Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT COUNT(*), SUM(UNITS), SUM(REVENUE), MAX(DAY), MAX(SHIPPED), SUM(CASE WHEN PAID THEN 1 ELSE 0 END) FROM \""
            + t.getTable() + "\"" ) ) {
      assertTrue( rs.next() );
      assertEquals( n, rs.getInt( 1 ) );
      assertEquals( units, rs.getLong( 2 ) );
      assertEquals( 0, revenue.compareTo( rs.getBigDecimal( 3 ) ) );
      assertEquals( java.sql.Date.valueOf( "2026-01-28" ), rs.getDate( 4 ) );
      assertEquals( java.sql.Timestamp.valueOf( "2026-01-29 09:30:00" ), rs.getTimestamp( 5 ) );
      assertEquals( n / 2, rs.getInt( 6 ) );
    }
  }

  @Test
  public void stopsReadingOnceTheHandlerHasEnough() throws Exception {
    Path p = orders( 100_000 );
    List<Long> seen = new ArrayList<>();
    ParquetRows.each( p, ( values, n ) -> {
      seen.add( n );
      return n < 4;
    } );
    assertEquals( List.of( 1L, 2L, 3L, 4L ), seen );
  }

  @Test
  public void mapsTheFilesTypes() {
    assertEquals( ColumnType.INTEGER, ParquetRows.columnType( "UBIGINT" ) );
    assertEquals( ColumnType.DECIMAL, ParquetRows.columnType( "DECIMAL(18,3)" ) );
    assertEquals( ColumnType.DECIMAL, ParquetRows.columnType( "DOUBLE" ) );
    assertEquals( ColumnType.TIMESTAMP, ParquetRows.columnType( "TIMESTAMP WITH TIME ZONE" ) );
    assertEquals( ColumnType.STRING, ParquetRows.columnType( "STRUCT(a INTEGER)" ) );
    assertEquals( ColumnType.STRING, ParquetRows.columnType( "TIME" ) );
  }

  @Test
  public void storesAParquetFileAndRefusesAnythingElseCalledOne() throws Exception {
    DatasetFiles files = new DatasetFiles( () -> folder.getRoot().toPath().resolve( "uploads" ), 10 * 1024 * 1024 );
    String stored = files.store( "orders", "orders.parquet", new ByteArrayInputStream( Files.readAllBytes( orders( 10 ) ) ) );
    assertTrue( stored, stored.endsWith( ".parquet" ) );
    try {
      files.store( "orders", "fake.parquet", new ByteArrayInputStream( "a,b\n".getBytes( StandardCharsets.UTF_8 ) ) );
      fail( "stored" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.UNSUPPORTED_FILE, e.getCode() );
      assertTrue( e.getMessage(), e.getMessage().contains( "isn't a Parquet file" ) );
    }
  }
}
