/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 - 2026 by Pentaho Canada Inc. : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2030-06-15
 ******************************************************************************/


package org.pentaho.platform.dataaccess.security.policy.rolebased.actions;

import org.pentaho.platform.dataaccess.security.policy.rolebased.actions.messages.Messages;
import org.pentaho.platform.engine.security.authorization.core.AbstractAuthorizationAction;

/** May certify datasets as trusted, and withdraw a certification (granted per role in Users & Roles). */
public class DatasetCertifyAction extends AbstractAuthorizationAction {
  public static final String NAME = "org.pentaho.platform.dataaccess.dataset.security.certify";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getLocalizedDisplayName( String localeString ) {
    return Messages.getInstance().getString( NAME );
  }

  @Override
  public String getLocalizedDescription( String localeString ) {
    return Messages.getInstance().getString( NAME + ".description" );
  }
}
