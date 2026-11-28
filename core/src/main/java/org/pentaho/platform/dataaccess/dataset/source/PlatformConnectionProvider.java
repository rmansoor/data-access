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

import org.pentaho.platform.api.data.DBDatasourceServiceException;
import org.pentaho.platform.api.data.IDBDatasourceService;
import org.pentaho.platform.engine.core.system.PentahoSystem;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.NoSuchElementException;

/** Connections from the platform's pooled data sources, the same ones reports use. */
public class PlatformConnectionProvider implements ConnectionProvider {

  @Override
  public Connection open( String name ) throws SQLException {
    IDBDatasourceService service = PentahoSystem.get( IDBDatasourceService.class );
    if ( service == null ) {
      throw new SQLException( "The platform's data source service is not available" );
    }
    DataSource ds;
    try {
      ds = service.getDataSource( name );
    } catch ( DBDatasourceServiceException e ) {
      throw new NoSuchElementException( "No connection named \"" + name + "\"" );
    }
    if ( ds == null ) {
      throw new NoSuchElementException( "No connection named \"" + name + "\"" );
    }
    return ds.getConnection();
  }
}
