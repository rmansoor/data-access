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

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.apache.commons.io.input.BOMInputStream;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.core.util.StringEvaluator;
import org.pentaho.di.trans.steps.fileinput.text.BufferedInputStreamReader;
import org.pentaho.di.trans.steps.fileinput.text.TextFileInputUtils;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileFormat;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.datasource.wizard.csv.CsvInspector;
import org.pentaho.platform.dataaccess.datasource.wizard.csv.CsvUtils;
import org.pentaho.platform.dataaccess.datasource.wizard.models.ColumnInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.models.CsvFileInfo;
import org.pentaho.reporting.libraries.base.util.CSVTokenizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Uploaded CSV files. Reading and type detection are the ones the data source wizard uses today
 * (Kettle's line reader and {@code StringEvaluator} with the wizard's number and date formats, ICU
 * encoding detection, BOM skipping; the non-deprecated successors of the classes it calls), so a file is understood the same way in both; the only change
 * is finer types: whole numbers apart from decimals, and timestamps apart from dates.
 */
public class CsvSourceAdapter implements SourceAdapter {

  static final String FILE = "source.file";

  private final FileLocator files;
  private final SourceLimits limits;

  public CsvSourceAdapter( FileLocator files, SourceLimits limits ) {
    this.files = files;
    this.limits = limits;
  }

  @Override
  public SourceType type() {
    return SourceType.FILE;
  }

  @Override
  public List<SourceColumn> schema( Source source ) {
    return read( source, 0 ).getColumns();
  }

  @Override
  public Sample sample( Source source, int limit, String table ) {
    return read( source, limit );
  }

  // ------------------------------------------------------------------------------------------------

  /** A file with how to read it decided: given in the definition, or detected as the wizard does. */
  static final class CsvFile {
    final Path path;
    final String encoding;
    final String delimiter;
    final String enclosure;
    final int header;
    final int detectLines;
    /** A workbook: read through {@link XlsxRows} instead of as text. */
    final boolean workbook;
    /** The workbook's sheet to read; null for the first. */
    final String sheet;
    /** The workbook row the table starts at, from 1. */
    final int startRow;

    CsvFile( Path path, String encoding, String delimiter, String enclosure, int header, int detectLines ) {
      this( path, encoding, delimiter, enclosure, header, detectLines, false, null, 1 );
    }

    CsvFile( Path path, String encoding, String delimiter, String enclosure, int header, int detectLines, boolean workbook,
             String sheet, int startRow ) {
      this.path = path;
      this.encoding = encoding;
      this.delimiter = delimiter;
      this.enclosure = enclosure;
      this.header = header;
      this.detectLines = detectLines;
      this.workbook = workbook;
      this.sheet = sheet;
      this.startRow = startRow;
    }
  }

  /** The columns detected from the first lines, and the Kettle metas that parse each column's text. */
  static final class Detected {
    final List<SourceColumn> columns;
    final List<ValueMetaInterface> metas;

    Detected( List<SourceColumn> columns, List<ValueMetaInterface> metas ) {
      this.columns = columns;
      this.metas = metas;
    }
  }

  /** Receives each line's values; returns false to stop reading. */
  @FunctionalInterface
  interface LineHandler {
    boolean line( List<String> values, long lineNumber );
  }

  private Sample read( Source source, int limit ) {
    CsvFile f = open( source );
    try {
      List<List<String>> lines = lines( f, Math.max( f.detectLines, f.header + limit + 1 ) );
      Sample sample = profile( lines, f, limit );
      return f.workbook ? new Sample( sample.getColumns(), sample.getRows(), sample.isTruncated(), XlsxRows.sheets( f.path ) )
        : sample;
    } catch ( IOException | KettleException e ) {
      throw JdbcSourceSupport.failed( "The file could not be read: " + e.getMessage(), e );
    }
  }

