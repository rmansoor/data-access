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

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class XlsxSourceTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Connection db;
  private final AtomicLong clock = new AtomicLong( 1_800_000_000_000L );

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:xlsx" + UUID.randomUUID(), "SA", "" );
  }

  @After
  public void tearDown() throws Exception {
    db.close();
  }

  private CsvSourceAdapter adapter() {
    return new CsvSourceAdapter( name -> {
      Path p = folder.getRoot().toPath().resolve( name );
      return Files.exists( p ) ? Optional.of( p ) : Optional.empty();
    }, new SourceLimits( 30, 100 ) );
  }

  /**
   * A workbook with a "Notes" sheet first, then "Sales": a title in row 1, row 2 empty, the header in
   * row 3, then rows of real dates (shown as 6-Jan-26), whole numbers and money (shown as 1,234.50).
   */
  private Path workbook( int salesRows ) throws Exception {
    Path p = folder.getRoot().toPath().resolve( "sales.xlsx" );
    try ( XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream( p ) ) {
      Sheet notes = wb.createSheet( "Notes" );
      notes.createRow( 0 ).createCell( 0 ).setCellValue( "Read me first" );
      Sheet sales = wb.createSheet( "Sales" );
      CellStyle date = wb.createCellStyle();
      date.setDataFormat( wb.getCreationHelper().createDataFormat().getFormat( "d-mmm-yy" ) );
      CellStyle stamp = wb.createCellStyle();
      stamp.setDataFormat( wb.getCreationHelper().createDataFormat().getFormat( "m/d/yy h:mm" ) );
      CellStyle money = wb.createCellStyle();
      money.setDataFormat( wb.getCreationHelper().createDataFormat().getFormat( "#,##0.00" ) );
      sales.createRow( 0 ).createCell( 0 ).setCellValue( "Sales 2026" );
      Row header = sales.createRow( 2 );
      String[] names = { "Region", "Day", "Units", "Revenue", "Shipped" };
      for ( int c = 0; c < names.length; c++ ) {
        header.createCell( c ).setCellValue( names[ c ] );
      }
      for ( int i = 0; i < salesRows; i++ ) {
        Row r = sales.createRow( 3 + i );
        r.createCell( 0 ).setCellValue( i % 2 == 0 ? "EMEA" : "NA" );
        r.createCell( 1 ).setCellValue( LocalDate.of( 2026, 1, 1 ).plusDays( i % 28 ) );
        r.getCell( 1 ).setCellStyle( date );
        r.createCell( 2 ).setCellValue( 10 + i );
        r.createCell( 3 ).setCellValue( 1234.5 + i );
        r.getCell( 3 ).setCellStyle( money );
        r.createCell( 4 ).setCellValue( LocalDateTime.of( 2026, 1, 2, 9, 30 ).plusDays( i % 28 ) );
        r.getCell( 4 ).setCellStyle( stamp );
      }
      wb.write( out );
    }
    return p;
  }

  private static Source source( String path, String sheet, int startRow ) {
    FileSource f = new FileSource();
    f.setFormat( FileFormat.XLSX );
    f.setPath( path );
    f.setHeaderRow( true );
    f.setSheet( sheet );
    f.setStartRow( startRow );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setFile( f );
    return s;
  }

  @Test
  public void samplesTheChosenSheetFromItsStartRowWithItsTypes() throws Exception {
    workbook( 5 );
    Sample sample = adapter().sample( source( "sales.xlsx", "Sales", 3 ), 10, null );
    assertEquals( List.of( "Notes", "Sales" ), sample.getSheets() );
    assertEquals( List.of( "Region:STRING", "Day:DATE", "Units:INTEGER", "Revenue:DECIMAL", "Shipped:TIMESTAMP" ),
      sample.getColumns().stream().map( c -> c.getName() + ":" + c.getType() ).collect( Collectors.toList() ) );
    List<Object> first = sample.getRows().get( 0 );
    assertEquals( "EMEA", first.get( 0 ) );
    assertEquals( "2026-01-01", first.get( 1 ) );
    assertEquals( 10L, ( (Number) first.get( 2 ) ).longValue() );
    assertEquals( 0, new BigDecimal( "1234.5" ).compareTo( new BigDecimal( first.get( 3 ).toString() ) ) );
    assertTrue( first.get( 4 ).toString(), first.get( 4 ).toString().startsWith( "2026-01-02T09:30" ) );
    assertEquals( 5, sample.getRows().size() );
  }

  @Test
  public void readsTheFirstSheetWhenNoneIsChosen() throws Exception {
    workbook( 2 );
    Sample sample = adapter().sample( source( "sales.xlsx", null, 0 ), 10, null );
    assertEquals( "Read me first", sample.getColumns().get( 0 ).getName() );
  }

  @Test
  public void aSheetTheWorkbookDoesntHaveIsRefused() throws Exception {
    workbook( 2 );
    try {
      adapter().sample( source( "sales.xlsx", "Budget", 1 ), 10, null );
      fail( "sampled" );
    } catch ( DatasetApiException e ) {
      assertEquals( "source.file.sheet", e.getPath() );
    }
  }

  @Test
  public void loadsTheSheetIntoATypedTable() throws Exception {
    workbook( 300 );
    CsvStager stager = new CsvStager( adapter(), TestConnections.pooled( db, "Staging" ), () -> "Staging", () -> null, 1_000_000,
      clock::incrementAndGet );
    StagedTable t = stager.stage( "sales", source( "sales.xlsx", "Sales", 3 ) );
    assertEquals( 300, t.getRows() );
    assertEquals( List.of( ColumnType.STRING, ColumnType.DATE, ColumnType.INTEGER, ColumnType.DECIMAL, ColumnType.TIMESTAMP ),
      t.getColumns().stream().map( StagedTable.Column::getType ).collect( Collectors.toList() ) );
    try ( Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT COUNT(*), SUM(UNITS), MIN(DAY), MAX(SHIPPED) FROM \"" + t.getTable() + "\"" ) ) {
      assertTrue( rs.next() );
      assertEquals( 300, rs.getInt( 1 ) );
      assertEquals( 300 * 10 + 299 * 300 / 2, rs.getLong( 2 ) );
      assertEquals( java.sql.Date.valueOf( "2026-01-01" ), rs.getDate( 3 ) );
      assertEquals( java.sql.Timestamp.valueOf( "2026-01-29 09:30:00" ), rs.getTimestamp( 4 ) );
    }
  }

  @Test
  public void stopsReadingOnceTheSampleHasEnoughRows() throws Exception {
    Path p = workbook( 2000 );
    List<Long> seen = new ArrayList<>();
    XlsxRows.each( p, "Sales", 3, ( values, n ) -> {
      seen.add( n );
      return n < 5;
    } );
    assertEquals( List.of( 1L, 2L, 3L, 4L, 5L ), seen );
  }

  // ------------------------------------------------------------------------------------------------
  // Uploading

  private DatasetFiles files() {
    return new DatasetFiles( () -> folder.getRoot().toPath().resolve( "uploads" ), 10 * 1024 * 1024 );
  }

  @Test
  public void storesAWorkbook() throws Exception {
    byte[] bytes = Files.readAllBytes( workbook( 1 ) );
    String stored = files().store( "sales", "Q1 Sales.xlsx", new ByteArrayInputStream( bytes ) );
    assertTrue( stored, stored.startsWith( "sales/" ) && stored.endsWith( ".xlsx" ) );
    assertTrue( files().locate( stored ).isPresent() );
  }

  @Test
  public void refusesOldExcelFilesAndTextCalledXlsx() {
    try {
      files().store( "sales", "Q1.xls", new ByteArrayInputStream( new byte[] { 1, 2, 3 } ) );
      fail( "stored" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.UNSUPPORTED_FILE, e.getCode() );
      assertTrue( e.getMessage(), e.getMessage().contains( "save it as an Excel Workbook (.xlsx)" ) );
    }
    try {
      files().store( "sales", "fake.xlsx", new ByteArrayInputStream( "a,b\n1,2\n".getBytes( StandardCharsets.UTF_8 ) ) );
      fail( "stored" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.UNSUPPORTED_FILE, e.getCode() );
      assertTrue( e.getMessage(), e.getMessage().contains( "isn't an Excel workbook" ) );
    }
  }
}
