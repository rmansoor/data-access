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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Loads an uploaded CSV file into a new table on the staging connection, one table per load.
 * <p>
 * The file is read twice and never held in memory. The first pass checks every value against the
 * type detected from the first lines, as the wizard's sample does; a column with a value that
 * doesn't fit is loaded as text and reported, rather than failing the load or dropping the row as
 * the wizard's transformation does. The second pass creates the table, sized from the first, and
 * inserts in batches in one transaction. If anything fails the table is dropped.
 * <p>
 * Each load makes a new table ({@code ds_<id>_<time>}), so readers of the previous one are never
 * left with a half-loaded table; the caller drops the old table once the dataset points at the new.
 */
public class CsvStager {

  private static final Log LOG = LogFactory.getLog( CsvStager.class );
  static final int BATCH = 1000;
  private static final int MAX_NAME = 30;
  private static final int MAX_DECIMAL_PRECISION = 38;
  private static final int MAX_WARNINGS = 20;

  private final CsvSourceAdapter csv;
  private final ConnectionProvider connections;
  private final Supplier<String> stagingConnection;
  private final Supplier<String> stagingSchema;
  private final long maxRows;
  private final LongSupplier clock;

  /**
   * @param stagingConnection the connection uploaded files are loaded into
   * @param stagingSchema the schema for staged tables; null or empty for the connection's default
   * @param maxRows the most data rows a file may have
   */
  public CsvStager( CsvSourceAdapter csv, ConnectionProvider connections, Supplier<String> stagingConnection,
                    Supplier<String> stagingSchema, long maxRows, LongSupplier clock ) {
    this.csv = csv;
    this.connections = connections;
    this.stagingConnection = stagingConnection;
    this.stagingSchema = stagingSchema;
    this.maxRows = maxRows;
    this.clock = clock;
  }

  /** The connection files are loaded into. */
  public String connection() {
    String name = stagingConnection.get();
    if ( name == null || name.trim().isEmpty() ) {
      throw new DatasetApiException( DatasetApiException.Code.NOT_IMPLEMENTED,
        "No staging connection is configured (data-access-staging-jndi in data-access/settings.xml)" );
    }
    return name.trim();
  }

  /** Loads the file into a new table on {@link #connection()}. */
  public StagedTable stage( String datasetId, Source source ) {
    CsvSourceAdapter.CsvFile file = csv.open( source );
    Profile profile = profile( file );
    String connection = connection();
    try ( Connection c = open( connection ) ) {
      boolean autoCommit = c.getAutoCommit();
      c.setAutoCommit( false );
      try {
        return load( c, connection, datasetId, file, profile );
      } finally {
        restore( c, autoCommit );
      }
    } catch ( SQLException e ) {
      throw JdbcSourceSupport.failed( "The staging database refused the load: " + e.getMessage(), e );
    }
  }

  /** Drops a table an earlier load made; logs rather than fails, since the dataset no longer uses it. */
  public void drop( String connection, String table ) {
    if ( table == null || table.trim().isEmpty() ) {
      return;
    }
    try ( Connection c = open( connection == null ? connection() : connection ) ) {
      boolean autoCommit = c.getAutoCommit();
      c.setAutoCommit( true );
      try ( Statement st = c.createStatement() ) {
        st.execute( "DROP TABLE " + qualified( c.getMetaData(), table ) );
      } finally {
        restore( c, autoCommit );
      }
    } catch ( SQLException | RuntimeException e ) {
      LOG.warn( "Could not drop staged table \"" + table + "\" on \"" + connection + "\"; drop it by hand", e );
    }
  }

  // ------------------------------------------------------------------------------------------------
  // First pass: what each column holds.

  /** What the first pass learned about each column. */
  static final class Profile {
    final CsvSourceAdapter.Detected detected;
    final ColumnType[] types;
    final int[] maxLength;
    final int[] integerDigits;
    final int[] scale;
    final List<String> warnings = new ArrayList<>();
    long rows;
    long wideLines;

