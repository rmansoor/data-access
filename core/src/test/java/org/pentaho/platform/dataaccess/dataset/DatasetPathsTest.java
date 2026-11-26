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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatasetPathsTest {

  @Test
  public void placesEachDatasetInItsOwnFolder() {
    assertEquals( "/etc/datasets/monthly-orders", DatasetPaths.folder( "monthly-orders" ) );
    assertEquals( "/etc/datasets/monthly-orders/dataset.json", DatasetPaths.definitionPath( "monthly-orders" ) );
  }

  @Test
  public void refusesIdsThatCouldLeaveTheFolder() {
    for ( String bad : new String[] { null, "", "..", "../etc", "a/b", "a\\b", "a:b", ".hidden", "-x", "has space" } ) {
      assertFalse( String.valueOf( bad ), DatasetPaths.isValidId( bad ) );
      try {
        DatasetPaths.definitionPath( bad );
        fail( "expected " + bad + " to be refused" );
      } catch ( IllegalArgumentException expected ) {
        // refused
      }
    }
    assertTrue( DatasetPaths.isValidId( "Sales_2026.v2" ) );
  }
}
