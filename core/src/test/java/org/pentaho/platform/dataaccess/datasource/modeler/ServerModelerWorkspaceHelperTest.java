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

import org.junit.Test;
import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.nodes.MainModelNode;
import org.pentaho.agilebi.modeler.nodes.MeasureMetaData;
import org.pentaho.agilebi.modeler.nodes.RelationalModelNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/** The server helper behaves as pentaho-modeler-gwt's GwtModelerWorkspaceHelper did. */
public class ServerModelerWorkspaceHelperTest {

  private static final class Exposed extends ServerModelerWorkspaceHelper {
    MainModelNode main( ModelerWorkspace w ) {
      return getMainModelNode( w );
    }

    RelationalModelNode relational( ModelerWorkspace w ) {
      return getRelationalModelNode( w );
    }
  }

  @Test
  public void reusesAndClearsTheWorkspacesModelsRatherThanReplacingThem() {
    Exposed helper = new Exposed();
    ModelerWorkspace workspace = new ModelerWorkspace( helper );
    MainModelNode existing = workspace.getModel();
    assertNotNull( existing );
    existing.getMeasures().add( new MeasureMetaData( "LOCALE" ) );

    assertSame( existing, helper.main( workspace ) );
    assertEquals( 0, existing.getMeasures().size() );
    assertSame( workspace.getRelationalModel(), helper.relational( workspace ) );
  }

  @Test
  public void usesTheSameDefaultLocale() {
    assertEquals( "en_US", ServerModelerWorkspaceHelper.DEFAULT_LOCALE );
  }
}