    Profile( CsvSourceAdapter.Detected detected ) {
      this.detected = detected;
      int width = detected.columns.size();
      types = new ColumnType[ width ];
      maxLength = new int[ width ];
      integerDigits = new int[ width ];
      scale = new int[ width ];
      for ( int i = 0; i < width; i++ ) {
        types[ i ] = detected.columns.get( i ).getType();
      }
    }

    int width() {
      return types.length;
    }

    void warn( String warning ) {
      if ( warnings.size() < MAX_WARNINGS ) {
        warnings.add( warning );
      }
    }
  }

  private Profile profile( CsvSourceAdapter.CsvFile file ) {
    try {
      List<List<String>> head = new ArrayList<>();
      CsvSourceAdapter.eachLine( file, ( values, n ) -> {
        head.add( values );
        return head.size() < file.detectLines;
      } );
      Profile p = new Profile( CsvSourceAdapter.detect( head, file ) );
      if ( p.width() == 0 ) {
        throw JdbcSourceSupport.invalid( CsvSourceAdapter.FILE + ".path", "names an empty file" );
      }
      CsvSourceAdapter.eachLine( file, ( values, n ) -> {
        if ( n <= file.header ) {
          return true;
        }
        if ( ++p.rows > maxRows ) {
          throw JdbcSourceSupport.failed( "The file has more than " + maxRows + " rows, the most this server loads", null );
        }
        if ( values.size() > p.width() ) {
          p.wideLines++;
        }
        for ( int c = 0; c < p.width(); c++ ) {
          check( p, c, c < values.size() ? values.get( c ) : null, n );
        }
        return true;
      } );
      if ( p.wideLines > 0 ) {
        p.warn( p.wideLines + " line(s) had more values than the " + p.width() + " columns detected; the extra values were left out" );
      }
      return p;
    } catch ( IOException | KettleException e ) {
      throw JdbcSourceSupport.failed( "The file could not be read: " + e.getMessage(), e );
    }
  }

