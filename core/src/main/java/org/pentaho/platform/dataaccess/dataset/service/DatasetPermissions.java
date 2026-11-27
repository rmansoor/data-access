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

package org.pentaho.platform.dataaccess.dataset.service;

/**
 * Who may read and who may change datasets. On the server this is {@link PlatformDatasetPermissions}:
 * the same rules as the rest of data access.
 */
public interface DatasetPermissions {

  /** May list and read datasets (data-access view permission). */
  boolean canView();

  /** May create, change and delete datasets (data-access manage permission). */
  boolean canManage();

  /** Administers security: sees and changes every dataset whatever its sharing. */
  default boolean canAdminister() {
    return false;
  }
}
