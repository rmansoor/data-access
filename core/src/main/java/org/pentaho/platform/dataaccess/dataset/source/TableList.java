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

import java.util.List;

/** The tables and views a connection offers, for choosing a TABLES source. */
@JsonPropertyOrder( { "tables", "truncated" } )
public final class TableList {

  private final List<Entry> tables;
  private final boolean truncated;

  public TableList( List<Entry> tables, boolean truncated ) {
    this.tables = List.copyOf( tables );
    this.truncated = truncated;
  }

  public List<Entry> getTables() {
    return tables;
  }

  /** The connection has more tables than were listed. */
  public boolean isTruncated() {
    return truncated;
  }

  /** One table or view; {@link #getQualifiedName()} is what a TABLES source lists. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonPropertyOrder( { "schema", "name", "type", "qualifiedName" } )
  public static final class Entry {
    private final String schema;
    private final String name;
    private final String type;

    public Entry( String schema, String name, String type ) {
      this.schema = schema == null || schema.isEmpty() ? null : schema;
      this.name = name;
      this.type = type;
    }

    public String getSchema() {
      return schema;
    }

    public String getName() {
      return name;
    }

    /** TABLE or VIEW. */
    public String getType() {
      return type;
    }

    public String getQualifiedName() {
      return schema == null ? name : schema + "." + name;
    }

    @Override
    public String toString() {
      return getQualifiedName() + ":" + type;
    }
  }
}
