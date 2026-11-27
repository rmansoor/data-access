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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.NoSuchElementException;

/** Connections for tests, handed out the way a pool does: the same one each time, close() only hands it back. */
public final class TestConnections {

  private TestConnections() {
  }

  /** {@code name} opens {@code db}; any other name is unknown. */
  public static ConnectionProvider pooled( Connection db, String name ) {
    return pooled( db, name, -1 );
  }

  /** As {@link #pooled(Connection, String)}, but the {@code failOnBatch}th executeBatch fails (counting from 1; -1 never). */
  public static ConnectionProvider pooled( Connection db, String name, int failOnBatch ) {
    int[] batches = { 0 };
    return n -> {
      if ( !name.equals( n ) ) {
        throw new NoSuchElementException( n );
      }
      return proxy( Connection.class, db, ( method, args ) -> {
        if ( "close".equals( method.getName() ) ) {
          return null;
        }
        Object result = call( db, method, args );
        if ( "prepareStatement".equals( method.getName() ) && failOnBatch > 0 ) {
          PreparedStatement real = (PreparedStatement) result;
          return proxy( PreparedStatement.class, real, ( m, a ) -> {
            if ( "executeBatch".equals( m.getName() ) && ++batches[ 0 ] == failOnBatch ) {
              throw new SQLException( "disk full" );
            }
            return call( real, m, a );
          } );
        }
        return result;
      } );
    };
  }

  private interface Handler {
    Object handle( Method method, Object[] args ) throws Throwable;
  }

  private static <T> T proxy( Class<T> type, T target, Handler handler ) {
    return type.cast( Proxy.newProxyInstance( TestConnections.class.getClassLoader(), new Class<?>[] { type },
      ( p, method, args ) -> handler.handle( method, args ) ) );
  }

  private static Object call( Object target, Method method, Object[] args ) throws Throwable {
    try {
      return method.invoke( target, args );
    } catch ( InvocationTargetException e ) {
      throw e.getCause();
    }
  }
}
