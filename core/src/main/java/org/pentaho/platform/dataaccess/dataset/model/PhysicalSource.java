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
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Cardinality;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Column;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.JoinType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Relationship;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.StagedColumn;
import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a dataset's rows are read from when it is queried: the connection, and the tables (or query)
 * holding them. A file's rows are in its staging table; a query is used as an inline SQL table; a
 * TABLES source is one table, or several joined as a star: one fact table, every other table joined
 * to it directly by a many-to-one relationship. Each kept column of the definition is mapped to its
 * table and column.
 */
public final class PhysicalSource {

  /** One table (or the query) and the definition columns read from it. */
  public static final class Table {
    private final String schema;
    private final String name;
    private final String sql;
    private final Map<String, String> columns = new LinkedHashMap<>();
    private final Set<String> keys = new LinkedHashSet<>();
    private boolean fact;

    Table( String schema, String name, String sql ) {
      this.schema = schema;
      this.name = name;
      this.sql = sql;
    }

    /** The table's schema, or null. */
    public String getSchema() {
      return schema;
    }

    /** The table, or null for a query. */
    public String getName() {
      return name;
    }

    /** The query, or null for a table. */
    public String getSql() {
      return sql;
    }

    /** The table column of each kept definition column read from this table, by the definition column's name. */
    public Map<String, String> getColumns() {
      return columns;
    }

    /** Columns that only joins use: in the table, but not offered to report users. */
    public Set<String> getKeys() {
      return keys;
    }

    /** Whether this is the table the measures are on (with several tables). */
    public boolean isFact() {
      return fact;
    }
  }

  /** A join from the fact table to another table. */
  public static final class Join {
    private final Table from;
    private final String fromColumn;
    private final Table to;
    private final String toColumn;
    private final boolean outer;

    Join( Table from, String fromColumn, Table to, String toColumn, boolean outer ) {
      this.from = from;
      this.fromColumn = fromColumn;
      this.to = to;
      this.toColumn = toColumn;
      this.outer = outer;
    }

    public Table getFrom() {
      return from;
    }

    public String getFromColumn() {
      return fromColumn;
    }

    public Table getTo() {
      return to;
    }

    public String getToColumn() {
      return toColumn;
    }

    /** Whether fact rows without a match are kept (a left outer join). */
    public boolean isOuter() {
      return outer;
    }
  }

  private final String connection;
  private final SqlDataSource dataSource;
  private final List<Table> tables;
  private final List<Join> joins;

  private PhysicalSource( String connection, SqlDataSource dataSource, List<Table> tables, List<Join> joins ) {
    this.connection = connection;
    this.dataSource = dataSource;
    this.tables = tables;
    this.joins = joins;
  }

