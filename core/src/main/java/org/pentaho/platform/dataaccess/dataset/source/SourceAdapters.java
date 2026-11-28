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
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import org.pentaho.platform.dataaccess.datasource.wizard.service.agile.AgileHelper;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The adapter for each kind of source. */
public final class SourceAdapters {

  /** The most data rows an uploaded file may have to be loaded. */
  public static final long MAX_STAGED_ROWS = 10_000_000L;

  private final Map<SourceType, SourceAdapter> byType = new EnumMap<>( SourceType.class );
  private final CsvStager stager;
  private final DatasetFiles files;
  private StagingConfig staging;
  private final org.pentaho.platform.dataaccess.dataset.service.StagingJobs jobs =
    new org.pentaho.platform.dataaccess.dataset.service.StagingJobs();

  public SourceAdapters( List<SourceAdapter> adapters ) {
    this( adapters, null );
  }

  public SourceAdapters( List<SourceAdapter> adapters, CsvStager stager ) {
    this( adapters, stager, null );
  }

  public SourceAdapters( List<SourceAdapter> adapters, CsvStager stager, DatasetFiles files ) {
    this.files = files;
    this.stager = stager;
    for ( SourceAdapter a : adapters ) {
      byType.put( a.type(), a );
    }
  }

  /** Database tables, SQL and CSV, on the platform's connections and upload folders. */
  public static SourceAdapters platform() {
    ConnectionProvider connections = new PlatformConnectionProvider();
    DatasetFiles files = new DatasetFiles( SourceAdapters::uploadsFolder, maxUploadBytes() );
    UploadedFileLocator legacy = new UploadedFileLocator();
    // Files uploaded to a dataset first; then the wizard's upload folders, for files uploaded the old way.
    FileLocator locator = name -> files.locate( name ).or( () -> legacy.locate( name ) );
    CsvSourceAdapter csv = new CsvSourceAdapter( locator, SourceLimits.standard() );
    // What an administrator saved, else the connection and schema the data source wizard staged into.
    StagingConfig staging = new StagingConfig( new org.pentaho.platform.dataaccess.dataset.service.RepositoryStagingSettingsStore(),
      AgileHelper::getJndiName, AgileHelper::getSchemaName );
    CsvStager stager = new CsvStager( csv, connections, staging::connection, staging::schema, MAX_STAGED_ROWS,
      System::currentTimeMillis );
    SourceAdapters adapters = new SourceAdapters( List.of( new TablesSourceAdapter( connections, SourceLimits.standard() ),
      new SqlSourceAdapter( connections, SourceLimits.standard() ), csv ), stager, files );
    adapters.staging = staging;
    adapters.connections = connections;
    adapters.publisher = new org.pentaho.platform.dataaccess.dataset.model.PlatformModelPublisher( connections );
    adapters.history = new org.pentaho.platform.dataaccess.dataset.service.RefreshHistory.InRepository();
    adapters.schedules = new org.pentaho.platform.dataaccess.dataset.service.DatasetSchedules.InScheduler();
    return adapters;
  }

  /** {@code system/data-access/uploads} in the solution folder: not served to browsers. */
  private static java.nio.file.Path uploadsFolder() {
    return java.nio.file.Paths.get( org.pentaho.platform.engine.core.system.PentahoSystem.getApplicationContext()
      .getSolutionPath( "system/data-access/uploads" ) );
  }

  /** {@code data-access-dataset-upload-max-mb} in data-access/settings.xml; 500 MB by default. */
  static long maxUploadBytes() {
    String mb = org.pentaho.platform.engine.core.system.PentahoSystem.getSystemSetting( "data-access/settings.xml",
      "data-access-dataset-upload-max-mb", "500" );
    try {
      return Math.max( 1, Long.parseLong( mb.trim() ) ) * 1024 * 1024;
    } catch ( NumberFormatException e ) {
      return 500L * 1024 * 1024;
    }
  }

  public static SourceAdapters standard( ConnectionProvider connections, FileLocator files, SourceLimits limits ) {
    return new SourceAdapters( List.of( new TablesSourceAdapter( connections, limits ), new SqlSourceAdapter( connections, limits ),
      new CsvSourceAdapter( files, limits ) ) );
  }