  /** Checks the file settings and decides what the definition leaves out. */
  CsvFile open( Source source ) {
    FileSource f = source.getFile();
    if ( f == null ) {
      throw JdbcSourceSupport.invalid( FILE, "is required" );
    }
    if ( f.getPath() == null || f.getPath().trim().isEmpty() ) {
      throw JdbcSourceSupport.invalid( FILE + ".path", "is required" );
    }
    Path path = files.locate( f.getPath() ).orElseThrow(
      () -> JdbcSourceSupport.invalid( FILE + ".path", "names no uploaded file (\"" + f.getPath() + "\")" ) );
    if ( f.getFormat() == FileFormat.XLSX ) {
      int header = f.isHeaderRow() ? 1 : 0;
      String sheet = blank( f.getSheet() ) ? null : f.getSheet();
      try {
        if ( sheet != null && !XlsxRows.sheets( path ).contains( sheet ) ) {
          throw JdbcSourceSupport.invalid( FILE + ".sheet", "names no sheet of the workbook (\"" + sheet + "\")" );
        }
      } catch ( IOException e ) {
        throw JdbcSourceSupport.failed( "The file could not be read: " + e.getMessage(), e );
      }
      return new CsvFile( path, null, null, null, header, Math.max( limits.getTypeDetectionRows(), header + 1 ), true, sheet,
        Math.max( 1, f.getStartRow() ) );
    }
    try {
      String encoding = blank( f.getEncoding() ) ? detectEncoding( path ) : f.getEncoding();
      if ( !Charset.isSupported( encoding ) ) {
        throw JdbcSourceSupport.invalid( FILE + ".encoding", "is not an encoding this server knows (\"" + encoding + "\")" );
      }
      int header = f.isHeaderRow() ? 1 : 0;
      // As the wizard does, the detection window counts the header row.
      int detectLines = Math.max( limits.getTypeDetectionRows(), header + 1 );
      String delimiter = blank( f.getDelimiter() ) ? guessDelimiter( path, encoding, detectLines ) : f.getDelimiter();
      String enclosure = f.getEnclosure() == null ? "\"" : f.getEnclosure();
      return new CsvFile( path, encoding, delimiter, enclosure, header, detectLines );
    } catch ( IOException e ) {
      throw JdbcSourceSupport.failed( "The file could not be read: " + e.getMessage(), e );
    }
  }

  private static Sample profile( List<List<String>> lines, CsvFile f, int limit ) {
    Detected detected = detect( lines, f );
    List<List<String>> data = lines.subList( Math.min( f.header, lines.size() ), lines.size() );
    int width = detected.columns.size();
    List<List<Object>> rows = new ArrayList<>();
    for ( int r = 0; r < Math.min( limit, data.size() ); r++ ) {
      List<String> raw = data.get( r );
      List<Object> row = new ArrayList<>( width );
      for ( int c = 0; c < width; c++ ) {
        row.add( value( c < raw.size() ? raw.get( c ) : null, detected.metas.get( c ), detected.columns.get( c ).getType() ) );
      }
      rows.add( row );
    }
    return new Sample( detected.columns, rows, data.size() > limit );
  }

  /** Column names and types from the file's first lines (the header, if any, and the detection window). */
  static Detected detect( List<List<String>> lines, CsvFile f ) {
    int width = lines.stream().limit( f.detectLines ).mapToInt( List::size ).max().orElse( 0 );
    List<String> headings = f.header > 0 && !lines.isEmpty() ? lines.get( 0 ) : List.of();
    List<List<String>> data = lines.subList( Math.min( f.header, lines.size() ), lines.size() );
    List<List<String>> window = data.subList( 0, Math.min( data.size(), Math.max( 0, f.detectLines - f.header ) ) );

    DecimalFormat df = new DecimalFormat( "000" );
    List<SourceColumn> columns = new ArrayList<>();
    List<ValueMetaInterface> metas = new ArrayList<>();
    for ( int c = 0; c < width; c++ ) {
      StringEvaluator eval = new StringEvaluator( false, CsvUtils.NUMBER_FORMATS, ColumnInfo.DATE_FORMATS );
      for ( List<String> row : window ) {
        eval.evaluateString( c < row.size() ? row.get( c ) : null );
      }
      ValueMetaInterface meta = eval.getAdvicedResult().getConversionMeta();
      metas.add( meta );
      String heading = c < headings.size() ? headings.get( c ) : null;
      String name = blank( heading ) ? CsvFileInfo.DEFAULT_COLUMN_NAME_PREFIX + df.format( c + 1 ) : heading;
      columns.add( new SourceColumn( name, columnType( meta ), meta.getConversionMask(), null, null ) );
    }
    return new Detected( columns, metas );
  }

  static ColumnType columnType( ValueMetaInterface meta ) {
    switch ( meta.getType() ) {
      case ValueMetaInterface.TYPE_INTEGER:
        return ColumnType.INTEGER;
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return ColumnType.DECIMAL;
      case ValueMetaInterface.TYPE_DATE:
      case ValueMetaInterface.TYPE_TIMESTAMP:
        String mask = meta.getConversionMask();
        return mask != null && mask.matches( ".*[Hhkms].*" ) ? ColumnType.TIMESTAMP : ColumnType.DATE;
      case ValueMetaInterface.TYPE_BOOLEAN:
        return ColumnType.BOOLEAN;
      default:
        return ColumnType.STRING;
    }
  }

  /** The text parsed as detection decided, or the text itself if a later row doesn't fit. */
  private static Object value( String text, ValueMetaInterface meta, ColumnType type ) {
    try {
      return parse( text, meta, type );
    } catch ( KettleException | RuntimeException e ) {
      return text;
    }
  }