  /**
   * The physical source of a definition.
   *
   * @param connection the name of the connection the rows are read through (for a file, the staging connection)
   * @param dataSource that connection, as the metadata layer describes it
   * @throws DatasetApiException VALIDATION_FAILED when the definition can't be published yet: a file not
   *                             loaded, tables that don't form a star, or a column the source doesn't have
   */
  public static PhysicalSource of( DatasetDefinition d, String connection, SqlDataSource dataSource ) {
    Source s = d.getSource();
    if ( s == null || s.getType() == null ) {
      throw problem( "source", "is required" );
    }
    switch ( s.getType() ) {
      case FILE:
        return ofFile( d, s.getFile(), connection, dataSource );
      case SQL: {
        Table t = new Table( null, null, s.getSql() );
        for ( Column c : kept( d ) ) {
          t.columns.put( c.getName(), sourceName( c ) );
        }
        return new PhysicalSource( connection, dataSource, List.of( t ), List.of() );
      }
      case TABLES:
      default:
        return ofTables( d, s, connection, dataSource );
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
    Table t = table( f.getStagingTable(), null );
    for ( Column c : kept( d ) ) {
      String name = staged.get( sourceName( c ) );
      if ( name == null ) {
        throw problem( "columns", "names \"" + sourceName( c ) + "\", which the loaded file doesn't have; load it again" );
      }
      t.columns.put( c.getName(), name );
    }
    return new PhysicalSource( connection, dataSource, List.of( t ), List.of() );
  }

  private static PhysicalSource ofTables( DatasetDefinition d, Source s, String connection, SqlDataSource dataSource ) {
    if ( s.getTables().isEmpty() ) {
      throw problem( "source.tables", "must name at least one table" );
    }
    Map<String, Table> byName = new LinkedHashMap<>();
    for ( String qualified : s.getTables() ) {
      byName.put( qualified, table( qualified, null ) );
    }
    boolean single = byName.size() == 1;
    List<Column> kept = kept( d );
    for ( int i = 0; i < kept.size(); i++ ) {
      Column c = kept.get( i );
      String tableName = c.getTable() == null && single ? s.getTables().get( 0 ) : c.getTable();
      Table t = tableName == null ? null : byName.get( tableName );
      if ( t == null ) {
        throw problem( "columns[" + i + "].table", c.getTable() == null ? "is required when the source has several tables"
          : "names \"" + c.getTable() + "\", which isn't one of the source's tables" );
      }
      t.columns.put( c.getName(), sourceName( c ) );
    }
    if ( single ) {
      return new PhysicalSource( connection, dataSource, new ArrayList<>( byName.values() ), List.of() );
    }
    return star( d, byName, connection, dataSource );
  }

  /**
   * Several tables as a star: each relationship read from its many side, they must all start at the
   * same table (the fact table) and reach every other table exactly once.
   */
  private static PhysicalSource star( DatasetDefinition d, Map<String, Table> byName, String connection, SqlDataSource dataSource ) {
    List<Relationship> rels = d.getRelationships();
    if ( rels.isEmpty() ) {
      throw problem( "relationships", "must join the tables: the source has " + byName.size() + " tables and no relationships" );
    }
    String fact = null;
    List<String[]> edges = new ArrayList<>();
    for ( int i = 0; i < rels.size(); i++ ) {
      Relationship r = rels.get( i );
      String path = "relationships[" + i + "]";
      for ( String t : new String[] { r.getFromTable(), r.getToTable() } ) {
        if ( t == null || !byName.containsKey( t ) ) {
          throw problem( path, "names table \"" + t + "\", which isn't one of the source's tables" );
        }
      }
      if ( r.getFromColumn() == null || r.getFromColumn().isEmpty() || r.getToColumn() == null || r.getToColumn().isEmpty() ) {
        throw problem( path, "needs a column on each side" );
      }
      // Read from the many side: ONE_TO_MANY is the same join the other way round.
      boolean reversed = r.getCardinality() == Cardinality.ONE_TO_MANY;
      String[] e = reversed ? new String[] { r.getToTable(), r.getToColumn(), r.getFromTable(), r.getFromColumn() }
        : new String[] { r.getFromTable(), r.getFromColumn(), r.getToTable(), r.getToColumn() };
      if ( fact == null ) {
        fact = e[ 0 ];
      } else if ( !fact.equals( e[ 0 ] ) ) {
        throw problem( path, "must start at the fact table \"" + fact + "\": every other table joins the fact table directly (a star)" );
      }
      edges.add( new String[] { e[ 0 ], e[ 1 ], e[ 2 ], e[ 3 ], r.getJoinType() == JoinType.LEFT_OUTER ? "outer" : "inner" } );
    }
    Set<String> reached = new LinkedHashSet<>();
    for ( int i = 0; i < edges.size(); i++ ) {
      String to = edges.get( i )[ 2 ];
      if ( to.equals( fact ) || !reached.add( to ) ) {
        throw problem( "relationships[" + i + "]", "joins \"" + to + "\" a second time: every other table joins the fact table once (a star)" );
      }
    }
    for ( String t : byName.keySet() ) {
      if ( !t.equals( fact ) && !reached.contains( t ) ) {
        throw problem( "relationships", "must join \"" + t + "\" to the fact table \"" + fact + "\"" );
      }
    }
    byName.get( fact ).fact = true;
    List<Join> joins = new ArrayList<>();
    for ( String[] e : edges ) {
      Table from = byName.get( e[ 0 ] );
      Table to = byName.get( e[ 2 ] );
      key( from, e[ 1 ] );
      key( to, e[ 3 ] );
      joins.add( new Join( from, e[ 1 ], to, e[ 3 ], "outer".equals( e[ 4 ] ) ) );
    }
    // The fact table first: the modeler and Mondrian read it as the cube's table.
    Table factTable = byName.get( fact );
    List<Table> ordered = new ArrayList<>();
    ordered.add( factTable );
    for ( Table t : byName.values() ) {
      if ( t != factTable ) {
        ordered.add( t );
      }
    }
    return new PhysicalSource( connection, dataSource, ordered, joins );
  }

  /** A join column the user didn't keep is still needed in the table. */
  private static void key( Table t, String column ) {
    if ( !t.columns.containsValue( column ) ) {
      t.keys.add( column );
    }
  }

  private static Table table( String qualified, String sql ) {
    int dot = qualified.lastIndexOf( '.' );
    return new Table( dot < 0 ? null : qualified.substring( 0, dot ), qualified.substring( dot + 1 ), sql );
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
      List.of( new DatasetProblem( path, message ) ), null );
  }

  public String getConnection() {
    return connection;
  }

  public SqlDataSource getDataSource() {
    return dataSource;
  }

  /** The tables, the fact table first. */
  public List<Table> getTables() {
    return tables;
  }

  /** The joins from the fact table; none with one table. */
  public List<Join> getJoins() {
    return joins;
  }

  /** The table column of each kept definition column, by the definition column's name. */
  public Map<String, String> getColumns() {
    Map<String, String> all = new LinkedHashMap<>();
    tables.forEach( t -> all.putAll( t.columns ) );
    return all;
  }

  /** The table a kept definition column is read from. */
  public Table tableOf( String column ) {
    for ( Table t : tables ) {
      if ( t.columns.containsKey( column ) ) {
        return t;
      }
    }
    return null;
  }
}
