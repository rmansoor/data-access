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
import org.pentaho.platform.dataaccess.dataset.DatasetPaths;
import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.DatasetUpgrades;
import org.pentaho.platform.dataaccess.dataset.DatasetValidator;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

/**
 * The rules behind the datasets API, independent of HTTP: permissions, ids, ownership and
 * optimistic locking. Drafts may be incomplete (people save as they go); completeness is checked by
 * {@link #validate(String)} and, later, enforced at publish.
 */
public class DatasetService {

  static final int MAX_GENERATED_ID = 60;

  private final DatasetStore store;
  private final DatasetPermissions permissions;
  private final Supplier<String> currentUser;
  private final DatasetValidator validator = new DatasetValidator();

  public DatasetService( DatasetStore store, DatasetPermissions permissions, Supplier<String> currentUser ) {
    this.store = store;
    this.permissions = permissions;
    this.currentUser = currentUser;
  }

  /** Every dataset, by name. */
  public List<StoredDataset> list() {
    requireView();
    return store.list().stream()
      .sorted( Comparator.comparing( ( StoredDataset s ) -> String.valueOf( s.getDefinition().getName() ).toLowerCase( Locale.ROOT ) )
        .thenComparing( s -> s.getDefinition().getId() ) )
      .collect( Collectors.toList() );
  }

  public StoredDataset get( String id ) {
    requireView();
    return load( id );
  }

  /**
   * Creates a draft. Without an id, one is made from the name ("Monthly orders" -> "monthly-orders",
   * then "monthly-orders-2"…). The owner defaults to the caller.
   */
  public StoredDataset create( DatasetDefinition definition ) {
    requireManage();
    requireName( definition );
    if ( definition.getId() == null || definition.getId().trim().isEmpty() ) {
      definition.setId( uniqueId( definition.getName() ) );
    } else {
      requireValidId( definition.getId(), "id" );
    }
    if ( definition.getOwner() == null || definition.getOwner().trim().isEmpty() ) {
      definition.setOwner( currentUser.get() );
    }
    definition.setSchemaVersion( DatasetUpgrades.CURRENT_VERSION );
    return store.create( definition );
  }

  /**
   * Replaces a draft. {@code expectedVersion} is the version the caller read (the ETag); without it
   * the update is refused, so nobody overwrites a change they never saw.
   */
  public StoredDataset update( String id, DatasetDefinition definition, String expectedVersion ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    if ( definition.getId() == null ) {
      definition.setId( id );
    } else if ( !definition.getId().equals( id ) ) {
      throw new DatasetApiException( Code.INVALID_DEFINITION, "id",
        "is \"" + definition.getId() + "\" but the address names \"" + id + "\"; a dataset's id can't change", null, null );
    }
    requireName( definition );
    if ( definition.getOwner() == null || definition.getOwner().trim().isEmpty() ) {
      definition.setOwner( load( id ).getDefinition().getOwner() );
    }
    definition.setSchemaVersion( DatasetUpgrades.CURRENT_VERSION );
    return store.update( definition, expectedVersion );
  }

  /** What still has to be fixed before the dataset can be published; empty when it is ready. */
  public List<DatasetProblem> validate( String id ) {
    requireView();
    return validator.validate( load( id ).getDefinition() );
  }

  public void delete( String id, String expectedVersion ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    store.delete( id, expectedVersion );
  }

  // ------------------------------------------------------------------------------------------------

  private StoredDataset load( String id ) {
    requireValidId( id, "" );
    return store.load( id ).orElseThrow( () -> new DatasetApiException( Code.NOT_FOUND, "No dataset with id \"" + id + "\"" ) );
  }

  private void requireView() {
    if ( !permissions.canView() ) {
      throw new DatasetApiException( Code.FORBIDDEN, "You don't have permission to see datasets" );
    }
  }

  private void requireManage() {
    if ( !permissions.canManage() ) {
      throw new DatasetApiException( Code.FORBIDDEN, "You don't have permission to create or change datasets" );
    }
  }

  private static void requireName( DatasetDefinition d ) {
    if ( d.getName() == null || d.getName().trim().isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "name", "A dataset needs a name",
        List.of( new DatasetProblem( "name", "is required" ) ), null );
    }
  }

  private static void requireValidId( String id, String path ) {
    if ( !DatasetPaths.isValidId( id ) ) {
      throw new DatasetApiException( Code.INVALID_DEFINITION, path,
        "\"" + id + "\" is not a valid dataset id (letters, digits, '.', '-' and '_'; starts with a letter or digit)", null, null );
    }
  }

  private static void requireVersion( String expectedVersion ) {
    if ( expectedVersion == null || expectedVersion.isEmpty() ) {
      throw new DatasetApiException( Code.VERSION_REQUIRED,
        "Say which version you are changing: send the ETag from your last read as If-Match" );
    }
  }

  /** A readable, unused id from a name. */
  String uniqueId( String name ) {
    String base = slug( name );
    String id = base;
    for ( int n = 2; store.load( id ).isPresent(); n++ ) {
      id = base + "-" + n;
    }
    return id;
  }

  static String slug( String name ) {
    String s = java.text.Normalizer.normalize( name, java.text.Normalizer.Form.NFKD )
      .replaceAll( "\\p{M}", "" )
      .toLowerCase( Locale.ROOT )
      .replaceAll( "[^a-z0-9]+", "-" )
      .replaceAll( "^-+|-+$", "" );
    if ( s.length() > MAX_GENERATED_ID ) {
      s = s.substring( 0, MAX_GENERATED_ID ).replaceAll( "-+$", "" );
    }
    return s.isEmpty() ? "dataset" : s;
  }
}
