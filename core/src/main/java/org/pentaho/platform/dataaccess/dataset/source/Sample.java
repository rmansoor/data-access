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

import java.util.Collections;
import java.util.List;

/**
 * The first rows of a source, with its columns. Values are JSON-ready: strings, numbers ({@code Long}
 * or {@code BigDecimal}), booleans, ISO-8601 dates and timestamps as strings, or null.
 */
@JsonPropertyOrder( { "columns", "rows", "truncated", "sheets" } )
public final class Sample {

  private final List<SourceColumn> columns;
  private final List<List<Object>> rows;
  private final boolean truncated;
  private final List<String> sheets;

  public Sample( List<SourceColumn> columns, List<List<Object>> rows, boolean truncated ) {
    this( columns, rows, truncated, null );
  }

  /** @param sheets for a workbook, its sheets, so the one to read can be chosen; null otherwise */
  public Sample( List<SourceColumn> columns, List<List<Object>> rows, boolean truncated, List<String> sheets ) {
    this.columns = Collections.unmodifiableList( columns );
    this.rows = Collections.unmodifiableList( rows );
    this.truncated = truncated;
    this.sheets = sheets == null ? null : List.copyOf( sheets );
  }

  /** A workbook's sheets, in order; absent for other sources. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  public List<String> getSheets() {
    return sheets;
  }

  public List<SourceColumn> getColumns() {
    return columns;
  }

  public List<List<Object>> getRows() {
    return rows;
  }

  /** True when the source has more rows than were returned. */
  public boolean isTruncated() {
    return truncated;
  }
}
