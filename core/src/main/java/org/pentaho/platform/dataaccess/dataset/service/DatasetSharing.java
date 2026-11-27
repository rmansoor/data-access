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

/**
 * Where each dataset's sharing is kept, and whether the caller passes it. An unrestricted dataset
 * passes everyone; a restricted one only its entries. The service decides the rest (owner and
 * administrators always pass, and the data-access permissions still apply).
 */
public interface DatasetSharing {

  /** The dataset's sharing; {@link DatasetShare#unrestricted()} when none is set. */
  DatasetShare get( String id );

  /** Replaces the dataset's sharing; unrestricted removes any restriction. */
  void set( String id, DatasetShare share );

  /** May the caller see and use this dataset under its sharing? */
  boolean canRead( String id );

  /** May the caller change and delete this dataset under its sharing? */
  boolean canWrite( String id );
}
