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

/** Where published datasets go: the metadata domain repository and the Mondrian catalogs. */
public interface ModelPublisher {

  /** A connection as the metadata layer describes it, for the domain's physical model. */
  SqlDataSource dataSource( String connection );

  /** Whether a metadata domain with this id exists. */
  boolean domainExists( String domainId );

  /** Whether a Mondrian catalog with this name exists. */
  boolean catalogExists( String catalogName );

  /**
   * Stores the domain, replacing an earlier version, and the Mondrian catalog over {@code connection}
   * (or removes the catalog when the model has nothing to analyse).
   */
  void publish( String connection, DatasetModelGenerator.Generated generated );

  /** Removes the domain and the catalog, if they exist. */
  void unpublish( PublishedIds ids );
}
