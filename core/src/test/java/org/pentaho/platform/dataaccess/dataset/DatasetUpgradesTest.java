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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatasetUpgradesTest {

  @Test
  public void runsEachStepInOrderAndStampsTheNewVersion() throws Exception {
    Map<Integer, UnaryOperator<ObjectNode>> steps = new HashMap<>();
    // v1 -> v2: "title" was renamed "name".
    steps.put( 1, node -> {
      node.set( "name", node.remove( "title" ) );
      return node;
    } );
    // v2 -> v3: a default owner was introduced.
    steps.put( 2, node -> {
      if ( !node.has( "owner" ) ) {
        node.put( "owner", "admin" );
      }
      return node;
    } );
    DatasetUpgrades upgrades = new DatasetUpgrades( 3, steps );

    ObjectNode v1 = (ObjectNode) DatasetJson.mapper().readTree( "{\"schemaVersion\":1,\"id\":\"a\",\"title\":\"Sales\"}" );
    ObjectNode v3 = upgrades.upgrade( v1 );

    assertEquals( 3, v3.get( "schemaVersion" ).intValue() );
    assertEquals( "Sales", v3.get( "name" ).asText() );
    assertEquals( "admin", v3.get( "owner" ).asText() );
    assertTrue( !v3.has( "title" ) );
  }

  @Test
  public void leavesTheCurrentVersionAlone() throws Exception {
    ObjectNode node = (ObjectNode) DatasetJson.mapper().readTree( "{\"schemaVersion\":1,\"id\":\"a\"}" );
    assertEquals( node.deepCopy(), DatasetUpgrades.standard().upgrade( node ) );
  }

  @Test
  public void namesAMissingStep() throws Exception {
    DatasetUpgrades upgrades = new DatasetUpgrades( 3, new HashMap<>() );
    ObjectNode node = (ObjectNode) DatasetJson.mapper().readTree( "{\"schemaVersion\":2}" );
    try {
      upgrades.upgrade( node );
      fail( "expected a missing upgrade step" );
    } catch ( DatasetFormatException e ) {
      assertEquals( "schemaVersion", e.getPath() );
      assertTrue( e.getMessage(), e.getMessage().contains( "no upgrade from version 2" ) );
    }
  }

  @Test
  public void refusesVersionsBelowOne() throws Exception {
    ObjectNode node = (ObjectNode) DatasetJson.mapper().readTree( "{\"schemaVersion\":0}" );
    try {
      DatasetUpgrades.standard().upgrade( node );
      fail( "expected version 0 to be refused" );
    } catch ( DatasetFormatException e ) {
      assertTrue( e.getMessage(), e.getMessage().contains( "must be 1 or more" ) );
    }
  }

  @Test
  public void readsAnOlderFileThroughTheReader() {
    Map<Integer, UnaryOperator<ObjectNode>> steps = new HashMap<>();
    steps.put( 1, node -> {
      node.set( "name", node.remove( "title" ) );
      return node;
    } );
    DatasetJson reader = new DatasetJson( new DatasetUpgrades( 2, steps ) );
    DatasetDefinition d = reader.read( "{\"schemaVersion\":1,\"id\":\"a\",\"title\":\"Sales\"}" );
    assertEquals( "Sales", d.getName() );
    assertEquals( 2, d.getSchemaVersion() );
  }
}
