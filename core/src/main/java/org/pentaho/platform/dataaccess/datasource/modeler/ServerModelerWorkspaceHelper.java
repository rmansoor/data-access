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

import org.pentaho.agilebi.modeler.BaseModelerWorkspaceHelper;
import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.nodes.MainModelNode;
import org.pentaho.agilebi.modeler.nodes.RelationalModelNode;

/**
 * Builds modeler workspaces on the server. Behaves exactly as pentaho-modeler-gwt's
 * GwtModelerWorkspaceHelper did, without GWT: an existing model is reused and its measures,
 * dimensions and categories cleared, rather than replaced.
 */
public class ServerModelerWorkspaceHelper extends BaseModelerWorkspaceHelper {

  public static final String DEFAULT_LOCALE = "en_US";

  public ServerModelerWorkspaceHelper() {
    super( DEFAULT_LOCALE );
  }

  @Override
  protected MainModelNode getMainModelNode( ModelerWorkspace workspace ) {
    MainModelNode model = workspace.getModel();
    if ( model == null ) {
      model = new MainModelNode();
      model.setWorkspace( workspace );
      return model;
    }
    model.getMeasures().clear();
    model.getDimensions().clear();
    return model;
  }

  @Override
  protected RelationalModelNode getRelationalModelNode( ModelerWorkspace workspace ) {
    RelationalModelNode model = workspace.getRelationalModel();
    if ( model == null ) {
      model = new RelationalModelNode();
      model.setWorkspace( workspace );
      return model;
    }
    model.getCategories().clear();
    return model;
  }
}
