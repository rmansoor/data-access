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

package org.pentaho.platform.dataaccess.datasource.modeler;

import org.pentaho.metadata.model.Domain;

/** Generates, serializes and loads metadata domains for the modeler (formerly pentaho-modeler-gwt's IModelerService). */
public interface IModelerService {

  Domain generateDomain( String connectionName, String tableName, String dbType, String query, String datasourceName )
    throws Exception;

  String serializeModels( Domain domain, String name ) throws Exception;

  String serializeModels( Domain domain, String name, boolean doOlap ) throws Exception;

  Domain loadDomain( String id ) throws Exception;
}
