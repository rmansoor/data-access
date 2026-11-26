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

package org.pentaho.platform.dataaccess.dataset;

/**
 * Where datasets live in the repository: one folder per dataset under {@value #ROOT}, holding its
 * definition ({@value #DEFINITION_FILE}). Repository versioning of that file is the dataset's
 * version history.
 */
public final class DatasetPaths {

  public static final String ROOT = "/etc/datasets";
  public static final String DEFINITION_FILE = "dataset.json";

  private DatasetPaths() {
  }

  /** True when {@code id} is safe to use as a repository folder name (see {@link DatasetValidator}). */
  public static boolean isValidId( String id ) {
    return id != null && id.matches( DatasetValidator.ID_PATTERN );
  }

  /** "/etc/datasets/{id}". */
  public static String folder( String id ) {
    requireValid( id );
    return ROOT + "/" + id;
  }

  /** "/etc/datasets/{id}/dataset.json". */
  public static String definitionPath( String id ) {
    return folder( id ) + "/" + DEFINITION_FILE;
  }

  private static void requireValid( String id ) {
    if ( !isValidId( id ) ) {
      // Ids come from requests; refuse anything that could escape the folder ("..", "/", ":").
      throw new IllegalArgumentException( "Not a valid dataset id: " + id );
    }
  }
}
