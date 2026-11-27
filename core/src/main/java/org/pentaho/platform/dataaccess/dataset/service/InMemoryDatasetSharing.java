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

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Sharing held in memory, matched against a given user and roles; for tests and previews. */
public class InMemoryDatasetSharing implements DatasetSharing {

  private final Map<String, DatasetShare> shares = new ConcurrentHashMap<>();
  private final Supplier<String> user;
  private final Supplier<? extends Collection<String>> roles;

  public InMemoryDatasetSharing( Supplier<String> user, Supplier<? extends Collection<String>> roles ) {
    this.user = user;
    this.roles = roles;
  }

  @Override
  public DatasetShare get( String id ) {
    return shares.getOrDefault( id, DatasetShare.unrestricted() ).normalized();
  }

  @Override
  public void set( String id, DatasetShare share ) {
    if ( share == null || !share.isRestricted() ) {
      shares.remove( id );
    } else {
      shares.put( id, share.normalized() );
    }
  }

  @Override
  public boolean canRead( String id ) {
    return passes( id, DatasetShare.Access.READ );
  }

  @Override
  public boolean canWrite( String id ) {
    return passes( id, DatasetShare.Access.WRITE );
  }

  private boolean passes( String id, DatasetShare.Access needed ) {
    DatasetShare share = shares.get( id );
    if ( share == null ) {
      return true;
    }
    String me = user.get();
    Collection<String> myRoles = roles.get();
    return share.getEntries().stream()
      .filter( e -> needed == DatasetShare.Access.READ || e.getAccess() == DatasetShare.Access.WRITE )
      .anyMatch( e -> e.getType() == DatasetShare.RecipientType.USER
        ? e.getRecipient().equals( me )
        : myRoles.contains( e.getRecipient() ) );
  }
}
