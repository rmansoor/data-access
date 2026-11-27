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

import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;

import java.util.function.Supplier;

/**
 * Builds a {@link DatasetService} for the current request: repository storage, the platform's
 * data-access permissions, and the signed-in user from the session. Wired into the REST resource
 * by the plugin's Spring context.
 */
public class PlatformDatasetServiceFactory implements Supplier<DatasetService> {

  private final DatasetStore store;
  private final DatasetPermissions permissions;
  private final DatasetSharing sharing;

  public PlatformDatasetServiceFactory() {
    this( new RepositoryDatasetStore(), new PlatformDatasetPermissions(), new RepositoryDatasetSharing() );
  }

  public PlatformDatasetServiceFactory( DatasetStore store, DatasetPermissions permissions, DatasetSharing sharing ) {
    this.store = store;
    this.permissions = permissions;
    this.sharing = sharing;
  }

  @Override
  public DatasetService get() {
    return new DatasetService( store, permissions, PlatformDatasetServiceFactory::currentUser, sharing );
  }

  private static String currentUser() {
    IPentahoSession session = PentahoSessionHolder.getSession();
    return session == null ? null : session.getName();
  }
}
