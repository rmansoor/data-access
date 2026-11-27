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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * What the database adapters share: every connection is read-only, inside a transaction that is
 * always rolled back, and every statement has a timeout and a row cap. Pooled connections are
 * handed back as they were found.
 */
abstract class JdbcSourceSupport implements SourceAdapter {

  private static final Log LOG = LogFactory.getLog( JdbcSourceSupport.class );
  static final String CONNECTION = "source.connection";

  protected final ConnectionProvider connections;
  protected final SourceLimits limits;

  JdbcSourceSupport( ConnectionProvider connections, SourceLimits limits ) {
    this.connections = connections;
    this.limits = limits;
  }

  @FunctionalInterface
  interface Work<T> {
    T run( Connection connection ) throws SQLException;
  }

  protected <T> T withConnection( String name, Work<T> work ) {
    if ( name == null || name.trim().isEmpty() ) {
      throw invalid( CONNECTION, "is required" );
    }
    try ( Connection c = open( name ) ) {
      boolean autoCommit = c.getAutoCommit();
      boolean readOnly = c.isReadOnly();
      readOnly( c, true );
      c.setAutoCommit( false );
      try {
        return work.run( c );
      } finally {
        try {
          c.rollback();
          c.setAutoCommit( autoCommit );
          readOnly( c, readOnly );
        } catch ( SQLException e ) {
          LOG.warn( "Could not reset connection \"" + name + "\" after reading a dataset source", e );
        }
      }
    } catch ( SQLTimeoutException e ) {
      throw failed( "The database took longer than " + limits.getQueryTimeoutSeconds() + " seconds and the query was stopped", e );
    } catch ( SQLException e ) {
      throw failed( "The database refused the request: " + e.getMessage(), e );
    }
  }

  /** A statement capped at {@code maxRows} rows and the query timeout. */
  protected <S extends Statement> S capped( S statement, int maxRows ) throws SQLException {
    statement.setQueryTimeout( limits.getQueryTimeoutSeconds() );
    statement.setMaxRows( maxRows );
    statement.setFetchSize( Math.min( maxRows, 500 ) );
    return statement;
  }

  private Connection open( String name ) throws SQLException {
    try {
      Connection c = connections.open( name );
      if ( c == null ) {
        throw new NoSuchElementException( name );
      }
      return c;
    } catch ( NoSuchElementException e ) {
      throw invalid( CONNECTION, "names no connection (\"" + name + "\")" );
    }
  }

  /** Read-only is a hint some drivers don't take; the guard and the rollback still apply. */
  private static void readOnly( Connection c, boolean on ) throws SQLException {
    try {
      c.setReadOnly( on );
    } catch ( SQLFeatureNotSupportedException e ) {
      LOG.debug( "Driver does not support read-only connections", e );
    }
  }

  static DatasetApiException invalid( String path, String message ) {
    return new DatasetApiException( Code.VALIDATION_FAILED, path, "The source's " + path.replace( "source.", "" ) + " " + message,
      List.of( new DatasetProblem( path, message ) ), null );
  }

  static DatasetApiException failed( String message, Throwable cause ) {
    return new DatasetApiException( Code.SOURCE_FAILED, "source", message, null, cause );
  }
}
