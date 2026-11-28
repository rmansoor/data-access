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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;

import java.util.List;

/** An uploaded file loaded into a table of its own on the staging connection. */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonPropertyOrder( { "connection", "table", "rows", "columns", "warnings" } )
public final class StagedTable {

  private final String connection;
  private final String table;
  private final long rows;
  private final List<Column> columns;
  private final List<String> warnings;

  public StagedTable( String connection, String table, long rows, List<Column> columns, List<String> warnings ) {
    this.connection = connection;
    this.table = table;
    this.rows = rows;
    this.columns = List.copyOf( columns );
    this.warnings = List.copyOf( warnings );
  }

  public String getConnection() {
    return connection;
  }

  /** The table, as {@code schema.table} when the staging schema is set. */
  public String getTable() {
    return table;
  }

  public long getRows() {
    return rows;
  }

  public List<Column> getColumns() {
    return columns;
  }

  /** What was loaded differently from what was detected, such as a column kept as text. */
  public List<String> getWarnings() {
    return warnings;
  }

  /** One column: its name in the file, its name in the table, and the type it was loaded as. */
  @JsonPropertyOrder( { "sourceName", "name", "type", "sqlType" } )
  public static final class Column {
    private final String sourceName;
    private final String name;
    private final ColumnType type;
    private final String sqlType;

    public Column( String sourceName, String name, ColumnType type, String sqlType ) {
      this.sourceName = sourceName;
      this.name = name;
      this.type = type;
      this.sqlType = sqlType;
    }

    public String getSourceName() {
      return sourceName;
    }

    public String getName() {
      return name;
    }

    public ColumnType getType() {
      return type;
    }

    /** The column type in the table's database, as created. */
    public String getSqlType() {
      return sqlType;
    }

    @Override
    public String toString() {
      return sourceName + "->" + name + ":" + type + "(" + sqlType + ")";
    }
  }
}
