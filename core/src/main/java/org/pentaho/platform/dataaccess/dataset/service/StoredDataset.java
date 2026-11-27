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

import java.time.Instant;

/** A definition as stored, with its version token and when it last changed. */
public final class StoredDataset {

  private final DatasetDefinition definition;
  private final String version;
  private final Instant updated;

  public StoredDataset( DatasetDefinition definition, String version, Instant updated ) {
    this.definition = definition;
    this.version = version;
    this.updated = updated;
  }

  public DatasetDefinition getDefinition() {
    return definition;
  }

  /** Opaque; changes on every write. */
  public String getVersion() {
    return version;
  }

  public Instant getUpdated() {
    return updated;
  }
}
