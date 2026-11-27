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

import java.sql.Connection;
import java.sql.SQLException;

/** Opens a JDBC connection by the name it has in Pentaho's connection list. */
@FunctionalInterface
public interface ConnectionProvider {

  /**
   * @return an open connection the caller closes
   * @throws java.util.NoSuchElementException when there is no connection by that name
   */
  Connection open( String name ) throws SQLException;
}
