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

/**
 * Where uploaded files are loaded, as an administrator set it. A value left empty falls back to
 * data-access/settings.xml ({@code data-access-staging-jndi}, {@code data-access-staging-schema}).
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonPropertyOrder( { "connection", "schema" } )
public final class StagingSettings {

  private String connection;
  private String schema;

  public StagingSettings() {
  }

  public StagingSettings( String connection, String schema ) {
    this.connection = blankToNull( connection );
    this.schema = blankToNull( schema );
  }

  public String getConnection() {
    return connection;
  }

  public void setConnection( String connection ) {
    this.connection = blankToNull( connection );
  }

  public String getSchema() {
    return schema;
  }

  public void setSchema( String schema ) {
    this.schema = blankToNull( schema );
  }

  static String blankToNull( String s ) {
    return s == null || s.trim().isEmpty() ? null : s.trim();
  }

  /** Keeps and reads the saved settings. */
  public interface Store {
    StagingSettings get();

    void set( StagingSettings settings );
  }

  /** Kept by this instance only; for tests. */
  public static final class InMemoryStore implements Store {
    private StagingSettings saved = new StagingSettings();

    @Override
    public synchronized StagingSettings get() {
      return new StagingSettings( saved.connection, saved.schema );
    }

    @Override
    public synchronized void set( StagingSettings settings ) {
      saved = new StagingSettings( settings.connection, settings.schema );
    }
  }
}
