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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The adapter for each kind of source. */
public final class SourceAdapters {

  private final Map<SourceType, SourceAdapter> byType = new EnumMap<>( SourceType.class );

  public SourceAdapters( List<SourceAdapter> adapters ) {
    for ( SourceAdapter a : adapters ) {
      byType.put( a.type(), a );
    }
  }

  /** Database tables, SQL and CSV, on the platform's connections and upload folders. */
  public static SourceAdapters platform() {
    return standard( new PlatformConnectionProvider(), new UploadedFileLocator(), SourceLimits.standard() );
  }

  public static SourceAdapters standard( ConnectionProvider connections, FileLocator files, SourceLimits limits ) {
    return new SourceAdapters( List.of( new TablesSourceAdapter( connections, limits ), new SqlSourceAdapter( connections, limits ),
      new CsvSourceAdapter( files, limits ) ) );
  }

  /** None: sampling answers "not implemented". */
  public static SourceAdapters none() {
    return new SourceAdapters( List.of() );
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
