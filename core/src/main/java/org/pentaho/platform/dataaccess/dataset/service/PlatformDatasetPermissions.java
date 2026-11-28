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

import org.pentaho.platform.api.engine.IAuthorizationPolicy;
import org.pentaho.platform.dataaccess.security.policy.rolebased.actions.DatasetCertifyAction;
import org.pentaho.platform.engine.core.system.PentahoSystem;
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

  // The same policy check the rest of data-access makes (SimpleDataAccessPermissionHandler); 11.1 deprecates it for
  // IAuthorizationService, which data-access hasn't moved to yet.
  @Override
  @SuppressWarnings( "removal" )
  public boolean canCertify() {
    IAuthorizationPolicy policy = PentahoSystem.get( IAuthorizationPolicy.class );
    return policy != null && policy.isAllowed( DatasetCertifyAction.NAME );
  }

  @Override
  public boolean canAdminister() {
    // The same rule the v1 data source API applies to administrators.
    return DatasourceService.canAdminister();
  }
}
