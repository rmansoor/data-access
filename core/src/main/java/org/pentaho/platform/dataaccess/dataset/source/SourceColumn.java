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

/** A column as the source reports it: its name, the dataset type it maps to, and the source's own type. */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonPropertyOrder( { "name", "type", "sourceType", "nullable", "table" } )
public final class SourceColumn {

  private final String name;
  private final ColumnType type;
  private final String sourceType;
  private final Boolean nullable;
  private final String table;

  public SourceColumn( String name, ColumnType type, String sourceType, Boolean nullable, String table ) {
    this.name = name;
    this.type = type;
    this.sourceType = sourceType;
    this.nullable = nullable;
    this.table = table;
  }

  public String getName() {
    return name;
  }

  public ColumnType getType() {
    return type;
  }

  /** The database type name ("VARCHAR", "NUMERIC") or, for files, the detected format mask; may be null. */
  public String getSourceType() {
    return sourceType;
  }

  /** Whether the source allows nulls; null when it doesn't say. */
  public Boolean getNullable() {
    return nullable;
  }

  /** For database tables, the table the column belongs to. */
  public String getTable() {
    return table;
  }

  @Override
  public String toString() {
    return name + ":" + type;
  }
}
