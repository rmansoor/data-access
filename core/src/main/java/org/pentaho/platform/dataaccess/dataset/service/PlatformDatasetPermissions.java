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

import org.pentaho.platform.dataaccess.datasource.api.DatasourceService;
import org.pentaho.platform.dataaccess.datasource.utils.DataAccessPermissionUtil;

/** Dataset permissions from the platform's data-access permission handlers, as the existing data source resources use them. */
public class PlatformDatasetPermissions implements DatasetPermissions {

  @Override
  public boolean canView() {
    return DataAccessPermissionUtil.hasViewAccess() || DataAccessPermissionUtil.hasManageAccess();
  }

  @Override
  public boolean canManage() {
    return DataAccessPermissionUtil.hasManageAccess();
  }

  @Override
  public boolean canAdminister() {
    // The same rule the v1 data source API applies to administrators.
    return DatasourceService.canAdminister();
  }
}
