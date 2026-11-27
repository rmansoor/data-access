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

package org.pentaho.platform.dataaccess.dataset.service;

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Where dataset definitions are kept. Every stored definition has a version token that changes on
 * each write; updates and deletes name the version they expect, so two people editing the same
 * dataset can't silently overwrite each other (the REST layer exposes it as an ETag).
 */
public interface DatasetStore {

  /** Every dataset, in no particular order. */
  List<StoredDataset> list();

  Optional<StoredDataset> load( String id );

  /**
   * Stores a new dataset.
   *
   * @throws DatasetApiException with {@link DatasetApiException.Code#ALREADY_EXISTS} when the id is taken
   */
  StoredDataset create( DatasetDefinition definition );

  /**
   * Replaces a dataset's definition.
   *
   * @param expectedVersion the version the caller last read
   * @throws DatasetApiException NOT_FOUND, or STALE_VERSION when the stored version differs
   */
  StoredDataset update( DatasetDefinition definition, String expectedVersion );

  /**
   * Deletes a dataset.
   *
   * @throws DatasetApiException NOT_FOUND, or STALE_VERSION when the stored version differs
   */
  void delete( String id, String expectedVersion );
}