  private static void check( Profile p, int c, String text, long line ) {
    if ( text == null ) {
      return;
    }
    p.maxLength[ c ] = Math.max( p.maxLength[ c ], text.length() );
    if ( p.types[ c ] == ColumnType.STRING || text.isEmpty() ) {
      return;
    }
    Object v;
    try {
      v = CsvSourceAdapter.parse( text, p.detected.metas.get( c ), p.types[ c ] );
    } catch ( KettleException | RuntimeException e ) {
      v = null;
    }
    if ( v == null ) {
      p.warn( "Column \"" + p.detected.columns.get( c ).getName() + "\" was detected as " + p.types[ c ] + " but line " + line
        + " has \"" + abbreviate( text ) + "\"; it was loaded as text" );
      p.types[ c ] = ColumnType.STRING;
      return;
    }
    if ( v instanceof BigDecimal ) {
      BigDecimal d = ( (BigDecimal) v ).stripTrailingZeros();
      int s = Math.max( 0, d.scale() );
      p.scale[ c ] = Math.max( p.scale[ c ], s );
      p.integerDigits[ c ] = Math.max( p.integerDigits[ c ], Math.max( 1, d.precision() - d.scale() ) );
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Second pass: the table and its rows.

  private StagedTable load( Connection c, String connection, String datasetId, CsvSourceAdapter.CsvFile file, Profile p )
    throws SQLException {
    DatabaseMetaData md = c.getMetaData();
    String table = qualifiedName( tableName( md, datasetId ) );
    List<StagedTable.Column> columns = columns( md, p );
    String quotedTable = qualified( md, table );
    StringBuilder ddl = new StringBuilder( "CREATE TABLE " ).append( quotedTable ).append( " (" );
    StringBuilder insert = new StringBuilder( "INSERT INTO " ).append( quotedTable ).append( " (" );
    for ( int i = 0; i < columns.size(); i++ ) {
      String sep = i == 0 ? "" : ", ";
      ddl.append( sep ).append( quote( md, columns.get( i ).getName() ) ).append( ' ' ).append( columns.get( i ).getSqlType() );
      insert.append( sep ).append( quote( md, columns.get( i ).getName() ) );
    }
    ddl.append( ')' );
    insert.append( ") VALUES (" ).append( "?, ".repeat( columns.size() - 1 ) ).append( "?)" );

    try ( Statement st = c.createStatement() ) {
      st.execute( ddl.toString() );
    }
    // Some databases (Oracle, MySQL) commit DDL themselves; commit so the drop below is always possible.
    c.commit();
    try {
      long rows = insert( c, insert.toString(), file, p );
      c.commit();
      return new StagedTable( connection, table, rows, columns, p.warnings );
    } catch ( SQLException | RuntimeException e ) {
      rollbackAndDrop( c, quotedTable );
      throw e;
    } catch ( IOException | KettleException e ) {
      rollbackAndDrop( c, quotedTable );
      throw JdbcSourceSupport.failed( "The file could not be read: " + e.getMessage(), e );
    }
  }

  private static long insert( Connection c, String sql, CsvSourceAdapter.CsvFile file, Profile p )
    throws SQLException, IOException, KettleException {
    long[] rows = { 0 };
    try ( PreparedStatement ps = c.prepareStatement( sql ) ) {
      SQLException[] failure = { null };
      CsvSourceAdapter.eachLine( file, ( values, n ) -> {
        if ( n <= file.header ) {
          return true;
        }
        try {
          for ( int i = 0; i < p.width(); i++ ) {
            bind( ps, i + 1, i < values.size() ? values.get( i ) : null, p, i );
          }
          ps.addBatch();
          if ( ++rows[ 0 ] % BATCH == 0 ) {
            ps.executeBatch();
          }
          return true;
        } catch ( SQLException e ) {
          failure[ 0 ] = new SQLException( "Line " + n + ": " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e );
          return false;
        }
      } );
      if ( failure[ 0 ] != null ) {
        throw failure[ 0 ];
      }
      if ( rows[ 0 ] % BATCH != 0 ) {
        ps.executeBatch();
      }
    }
    return rows[ 0 ];
  }

  private static void bind( PreparedStatement ps, int index, String text, Profile p, int c ) throws SQLException {
    ColumnType type = p.types[ c ];
    Object v;
    try {
      v = CsvSourceAdapter.parse( text, p.detected.metas.get( c ), type );
    } catch ( KettleException e ) {
      throw new SQLException( "\"" + abbreviate( text ) + "\" no longer reads as " + type, e );
    }
    if ( v == null ) {
      ps.setNull( index, sqlType( type ) );
      return;
    }
    switch ( type ) {
      case INTEGER:
        if ( v instanceof Long ) {
          ps.setLong( index, (Long) v );
        } else {
          ps.setBigDecimal( index, new BigDecimal( v.toString() ) );
        }
        break;
      case DECIMAL:
        ps.setBigDecimal( index, v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal( v.toString() ) );
        break;
      case BOOLEAN:
        ps.setBoolean( index, (Boolean) v );
        break;
      case DATE:
        ps.setDate( index, Date.valueOf( LocalDate.parse( v.toString() ) ) );
        break;
      case TIMESTAMP:
        ps.setTimestamp( index, Timestamp.valueOf( LocalDateTime.parse( v.toString() ) ) );
        break;
      default:
        ps.setString( index, v.toString() );
    }
  }

  private static int sqlType( ColumnType type ) {
    switch ( type ) {
      case INTEGER:
        return Types.BIGINT;
      case DECIMAL:
        return Types.DECIMAL;
      case BOOLEAN:
        return Types.BOOLEAN;
      case DATE:
        return Types.DATE;
      case TIMESTAMP:
        return Types.TIMESTAMP;
      default:
        return Types.VARCHAR;
    }
  }

  private static void rollbackAndDrop( Connection c, String quotedTable ) {
    try {
      c.rollback();
      try ( Statement st = c.createStatement() ) {
        st.execute( "DROP TABLE " + quotedTable );
      }
      c.commit();
    } catch ( SQLException e ) {
      LOG.warn( "Could not drop the partly loaded table " + quotedTable + "; drop it by hand", e );
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Names and types in the staging database's own terms.

  /** Column names safe in any database: lower-case letters, digits and underscores, unique, short. */
  private List<StagedTable.Column> columns( DatabaseMetaData md, Profile p ) throws SQLException {
    TypeNames types = new TypeNames( md );
    int max = limit( md.getMaxColumnNameLength() );
    Set<String> used = new HashSet<>();
    List<StagedTable.Column> columns = new ArrayList<>();
    for ( int c = 0; c < p.width(); c++ ) {
      String source = p.detected.columns.get( c ).getName();
      String name = unique( identifier( source, "column_" + ( c + 1 ), max ), used, max );
      columns.add( new StagedTable.Column( source, cased( md, name ), p.types[ c ], types.ddl( p, c ) ) );
    }
    return columns;
  }

  private String tableName( DatabaseMetaData md, String datasetId ) throws SQLException {
    String stamp = Long.toString( clock.getAsLong(), 36 );
    int max = limit( md.getMaxTableNameLength() );
    String base = identifier( "ds_" + datasetId, "ds", max - stamp.length() - 1 );
    return cased( md, base + "_" + stamp );
  }

  private String qualifiedName( String table ) {
    String schema = stagingSchema.get();
    return schema == null || schema.trim().isEmpty() ? table : schema.trim() + "." + table;
  }

  static String identifier( String text, String fallback, int max ) {
    // Accents dropped ("Ü" -> "u"), then anything else that isn't a letter, digit or underscore.
    String plain = text == null ? "" : Normalizer.normalize( text, Normalizer.Form.NFD ).replaceAll( "\\p{M}", "" );
    String s = plain.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9_]+", "_" ).replaceAll( "_+", "_" )
      .replaceAll( "^_|_$", "" );
    if ( s.isEmpty() ) {
      s = fallback;
    }
    if ( !Character.isLetter( s.charAt( 0 ) ) ) {
      s = "c_" + s;
    }
    return s.length() > max ? s.substring( 0, max ).replaceAll( "_$", "" ) : s;
  }

  private static String unique( String name, Set<String> used, int max ) {
    String candidate = name;
    for ( int n = 2; !used.add( candidate ); n++ ) {
      String suffix = "_" + n;
      candidate = ( name.length() + suffix.length() > max ? name.substring( 0, max - suffix.length() ) : name ) + suffix;
    }
    return candidate;
  }

  private static int limit( int reported ) {
    return reported > 0 ? Math.min( reported, MAX_NAME ) : MAX_NAME;
  }

  /** Stored the way the database stores unquoted names, so the table is easy to query by hand too. */
  private static String cased( DatabaseMetaData md, String name ) throws SQLException {
    return md.storesUpperCaseIdentifiers() ? name.toUpperCase( Locale.ROOT ) : name;
  }

  private static String quote( DatabaseMetaData md, String name ) throws SQLException {
    String q = md.getIdentifierQuoteString();
    if ( q == null || q.trim().isEmpty() ) {
      return name;
    }
    q = q.trim();
    return q + name.replace( q, q + q ) + q;
  }

  private static String qualified( DatabaseMetaData md, String table ) throws SQLException {
    int dot = table.indexOf( '.' );
    return dot < 0 ? quote( md, table ) : quote( md, table.substring( 0, dot ) ) + "." + quote( md, table.substring( dot + 1 ) );
  }

  /** The database's own names for the types we create, from {@link DatabaseMetaData#getTypeInfo()}. */
  static final class TypeNames {
    private final Map<Integer, List<String>> names = new HashMap<>();
    private final Map<Integer, Long> precision = new HashMap<>();

    TypeNames( DatabaseMetaData md ) throws SQLException {
      try ( ResultSet rs = md.getTypeInfo() ) {
        while ( rs.next() ) {
          int type = rs.getInt( "DATA_TYPE" );
          String name = rs.getString( "TYPE_NAME" );
          names.computeIfAbsent( type, t -> new ArrayList<>() ).add( name );
          if ( name.equalsIgnoreCase( standardName( type ) ) || !precision.containsKey( type ) ) {
            precision.put( type, rs.getLong( "PRECISION" ) );
          }
        }
      }
    }

    String ddl( Profile p, int c ) {
      switch ( p.types[ c ] ) {
        case INTEGER:
          if ( names.containsKey( Types.BIGINT ) ) {
            return first( "BIGINT", Types.BIGINT );
          }
          // Without a 64-bit integer type (Oracle), a whole-number NUMERIC holds the same values.
          return first( "NUMERIC", Types.NUMERIC, Types.DECIMAL ) + "(19,0)";
        case DECIMAL:
          int scale = p.scale[ c ];
          int digits = Math.max( 1, p.integerDigits[ c ] ) + scale;
          if ( digits > MAX_DECIMAL_PRECISION ) {
            return first( "DOUBLE PRECISION", Types.DOUBLE, Types.FLOAT );
          }
          return first( "DECIMAL", Types.DECIMAL, Types.NUMERIC ) + "(" + digits + "," + scale + ")";
        case BOOLEAN:
          return first( "SMALLINT", Types.BOOLEAN, Types.BIT, Types.SMALLINT );
        case DATE:
          return first( "DATE", Types.DATE );
        case TIMESTAMP:
          return first( "TIMESTAMP", Types.TIMESTAMP );
        default:
          int length = Math.max( 1, p.maxLength[ c ] );
          long max = precision.getOrDefault( Types.VARCHAR, 0L );
          if ( length > ( max > 0 ? max : 4000 ) ) {
            return first( "CLOB", Types.CLOB, Types.LONGVARCHAR );
          }
          return first( "VARCHAR", Types.VARCHAR ) + "(" + length + ")";
      }
    }

    /**
     * The database's name for the first of the types it has: the standard name if it lists that
     * (HSQLDB lists NVARCHAR before VARCHAR), else the first it lists (Oracle's VARCHAR2).
     */
    private String first( String standard, int... types ) {
      for ( int t : types ) {
        List<String> listed = names.get( t );
        if ( listed != null && !listed.isEmpty() ) {
          return listed.stream().filter( n -> n.equalsIgnoreCase( standardName( t ) ) ).findFirst().orElse( listed.get( 0 ) );
        }
      }
      return standard;
    }

    private static String standardName( int type ) {
      switch ( type ) {
        case Types.BIGINT:
          return "BIGINT";
        case Types.NUMERIC:
          return "NUMERIC";
        case Types.DECIMAL:
          return "DECIMAL";
        case Types.DOUBLE:
          return "DOUBLE PRECISION";
        case Types.FLOAT:
          return "FLOAT";
        case Types.BOOLEAN:
          return "BOOLEAN";
        case Types.BIT:
          return "BIT";
        case Types.SMALLINT:
          return "SMALLINT";
        case Types.DATE:
          return "DATE";
        case Types.TIMESTAMP:
          return "TIMESTAMP";
        case Types.CLOB:
          return "CLOB";
        case Types.LONGVARCHAR:
          return "LONG VARCHAR";
        default:
          return "VARCHAR";
      }
    }
  }

  // ------------------------------------------------------------------------------------------------

  private Connection open( String name ) throws SQLException {
    try {
      Connection c = connections.open( name );
      if ( c == null ) {
        throw new NoSuchElementException( name );
      }
      return c;
    } catch ( NoSuchElementException e ) {
      throw new DatasetApiException( DatasetApiException.Code.SOURCE_FAILED, "source",
        "The staging connection \"" + name + "\" doesn't exist", null, e );
    }
  }

  private static void restore( Connection c, boolean autoCommit ) {
    try {
      c.setAutoCommit( autoCommit );
    } catch ( SQLException e ) {
      LOG.warn( "Could not reset the staging connection after a load", e );
    }
  }

  private static String abbreviate( String text ) {
    return text.length() > 40 ? text.substring( 0, 40 ) + "…" : text;
  }
}