  /** None: sampling answers "not implemented". */
  public static SourceAdapters none() {
    return new SourceAdapters( List.of() );
  }

  /** Loads uploaded files into staging tables. */
  public CsvStager stager() {
    if ( stager == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server can't load files into staging tables" );
    }
    return stager;
  }

  /** The adapter for database tables, which also lists a connection's tables. */
  public TablesSourceAdapter tables() {
    SourceAdapter a = byType.get( SourceType.TABLES );
    if ( !( a instanceof TablesSourceAdapter ) ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server can't list database tables" );
    }
    return (TablesSourceAdapter) a;
  }

  /** Where files uploaded to datasets are kept. */
  public DatasetFiles files() {
    if ( files == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server can't store uploaded files" );
    }
    return files;
  }

  /** Whether files can be staged, for cleaning up after datasets that were. */
  public boolean canStage() {
    return stager != null;
  }

  private ConnectionProvider connections;

  /** Sets where files are loaded and the connections to check a new choice against; for tests. */
  public SourceAdapters withStaging( StagingConfig staging, ConnectionProvider connections ) {
    this.staging = staging;
    this.connections = connections;
    return this;
  }

  private org.pentaho.platform.dataaccess.dataset.model.ModelPublisher publisher;
  private org.pentaho.platform.dataaccess.dataset.service.RefreshHistory history =
    new org.pentaho.platform.dataaccess.dataset.service.RefreshHistory.InMemory();

  /** Where each dataset's loads are recorded; in memory unless set (the platform keeps them in the repository). */
  public org.pentaho.platform.dataaccess.dataset.service.RefreshHistory history() {
    return history;
  }

  private org.pentaho.platform.dataaccess.dataset.service.DatasetSchedules schedules =
    new org.pentaho.platform.dataaccess.dataset.service.DatasetSchedules.InMemory();

  /** Where scheduled reloads are kept; in memory unless set (the platform uses its scheduler). */
  public org.pentaho.platform.dataaccess.dataset.service.DatasetSchedules schedules() {
    return schedules;
  }

  /** Sets where scheduled reloads are kept; for tests. */
  public SourceAdapters withSchedules( org.pentaho.platform.dataaccess.dataset.service.DatasetSchedules schedules ) {
    this.schedules = schedules;
    return this;
  }

  /** Sets where loads are recorded; for tests. */
  public SourceAdapters withHistory( org.pentaho.platform.dataaccess.dataset.service.RefreshHistory history ) {
    this.history = history;
    return this;
  }

  /** Sets where published datasets go; for tests. */
  public SourceAdapters withPublisher( org.pentaho.platform.dataaccess.dataset.model.ModelPublisher publisher ) {
    this.publisher = publisher;
    return this;
  }

  /** Where published datasets go: the metadata repository and Mondrian catalogs. */
  public org.pentaho.platform.dataaccess.dataset.model.ModelPublisher publisher() {
    if ( publisher == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server can't publish datasets" );
    }
    return publisher;
  }

  /** Whether datasets can be published, for removing what a deleted dataset published. */
  public boolean canPublish() {
    return publisher != null;
  }

  /** Loads running in the background; shared by every request this instance serves. */
  public org.pentaho.platform.dataaccess.dataset.service.StagingJobs jobs() {
    return jobs;
  }

  /** Where files are loaded: saved settings, else settings.xml. */
  public StagingConfig staging() {
    if ( staging == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server has no staging settings" );
    }
    return staging;
  }

  /** Whether {@code name} is a connection the server can open. */
  public boolean connectionExists( String name ) {
    if ( connections == null || name == null ) {
      return false;
    }
    try ( java.sql.Connection c = connections.open( name ) ) {
      return c != null;
    } catch ( java.util.NoSuchElementException | java.sql.SQLException e ) {
      return false;
    }
  }

  /** Whether uploaded files are kept here, for cleaning up after deleted datasets. */
  public boolean hasFiles() {
    return files != null;
  }

  public SourceAdapter forSource( Source source ) {
    if ( source == null || source.getType() == null ) {
      throw JdbcSourceSupport.invalid( "source.type", "is required" );
    }
    SourceAdapter adapter = byType.get( source.getType() );
    if ( adapter == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server can't read " + source.getType() + " sources" );
    }
    return adapter;
  }
}
