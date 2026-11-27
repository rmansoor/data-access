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

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link DatasetStore} in memory, for tests and local development. Definitions are copied through
 * JSON on the way in and out, so callers can't change stored state by holding on to an object.
 */
public class InMemoryDatasetStore implements DatasetStore {

  private final DatasetJson json = new DatasetJson();
  private final Clock clock;
  private final Map<String, String[]> data = new LinkedHashMap<>(); // id -> [json, version, updated epoch ms]
  private long nextVersion = 1;

  public InMemoryDatasetStore() {
    this( Clock.systemUTC() );
  }

  public InMemoryDatasetStore( Clock clock ) {
    this.clock = clock;
  }

  @Override
  public synchronized List<StoredDataset> list() {
    List<StoredDataset> all = new ArrayList<>();
    for ( String id : data.keySet() ) {
      all.add( read( id ) );
    }
    return all;
  }

  @Override
  public synchronized Optional<StoredDataset> load( String id ) {
    return data.containsKey( id ) ? Optional.of( read( id ) ) : Optional.empty();
  }

  @Override
  public synchronized StoredDataset create( DatasetDefinition definition ) {
    if ( data.containsKey( definition.getId() ) ) {
      throw new DatasetApiException( DatasetApiException.Code.ALREADY_EXISTS, "id",
        "A dataset with id \"" + definition.getId() + "\" already exists", null, null );
    }
    return write( definition );
  }

  @Override
  public synchronized StoredDataset update( DatasetDefinition definition, String expectedVersion ) {
    check( definition.getId(), expectedVersion );
    return write( definition );
  }

  @Override
  public synchronized void delete( String id, String expectedVersion ) {
    check( id, expectedVersion );
    data.remove( id );
  }

  private void check( String id, String expectedVersion ) {
    String[] row = data.get( id );
    if ( row == null ) {
      throw new DatasetApiException( DatasetApiException.Code.NOT_FOUND, "No dataset with id \"" + id + "\"" );
    }
    if ( !row[ 1 ].equals( expectedVersion ) ) {
      throw new DatasetApiException( DatasetApiException.Code.STALE_VERSION,
        "The dataset changed since it was read (now version " + row[ 1 ] + "); read it again and reapply the change" );
    }
  }

  private StoredDataset write( DatasetDefinition definition ) {
    String version = String.valueOf( nextVersion++ );
    data.put( definition.getId(), new String[] { json.write( definition ), version, String.valueOf( clock.millis() ) } );
    return read( definition.getId() );
  }

  private StoredDataset read( String id ) {
    String[] row = data.get( id );
    return new StoredDataset( json.read( row[ 0 ] ), row[ 1 ], java.time.Instant.ofEpochMilli( Long.parseLong( row[ 2 ] ) ) );
  }
}
