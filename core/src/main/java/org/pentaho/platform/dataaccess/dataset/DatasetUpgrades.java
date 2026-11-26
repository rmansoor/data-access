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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Brings a stored definition up to {@link #CURRENT_VERSION} before it is bound to
 * {@link DatasetDefinition}. Each step takes the JSON of version N and returns version N + 1, so a
 * file written by any earlier release still opens. To change the format: bump CURRENT_VERSION and
 * register the step from the previous version in {@link #standard()}.
 */
public final class DatasetUpgrades {

  /** The version this release writes. */
  public static final int CURRENT_VERSION = 1;

  private final int currentVersion;
  private final Map<Integer, UnaryOperator<ObjectNode>> steps;

  /**
   * @param currentVersion the version upgrades end at
   * @param steps step from version N (the key) to N + 1
   */
  public DatasetUpgrades( int currentVersion, Map<Integer, UnaryOperator<ObjectNode>> steps ) {
    this.currentVersion = currentVersion;
    this.steps = Collections.unmodifiableMap( new TreeMap<>( steps ) );
  }

  /** The upgrades this release ships. Version 1 is the first, so there are none yet. */
  public static DatasetUpgrades standard() {
    return new DatasetUpgrades( CURRENT_VERSION, Collections.emptyMap() );
  }

  public int getCurrentVersion() {
    return currentVersion;
  }

  /**
   * Upgrades {@code json} in place and returns it.
   *
   * @throws DatasetFormatException when the version is missing, unknown, newer than this release,
   *                                or a step is missing
   */
  public ObjectNode upgrade( ObjectNode json ) {
    JsonNode versionNode = json.get( "schemaVersion" );
    if ( versionNode == null || !versionNode.canConvertToInt() || !versionNode.isIntegralNumber() ) {
      throw new DatasetFormatException( "schemaVersion", "is required and must be a whole number" );
    }
    int version = versionNode.intValue();
    if ( version < 1 ) {
      throw new DatasetFormatException( "schemaVersion", "must be 1 or more, not " + version );
    }
    if ( version > currentVersion ) {
      throw new DatasetFormatException( "schemaVersion",
        version + " is newer than this server supports (" + currentVersion + "); upgrade the server to open it" );
    }
    ObjectNode node = json;
    while ( version < currentVersion ) {
      UnaryOperator<ObjectNode> step = steps.get( version );
      if ( step == null ) {
        throw new DatasetFormatException( "schemaVersion", "no upgrade from version " + version );
      }
      node = step.apply( node );
      version++;
      node.put( "schemaVersion", version );
    }
    return node;
  }
}
