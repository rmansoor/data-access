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

import java.util.function.Supplier;

/** The staging connection and schema in effect: what an administrator saved, else settings.xml. */
public class StagingConfig {

  private final StagingSettings.Store store;
  private final Supplier<String> defaultConnection;
  private final Supplier<String> defaultSchema;

  public StagingConfig( StagingSettings.Store store, Supplier<String> defaultConnection, Supplier<String> defaultSchema ) {
    this.store = store;
    this.defaultConnection = defaultConnection;
    this.defaultSchema = defaultSchema;
  }

  /** A fixed connection and schema, nothing saved; for tests. */
  public static StagingConfig fixed( String connection, String schema ) {
    return new StagingConfig( new StagingSettings.InMemoryStore(), () -> connection, () -> schema );
  }

  public String connection() {
    String saved = store.get().getConnection();
    return saved != null ? saved : StagingSettings.blankToNull( defaultConnection.get() );
  }

  public String schema() {
    String saved = store.get().getSchema();
    return saved != null ? saved : StagingSettings.blankToNull( defaultSchema.get() );
  }

  public StagingSettings saved() {
    return store.get();
  }

  public void save( StagingSettings settings ) {
    store.set( settings );
  }

  /** What settings.xml says, used when nothing is saved. */
  public StagingSettings defaults() {
    return new StagingSettings( defaultConnection.get(), defaultSchema.get() );
  }
}
