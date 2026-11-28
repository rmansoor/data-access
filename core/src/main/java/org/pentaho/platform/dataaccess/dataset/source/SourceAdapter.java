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

import java.util.List;

/**
 * Reads one kind of source: database tables, a SQL query or a file. New kinds (Excel, Parquet,
 * cloud storage, PDI) are added as adapters, without changes to the API or the wizard.
 * <p>
 * Adapters only read. Problems are reported as
 * {@link org.pentaho.platform.dataaccess.dataset.service.DatasetApiException}: VALIDATION_FAILED for a
 * source definition that can't work (with the field's path), SOURCE_FAILED when the source itself
 * refuses or fails.
 */
public interface SourceAdapter {

  /** The kind of source this adapter reads. */
  SourceType type();

  /** The source's columns. For TABLES, the columns of every table, each naming its table. */
  List<SourceColumn> schema( Source source );

  /**
   * The source's first rows.
   *
   * @param limit at most this many rows (already checked by the caller to be within the server's cap)
   * @param table for TABLES, which table to sample; null for the first. Ignored by other kinds.
   */
  Sample sample( Source source, int limit, String table );
}
