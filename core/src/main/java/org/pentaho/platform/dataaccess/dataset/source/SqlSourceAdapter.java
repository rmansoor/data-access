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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

/** A single read-only query on a named connection; see {@link SqlGuard} for what is accepted. */
public class SqlSourceAdapter extends JdbcSourceSupport {

  public SqlSourceAdapter( ConnectionProvider connections, SourceLimits limits ) {
    super( connections, limits );
  }

  @Override
  public SourceType type() {
    return SourceType.SQL;
  }

  @Override
  public List<SourceColumn> schema( Source source ) {
    String sql = SqlGuard.check( source.getSql() );
    return withConnection( source.getConnection(), c -> {
      try ( PreparedStatement ps = capped( c.prepareStatement( sql ), 1 ) ) {
        ResultSetMetaData md = ps.getMetaData();
        if ( md != null ) {
          return JdbcValues.columns( md, null );
        }
        // Some drivers only describe a query once it has run.
        try ( ResultSet rs = ps.executeQuery() ) {
          return JdbcValues.columns( rs.getMetaData(), null );
        }
      }
    } );
  }

  @Override
  public Sample sample( Source source, int limit, String table ) {
    String sql = SqlGuard.check( source.getSql() );
    return withConnection( source.getConnection(), c -> {
      // One row more than asked for, to tell whether there are more.
      try ( PreparedStatement ps = capped( c.prepareStatement( sql ), limit + 1 ); ResultSet rs = ps.executeQuery() ) {
        return JdbcValues.read( rs, JdbcValues.columns( rs.getMetaData(), null ), limit );
      }
    } );
  }
}
