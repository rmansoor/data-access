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

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads an Excel workbook (.xlsx) a row at a time, the way {@link CsvSourceAdapter} reads a CSV file,
 * so sampling, type detection and loading work the same. The sheet is streamed (SAX), not loaded,
 * so a large sheet takes little memory.
 * <p>
 * Each cell becomes text that reads the same whatever the workbook's display formats: a date as
 * {@code yyyy-MM-dd} (with {@code HH:mm:ss} when it has a time), a number without grouping or
 * exponent, anything else as Excel shows it. Empty rows are skipped; rows above {@code startRow}
 * (titles, notes) are left out.
 */
final class XlsxRows {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern( "yyyy-MM-dd", Locale.ROOT );
  private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss", Locale.ROOT );

  private XlsxRows() {
  }

  /** The workbook's sheet names, in order. */
  static List<String> sheets( Path path ) throws IOException {
    try ( OPCPackage pkg = open( path ) ) {
      List<String> names = new ArrayList<>();
      XSSFReader.SheetIterator it = sheetIterator( pkg );
      while ( it.hasNext() ) {
        it.next().close(); // only the name is wanted; the iterator opens each sheet's data
        names.add( it.getSheetName() );
      }
      return names;
    }
  }

  /**
   * Hands each non-empty row of {@code sheet} (the first sheet when null) from {@code startRow} on to
   * the handler, numbered from 1, until it returns false.
   *
   * @throws IllegalArgumentException when the workbook has no such sheet
   */
  static void each( Path path, String sheet, int startRow, CsvSourceAdapter.LineHandler handler ) throws IOException {
    try ( OPCPackage pkg = open( path ) ) {
      XSSFReader reader = new XSSFReader( pkg );
      ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable( pkg );
      StylesTable styles = reader.getStylesTable();
      XSSFReader.SheetIterator it = sheetIterator( pkg );
      while ( it.hasNext() ) {
        try ( InputStream in = it.next() ) {
          if ( sheet == null || sheet.equals( it.getSheetName() ) ) {
            parse( in, styles, strings, Math.max( 1, startRow ), handler );
            return;
          }
        }
      }
      throw new IllegalArgumentException( "The workbook has no sheet named \"" + sheet + "\"" );
    } catch ( OpenXML4JException | SAXException | ParserConfigurationException e ) {
      throw new IOException( "The workbook can't be read: " + e.getMessage(), e );
    }
  }

  private static OPCPackage open( Path path ) throws IOException {
    try {
      return OPCPackage.open( path.toFile(), PackageAccess.READ );
    } catch ( OpenXML4JException | RuntimeException e ) {
      throw new IOException( "The file isn't an Excel workbook (.xlsx): " + e.getMessage(), e );
    }
  }

  private static XSSFReader.SheetIterator sheetIterator( OPCPackage pkg ) throws IOException {
    try {
      return (XSSFReader.SheetIterator) new XSSFReader( pkg ).getSheetsData();
    } catch ( OpenXML4JException e ) {
      throw new IOException( "The workbook can't be read: " + e.getMessage(), e );
    }
  }

  /** Thrown from inside the SAX parse to stop it once the handler has seen enough. */
  private static final class Stop extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Stop() {
      super( null, null, false, false );
    }
  }

  private static void parse( InputStream sheet, StylesTable styles, ReadOnlySharedStringsTable strings, int startRow,
                             CsvSourceAdapter.LineHandler handler )
    throws IOException, SAXException, ParserConfigurationException {
    Rows rows = new Rows( startRow, handler );
    XMLReader xml = XMLHelper.newXMLReader();
    xml.setContentHandler( new XSSFSheetXMLHandler( styles, null, strings, rows, new PlainFormatter(), false ) );
    try {
      xml.parse( new InputSource( sheet ) );
    } catch ( Stop stopped ) {
      // the handler had enough
    }
  }

  /** Collects a row's cells by column, filling gaps with nulls. */
  private static final class Rows implements XSSFSheetXMLHandler.SheetContentsHandler {
    private final int startRow;
    private final CsvSourceAdapter.LineHandler handler;
    private final List<String> values = new ArrayList<>();
    private boolean skipping;
    private long n;

    Rows( int startRow, CsvSourceAdapter.LineHandler handler ) {
      this.startRow = startRow;
      this.handler = handler;
    }

    @Override
    public void startRow( int rowNum ) {
      values.clear();
      skipping = rowNum + 1 < startRow; // rowNum counts from 0
    }

    @Override
    public void cell( String reference, String formatted, XSSFComment comment ) {
      if ( skipping || reference == null ) {
        return;
      }
      int column = new CellReference( reference ).getCol();
      while ( values.size() < column ) {
        values.add( null );
      }
      String text = formatted == null ? null : formatted.trim();
      values.add( text == null || text.isEmpty() ? null : text );
    }

    @Override
    public void endRow( int rowNum ) {
      if ( skipping || values.stream().allMatch( v -> v == null ) ) {
        return;
      }
      if ( !handler.line( new ArrayList<>( values ), ++n ) ) {
        throw new Stop();
      }
    }
  }

  /** Dates as ISO text and numbers in plain digits, whatever display format the workbook gives them. */
  static final class PlainFormatter extends DataFormatter {
    PlainFormatter() {
      super( Locale.ROOT );
    }

    @Override
    public String formatRawCellContents( double value, int formatIndex, String formatString, boolean use1904Windowing ) {
      if ( DateUtil.isADateFormat( formatIndex, formatString ) && DateUtil.isValidExcelDate( value ) ) {
        LocalDateTime t = DateUtil.getLocalDateTime( value, use1904Windowing, true );
        boolean timeOfDay = t.toLocalTime().toSecondOfDay() != 0;
        // A time alone (no date part) stays as Excel would show it.
        if ( value < 1 && timeOfDay ) {
          return super.formatRawCellContents( value, formatIndex, formatString, use1904Windowing );
        }
        return ( timeOfDay ? MOMENT : DAY ).format( t.atOffset( ZoneOffset.UTC ) );
      }
      return BigDecimal.valueOf( value ).stripTrailingZeros().toPlainString();
    }
  }
}