  /**
   * The text as the detected type: Long, BigDecimal, Boolean, or an ISO date or timestamp string.
   * Null for null or empty text (and the text itself for STRING columns).
   *
   * @throws KettleException or a RuntimeException when the text doesn't fit the type
   */
  static Object parse( String text, ValueMetaInterface meta, ColumnType type ) throws KettleException {
    if ( text == null || type == ColumnType.STRING ) {
      return text;
    }
    if ( text.isEmpty() ) {
      return null;
    }
    // The text, described with the detected mask and symbols, converted to the detected type. Built
    // directly rather than through ValueMetaFactory, which needs Kettle's plugin registry.
    ValueMetaString asText = new ValueMetaString( "value" );
    asText.setConversionMask( meta.getConversionMask() );
    asText.setDecimalSymbol( meta.getDecimalSymbol() );
    asText.setGroupingSymbol( meta.getGroupingSymbol() );
    Object v = meta.convertData( asText, text );
    if ( v instanceof Double ) {
      return BigDecimal.valueOf( (Double) v );
    }
    if ( v instanceof Date ) {
      Timestamp t = new Timestamp( ( (Date) v ).getTime() );
      return type == ColumnType.DATE ? t.toLocalDateTime().toLocalDate().toString() : t.toLocalDateTime().toString();
    }
    return v;
  }

  private static List<List<String>> lines( CsvFile f, int max ) throws IOException, KettleException {
    List<List<String>> lines = new ArrayList<>();
    eachLine( f, ( values, n ) -> {
      lines.add( values );
      return lines.size() < max;
    } );
    return lines;
  }

  /** Reads the file line by line, header included, without holding it in memory. */
  static void eachLine( CsvFile f, LineHandler handler ) throws IOException, KettleException {
    if ( f.workbook ) {
      XlsxRows.each( f.path, f.sheet, f.startRow, handler );
      return;
    }
    int fileType = new CsvInspector().determineFileFormat( firstLine( f.path, f.encoding ) );
    try ( InputStream in = BOMInputStream.builder().setInputStream( Files.newInputStream( f.path ) ).get();
          BufferedInputStreamReader reader = new BufferedInputStreamReader( new InputStreamReader( in, f.encoding ) ) ) {
      StringBuilder buffer = new StringBuilder( 1000 );
      long n = 0;
      for ( String line = TextFileInputUtils.getLine( null, reader, fileType, buffer ); line != null;
            line = TextFileInputUtils.getLine( null, reader, fileType, buffer ) ) {
        n++;
        List<String> tokens = new ArrayList<>();
        try {
          CSVTokenizer t = new CSVTokenizer( line, f.delimiter, f.enclosure );
          while ( t.hasMoreTokens() ) {
            String token = t.nextToken();
            tokens.add( token == null ? null : token.trim() );
          }
        } catch ( IllegalArgumentException e ) {
          throw JdbcSourceSupport.failed( "Line " + n + " of the file can't be read: " + e.getMessage(), e );
        }
        if ( !handler.line( tokens, n ) ) {
          return;
        }
      }
    }
  }

  /** The first line with its line ending, which tells DOS files from Unix ones. */
  private static String firstLine( Path path, String encoding ) throws IOException {
    StringBuilder line = new StringBuilder();
    try ( Reader r = new InputStreamReader( Files.newInputStream( path ), encoding ) ) {
      for ( int c = r.read(); c > 0; c = r.read() ) {
        line.append( (char) c );
        if ( c == '\r' || c == '\n' ) {
          int next = r.read();
          if ( next == '\r' || next == '\n' ) {
            line.append( (char) next );
          }
          break;
        }
      }
    }
    return line.toString();
  }

  private static String guessDelimiter( Path path, String encoding, int lines ) throws IOException {
    StringBuilder text = new StringBuilder();
    try ( BufferedReader r = Files.newBufferedReader( path, Charset.forName( encoding ) ) ) {
      String line;
      for ( int i = 0; i < lines && ( line = r.readLine() ) != null; i++ ) {
        text.append( line ).append( '\n' );
      }
    }
    String guess = new CsvInspector().guessDelimiter( text.toString() );
    return guess == null ? "," : guess;
  }

  private static String detectEncoding( Path path ) throws IOException {
    byte[] head;
    try ( InputStream in = Files.newInputStream( path ) ) {
      head = in.readNBytes( 1024 );
    }
    CharsetDetector detector = new CharsetDetector();
    detector.setText( head );
    CharsetMatch match = detector.detect();
    return match == null ? "UTF-8" : match.getName();
  }

  private static boolean blank( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
