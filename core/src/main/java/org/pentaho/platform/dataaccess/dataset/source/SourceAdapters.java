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
    // The connection and schema the data source wizard stages uploads into today.
    CsvStager stager = new CsvStager( csv, connections, AgileHelper::getJndiName, AgileHelper::getSchemaName, MAX_STAGED_ROWS,
      System::currentTimeMillis );
    return new SourceAdapters( List.of( new TablesSourceAdapter( connections, SourceLimits.standard() ),
      new SqlSourceAdapter( connections, SourceLimits.standard() ), csv ), stager, files );
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
