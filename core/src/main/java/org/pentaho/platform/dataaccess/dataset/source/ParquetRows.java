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
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Reads a Parquet file through DuckDB, a row at a time, the way {@link CsvSourceAdapter} reads a CSV
 * file: the first row is the column names, then each row's values as text in the same plain forms an
 * Excel workbook gives (dates {@code yyyy-MM-dd}, timestamps {@code yyyy-MM-dd HH:mm:ss}, numbers in
 * plain digits). The column types come from the file's own schema, not from guessing.
 * <p>
 * DuckDB streams the result, so a file larger than memory is read in chunks. It runs in-process with
 * a small memory limit and two threads, and never fetches extensions, so it stays off the network.
 */
final class ParquetRows {

  private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss", Locale.ROOT );

  private ParquetRows() {
  }

  /** The file's columns: names and types, from its schema. */
  static List<SourceColumn> schema( Path path ) throws IOException {
    try ( Connection c = connect(); Statement st = c.createStatement();
          ResultSet rs = st.executeQuery( "DESCRIBE SELECT * FROM " + read( path ) ) ) {
      List<SourceColumn> columns = new ArrayList<>();
      while ( rs.next() ) {
        String type = rs.getString( "column_type" );
        columns.add( new SourceColumn( rs.getString( "column_name" ), columnType( type ), type, null, null ) );
      }
      return columns;
    } catch ( SQLException e ) {
      throw new IOException( "The file isn't a Parquet file DuckDB can read: " + e.getMessage(), e );
    }
  }

  /** Hands the column names (line 1), then each row (lines 2…), to the handler until it returns false. */
  static void each( Path path, CsvSourceAdapter.LineHandler handler ) throws IOException {
    try ( Connection c = connect(); Statement st = c.createStatement() ) {
      st.setFetchSize( 2048 );
      try ( ResultSet rs = st.executeQuery( "SELECT * FROM " + read( path ) ) ) {
        int width = rs.getMetaData().getColumnCount();
        List<String> names = new ArrayList<>( width );
        for ( int i = 1; i <= width; i++ ) {
          names.add( rs.getMetaData().getColumnLabel( i ) );
        }
        long n = 1;
        if ( !handler.line( names, n ) ) {
          return;
        }
        while ( rs.next() ) {
          List<String> values = new ArrayList<>( width );
          for ( int i = 1; i <= width; i++ ) {
            values.add( text( rs.getObject( i ) ) );
          }
          if ( !handler.line( values, ++n ) ) {
            return;
          }
        }
      }
    } catch ( SQLException e ) {
      throw new IOException( "The Parquet file can't be read: " + e.getMessage(), e );
    }
  }

  /** The type a dataset column gets from a DuckDB (Parquet) type. */
  static ColumnType columnType( String duckType ) {
    String t = duckType == null ? "" : duckType.toUpperCase( Locale.ROOT );
    if ( t.equals( "BOOLEAN" ) ) {
      return ColumnType.BOOLEAN;
    } else if ( t.matches( "U?(TINYINT|SMALLINT|INTEGER|BIGINT)" ) ) {
      return ColumnType.INTEGER;
    } else if ( t.matches( "(U?HUGEINT|FLOAT|DOUBLE|REAL|DECIMAL.*)" ) ) {
      return ColumnType.DECIMAL;
    } else if ( t.equals( "DATE" ) ) {
      return ColumnType.DATE;
    } else if ( t.startsWith( "TIMESTAMP" ) ) {
      return ColumnType.TIMESTAMP;
    }
    return ColumnType.STRING; // VARCHAR, TIME, UUID, BLOB, and nested types (as their text)
  }

  static String text( Object v ) {
    if ( v == null ) {
      return null;
    } else if ( v instanceof LocalDateTime ) {
      return MOMENT.format( (LocalDateTime) v );
    } else if ( v instanceof OffsetDateTime ) {
      return MOMENT.format( ( (OffsetDateTime) v ).toLocalDateTime() );
    } else if ( v instanceof java.sql.Timestamp ) {
      return MOMENT.format( ( (java.sql.Timestamp) v ).toLocalDateTime() );
    } else if ( v instanceof LocalDate ) {
      return v.toString();
    } else if ( v instanceof java.sql.Date ) {
      return ( (java.sql.Date) v ).toLocalDate().toString();
    } else if ( v instanceof BigDecimal ) {
      return ( (BigDecimal) v ).stripTrailingZeros().toPlainString();
    } else if ( v instanceof Double || v instanceof Float ) {
      double d = ( (Number) v ).doubleValue();
      return Double.isFinite( d ) ? BigDecimal.valueOf( d ).stripTrailingZeros().toPlainString() : null;
    }
    return v.toString();
  }

  /** {@code read_parquet('<path>')}: the path is the server's own (an uploaded file), quoted for SQL. */
  private static String read( Path path ) {
    return "read_parquet('" + path.toAbsolutePath().toString().replace( "'", "''" ) + "')";
  }

  private static Connection connect() throws SQLException {
    Properties p = new Properties();
    p.setProperty( "jdbc_stream_results", "true" );
    // The driver is loaded from the plugin's lib, which DriverManager may not see: open it directly.
    Connection c = new DuckDBDriver().connect( "jdbc:duckdb:", p );
    try ( Statement st = c.createStatement() ) {
      st.execute( "SET memory_limit='512MB'" );
      st.execute( "SET threads=2" );
      st.execute( "SET autoinstall_known_extensions=false" );
      st.execute( "SET autoload_known_extensions=false" );
    } catch ( SQLException e ) {
      c.close();
      throw e;
    }
    return c;
  }
}
