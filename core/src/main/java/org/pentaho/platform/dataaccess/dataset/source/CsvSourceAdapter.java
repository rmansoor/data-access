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

  private Sample read( Source source, int limit ) {
    FileSource f = source.getFile();
    if ( f == null ) {
      throw JdbcSourceSupport.invalid( FILE, "is required" );
    }
    if ( f.getFormat() != null && f.getFormat() != FileFormat.CSV ) {
      throw JdbcSourceSupport.invalid( FILE + ".format", "is not supported yet (" + f.getFormat() + ")" );
    }
    if ( f.getPath() == null || f.getPath().trim().isEmpty() ) {
      throw JdbcSourceSupport.invalid( FILE + ".path", "is required" );
    }
    Path path = files.locate( f.getPath() ).orElseThrow(
      () -> JdbcSourceSupport.invalid( FILE + ".path", "names no uploaded file (\"" + f.getPath() + "\")" ) );
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
      List<List<String>> lines = lines( path, encoding, delimiter, enclosure, Math.max( detectLines, header + limit + 1 ) );
      return profile( lines, header, detectLines, limit );
    } catch ( IOException | KettleException e ) {
      throw JdbcSourceSupport.failed( "The file could not be read: " + e.getMessage(), e );
    }
  }

  private static Sample profile( List<List<String>> lines, int header, int detectLines, int limit ) {
    int width = lines.stream().mapToInt( List::size ).max().orElse( 0 );
    List<String> headings = header > 0 && !lines.isEmpty() ? lines.get( 0 ) : List.of();
    List<List<String>> data = lines.subList( Math.min( header, lines.size() ), lines.size() );
    List<List<String>> window = data.subList( 0, Math.min( data.size(), Math.max( 0, detectLines - header ) ) );

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

    List<List<Object>> rows = new ArrayList<>();
    for ( int r = 0; r < Math.min( limit, data.size() ); r++ ) {
      List<String> raw = data.get( r );
      List<Object> row = new ArrayList<>( width );
      for ( int c = 0; c < width; c++ ) {
        row.add( value( c < raw.size() ? raw.get( c ) : null, metas.get( c ), columns.get( c ).getType() ) );
      }
      rows.add( row );
    }
    return new Sample( columns, rows, data.size() > limit );
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
    if ( text == null || type == ColumnType.STRING ) {
      return text;
    }
    if ( text.isEmpty() ) {
      return null;
    }
    try {
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
    } catch ( KettleException | RuntimeException e ) {
      return text;
    }
  }

  private static List<List<String>> lines( Path path, String encoding, String delimiter, String enclosure, int max )
    throws IOException, KettleException {
    int fileType = new CsvInspector().determineFileFormat( firstLine( path, encoding ) );
    List<List<String>> lines = new ArrayList<>();
    try ( InputStream in = BOMInputStream.builder().setInputStream( Files.newInputStream( path ) ).get();
          BufferedInputStreamReader reader = new BufferedInputStreamReader( new InputStreamReader( in, encoding ) ) ) {
      StringBuilder buffer = new StringBuilder( 1000 );
      for ( String line = TextFileInputUtils.getLine( null, reader, fileType, buffer ); line != null && lines.size() < max;
            line = TextFileInputUtils.getLine( null, reader, fileType, buffer ) ) {
        List<String> tokens = new ArrayList<>();
        try {
          CSVTokenizer t = new CSVTokenizer( line, delimiter, enclosure );
          while ( t.hasMoreTokens() ) {
            String token = t.nextToken();
            tokens.add( token == null ? null : token.trim() );
          }
        } catch ( IllegalArgumentException e ) {
          throw JdbcSourceSupport.failed( "Line " + ( lines.size() + 1 ) + " of the file can't be read: " + e.getMessage(), e );
        }
        lines.add( tokens );
      }
    }
    return lines;
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
