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

package org.pentaho.platform.dataaccess.dataset.model;

import org.pentaho.metadata.model.SqlDataSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Column;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.StagedColumn;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a dataset's rows are read from when it is queried: the connection, and the one table (or
 * query) holding them. A file's rows are in its staging table; a query is used as an inline SQL table.
 * Each kept column of the definition is mapped to its column in that table.
 */
public final class PhysicalSource {

  private final String connection;
  private final SqlDataSource dataSource;
  private final String schema;
  private final String table;
  private final String sql;
  private final Map<String, String> columns;

  private PhysicalSource( String connection, SqlDataSource dataSource, String schema, String table, String sql,
                          Map<String, String> columns ) {
    this.connection = connection;
    this.dataSource = dataSource;
    this.schema = schema;
    this.table = table;
    this.sql = sql;
    this.columns = columns;
  }

  /**
   * The physical source of a definition.
   *
   * @param connection the name of the connection the rows are read through (for a file, the staging connection)
   * @param dataSource that connection, as the metadata layer describes it
   * @throws DatasetApiException VALIDATION_FAILED when the definition can't be published yet: a file not
   *                             loaded, more than one table, or a column the source doesn't have
   */
  public static PhysicalSource of( DatasetDefinition d, String connection, SqlDataSource dataSource ) {
    Source s = d.getSource();
    if ( s == null || s.getType() == null ) {
      throw problem( "source", "is required" );
    }
    switch ( s.getType() ) {
      case FILE:
        return ofFile( d, s.getFile(), connection, dataSource );
      case SQL:
        return new PhysicalSource( connection, dataSource, null, null, s.getSql(), byName( d, null ) );
      case TABLES:
      default:
        if ( s.getTables().size() != 1 ) {
          throw problem( "source.tables", "must name one table to publish; joining tables isn't supported yet" );
        }
        String qualified = s.getTables().get( 0 );
        int dot = qualified.lastIndexOf( '.' );
        return new PhysicalSource( connection, dataSource, dot < 0 ? null : qualified.substring( 0, dot ),
          qualified.substring( dot + 1 ), null, byName( d, qualified ) );
    }
  }

  private static PhysicalSource ofFile( DatasetDefinition d, FileSource f, String connection, SqlDataSource dataSource ) {
    if ( f == null || f.getStagingTable() == null ) {
      throw problem( "source.file.stagingTable", "is not set: load the file before publishing" );
    }
    if ( f.getStagedColumns().isEmpty() ) {
      throw problem( "source.file.stagedColumns", "are not recorded: the file was loaded before publishing was available; load it again" );
    }
    Map<String, String> staged = new LinkedHashMap<>();
    for ( StagedColumn c : f.getStagedColumns() ) {
      staged.put( c.getSourceName(), c.getName() );
    }
    Map<String, String> columns = new LinkedHashMap<>();
    for ( Column c : kept( d ) ) {
      String name = staged.get( sourceName( c ) );
      if ( name == null ) {
        throw problem( "columns", "names \"" + sourceName( c ) + "\", which the loaded file doesn't have; load it again" );
      }
      columns.put( c.getName(), name );
    }
    String table = f.getStagingTable();
    int dot = table.lastIndexOf( '.' );
    return new PhysicalSource( connection, dataSource, dot < 0 ? null : table.substring( 0, dot ), table.substring( dot + 1 ),
      null, columns );
  }

  /** Columns read by their own name; for a table source, only that table's. */
  private static Map<String, String> byName( DatasetDefinition d, String table ) {
    Map<String, String> columns = new LinkedHashMap<>();
    for ( Column c : kept( d ) ) {
      if ( table != null && c.getTable() != null && !c.getTable().equals( table ) ) {
        throw problem( "columns", "names table \"" + c.getTable() + "\", which isn't the source's table" );
      }
      columns.put( c.getName(), sourceName( c ) );
    }
    return columns;
  }

  static List<Column> kept( DatasetDefinition d ) {
    List<Column> kept = new ArrayList<>();
    for ( Column c : d.getColumns() ) {
      if ( !c.isHidden() ) {
        kept.add( c );
      }
    }
    if ( kept.isEmpty() ) {
      throw problem( "columns", "must keep at least one column to publish" );
    }
    return kept;
  }

  private static String sourceName( Column c ) {
    return c.getSourceName() == null || c.getSourceName().isEmpty() ? c.getName() : c.getSourceName();
  }

  private static DatasetApiException problem( String path, String message ) {
    return new DatasetApiException( Code.VALIDATION_FAILED, path, "The dataset can't be published: " + path + " " + message,
      List.of( new org.pentaho.platform.dataaccess.dataset.DatasetProblem( path, message ) ), null );
  }

  public String getConnection() {
    return connection;
  }

  public SqlDataSource getDataSource() {
    return dataSource;
  }

  /** The table's schema, or null. */
  public String getSchema() {
    return schema;
  }

  /** The table, or null for a query. */
  public String getTable() {
    return table;
  }

  /** The query, or null for a table. */
  public String getSql() {
    return sql;
  }

  /** The table column of each kept definition column, by the definition column's name, in definition order. */
  public Map<String, String> getColumns() {
    return columns;
  }
}
