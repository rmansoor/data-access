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

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tables on a named connection, given as "table" or "schema.table".
 * <p>
 * Each name is looked up in the database's own catalog (as written, then upper- and lower-case,
 * for databases that fold unquoted names), and SQL is only ever built from the names the database
 * returned, quoted, so a table name can't carry SQL.
 */
public class TablesSourceAdapter extends JdbcSourceSupport {

  static final String TABLES = "source.tables";

  public TablesSourceAdapter( ConnectionProvider connections, SourceLimits limits ) {
    super( connections, limits );
  }

  @Override
  public SourceType type() {
    return SourceType.TABLES;
  }

  @Override
  public List<SourceColumn> schema( Source source ) {
    requireTables( source );
    return withConnection( source.getConnection(), c -> {
      DatabaseMetaData md = c.getMetaData();
      List<SourceColumn> columns = new ArrayList<>();
      List<String> names = source.getTables();
      for ( int i = 0; i < names.size(); i++ ) {
        Table t = resolve( md, names.get( i ), TABLES + "[" + i + "]", source.getConnection() );
        try ( ResultSet rs = md.getColumns( t.catalog, escape( md, t.schema ), escape( md, t.name ), "%" ) ) {
          while ( rs.next() ) {
            int nullable = rs.getInt( "NULLABLE" );
            columns.add( new SourceColumn( rs.getString( "COLUMN_NAME" ),
              JdbcValues.columnType( rs.getInt( "DATA_TYPE" ), rs.getInt( "DECIMAL_DIGITS" ) ), rs.getString( "TYPE_NAME" ),
              nullable == DatabaseMetaData.columnNullableUnknown ? null : nullable == DatabaseMetaData.columnNullable,
              names.get( i ) ) );
          }
        }
      }
      return columns;
    } );
  }

  @Override
  public Sample sample( Source source, int limit, String table ) {
    requireTables( source );
    List<String> names = source.getTables();
    int index = table == null ? 0 : names.indexOf( table );
    if ( index < 0 ) {
      throw invalid( "table", "\"" + table + "\" is not one of this dataset's tables" );
    }
    String name = names.get( index );
    return withConnection( source.getConnection(), c -> {
      Table t = resolve( c.getMetaData(), name, TABLES + "[" + index + "]", source.getConnection() );
      String sql = "SELECT * FROM " + t.quoted( c );
      try ( Statement st = capped( c.createStatement(), limit + 1 ); ResultSet rs = st.executeQuery( sql ) ) {
        return JdbcValues.read( rs, JdbcValues.columns( rs.getMetaData(), name ), limit );
      }
    } );
  }

  // ------------------------------------------------------------------------------------------------

  private static void requireTables( Source source ) {
    if ( source.getTables() == null || source.getTables().isEmpty() ) {
      throw invalid( TABLES, "needs at least one table" );
    }
  }

  /** A table as the database names it. */
  static final class Table {
    final String catalog;
    final String schema;
    final String name;

    Table( String catalog, String schema, String name ) {
      this.catalog = catalog;
      this.schema = schema;
      this.name = name;
    }

    String quoted( Connection c ) throws SQLException {
      String q = c.getMetaData().getIdentifierQuoteString();
      return ( schema == null || schema.isEmpty() ? "" : quote( schema, q ) + "." ) + quote( name, q );
    }

    private static String quote( String identifier, String q ) {
      if ( q == null || q.trim().isEmpty() ) {
        // No quoting in this database: only plain names are safe to use.
        if ( !identifier.matches( "[A-Za-z_][A-Za-z0-9_$]*" ) ) {
          throw invalid( TABLES, "can't use \"" + identifier + "\": the database has no way to quote it" );
        }
        return identifier;
      }
      return q + identifier.replace( q, q + q ) + q;
    }
  }

  static Table resolve( DatabaseMetaData md, String given, String path, String connection ) throws SQLException {
    String[] parts = given == null ? new String[ 0 ] : given.split( "\\.", -1 );
    if ( parts.length < 1 || parts.length > 2 || Arrays.stream( parts ).anyMatch( p -> p.trim().isEmpty() ) ) {
      throw invalid( path, "must be \"table\" or \"schema.table\", not \"" + given + "\"" );
    }
    String schema = parts.length == 2 ? parts[ 0 ].trim() : null;
    String table = parts[ parts.length - 1 ].trim();
    for ( String[] candidate : spellings( schema, table ) ) {
      try ( ResultSet rs = md.getTables( null, escape( md, candidate[ 0 ] ), escape( md, candidate[ 1 ] ),
        new String[] { "TABLE", "VIEW" } ) ) {
        while ( rs.next() ) {
          String foundSchema = rs.getString( "TABLE_SCHEM" );
          String foundName = rs.getString( "TABLE_NAME" );
          // The patterns are escaped, but some drivers still match loosely: insist on the exact name.
          if ( foundName.equals( candidate[ 1 ] ) && ( candidate[ 0 ] == null || candidate[ 0 ].equals( foundSchema ) ) ) {
            return new Table( rs.getString( "TABLE_CAT" ), foundSchema, foundName );
          }
        }
      }
    }
    throw invalid( path, "names no table or view the connection \"" + connection + "\" can see (\"" + given + "\")" );
  }

  private static List<String[]> spellings( String schema, String table ) {
    Set<List<String>> seen = new LinkedHashSet<>();
    seen.add( Arrays.asList( schema, table ) );
    seen.add( Arrays.asList( upper( schema ), upper( table ) ) );
    seen.add( Arrays.asList( lower( schema ), lower( table ) ) );
    List<String[]> out = new ArrayList<>();
    for ( List<String> s : seen ) {
      out.add( new String[] { s.get( 0 ), s.get( 1 ) } );
    }
    return out;
  }

  private static String upper( String s ) {
    return s == null ? null : s.toUpperCase( Locale.ROOT );
  }

  private static String lower( String s ) {
    return s == null ? null : s.toLowerCase( Locale.ROOT );
  }

  /** A metadata search pattern that matches only this exact name. */
  static String escape( DatabaseMetaData md, String name ) throws SQLException {
    if ( name == null ) {
      return null;
    }
    String esc = md.getSearchStringEscape();
    if ( esc == null || esc.isEmpty() ) {
      return name;
    }
    return name.replace( esc, esc + esc ).replace( "_", esc + "_" ).replace( "%", esc + "%" );
  }
}
