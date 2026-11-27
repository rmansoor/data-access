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

import org.pentaho.platform.api.repository2.unified.IAclNodeHelper;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryFileAce;
import org.pentaho.platform.api.repository2.unified.RepositoryFileAcl;
import org.pentaho.platform.api.repository2.unified.RepositoryFilePermission;
import org.pentaho.platform.api.repository2.unified.RepositoryFileSid;
import org.pentaho.platform.dataaccess.dataset.DatasetPaths;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.repository2.unified.jcr.JcrAclNodeHelper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps sharing the way metadata and Mondrian data sources already do: an ACL node in the
 * repository pointing at the dataset's folder ({@link IAclNodeHelper}). No ACL node means
 * unrestricted. The ACL node's own ACL is what the repository checks, as the caller, so the same
 * users, roles and permission rules apply as everywhere else in Pentaho.
 * <p>
 * READ maps to the repository's READ; WRITE to READ, WRITE and DELETE.
 */
public class RepositoryDatasetSharing implements DatasetSharing {

  private final Supplier<IUnifiedRepository> repositorySupplier;
  private final Function<IUnifiedRepository, IAclNodeHelper> helperFactory;
  private final Supplier<String> adminRole;
  private IUnifiedRepository repository;
  private IAclNodeHelper helper;

  public RepositoryDatasetSharing() {
    this( () -> PentahoSystem.get( IUnifiedRepository.class ), JcrAclNodeHelper::new,
      () -> PentahoSystem.get( String.class, "singleTenantAdminAuthorityName", PentahoSessionHolder.getSession() ) );
  }

  public RepositoryDatasetSharing( Supplier<IUnifiedRepository> repository,
                                   Function<IUnifiedRepository, IAclNodeHelper> helperFactory,
                                   Supplier<String> adminRole ) {
    this.repositorySupplier = repository;
    this.helperFactory = helperFactory;
    this.adminRole = adminRole;
  }

  @Override
  public DatasetShare get( String id ) {
    RepositoryFile folder = folder( id );
    // As system: reading an ACL needs ACL rights the dataset's readers don't have.
    RepositoryFileAcl acl = asSystem( () -> helper().getAclFor( folder ) );
    if ( acl == null ) {
      return DatasetShare.unrestricted();
    }
    String admin = adminRole.get();
    List<DatasetShare.Entry> entries = new ArrayList<>();
    for ( RepositoryFileAce ace : acl.getAces() ) {
      RepositoryFileSid sid = ace.getSid();
      boolean addedAdmin = sid.getType() == RepositoryFileSid.Type.ROLE && sid.getName().equals( admin )
        && ace.getPermissions().contains( RepositoryFilePermission.ALL );
      if ( addedAdmin ) {
        // getAclFor adds the administrator role for display; administrators always have access anyway.
        continue;
      }
      entries.add( new DatasetShare.Entry( sid.getName(),
        sid.getType() == RepositoryFileSid.Type.USER ? DatasetShare.RecipientType.USER : DatasetShare.RecipientType.ROLE,
        writes( ace.getPermissions() ) ? DatasetShare.Access.WRITE : DatasetShare.Access.READ ) );
    }
    return DatasetShare.restricted( entries ).normalized();
  }

  @Override
  public void set( String id, DatasetShare share ) {
    RepositoryFile folder = folder( id );
    RepositoryFileAcl acl = null;
    if ( share != null && share.isRestricted() ) {
      // The owner of the ACL itself is a placeholder: IAclNodeHelper keeps the node's existing owner.
      RepositoryFileAcl.Builder builder = new RepositoryFileAcl.Builder( "datasets" );
      List<RepositoryFileAce> aces = new ArrayList<>();
      for ( DatasetShare.Entry e : share.getEntries() ) {
        aces.add( new RepositoryFileAce( new RepositoryFileSid( e.getRecipient(),
          e.getType() == DatasetShare.RecipientType.USER ? RepositoryFileSid.Type.USER : RepositoryFileSid.Type.ROLE ),
          permissions( e.getAccess() ) ) );
      }
      acl = builder.aces( aces ).build();
    }
    helper().setAclFor( folder, acl );
  }

  @Override
  public boolean canRead( String id ) {
    return passes( id, EnumSet.of( RepositoryFilePermission.READ ) );
  }

  @Override
  public boolean canWrite( String id ) {
    return passes( id, EnumSet.of( RepositoryFilePermission.WRITE ) );
  }

  // ------------------------------------------------------------------------------------------------

  /** Must run as the caller: the helper checks the caller's access to the ACL node. */
  private boolean passes( String id, EnumSet<RepositoryFilePermission> needed ) {
    RepositoryFile folder = asSystem( () -> repository().getFile( DatasetPaths.folder( id ) ) );
    return folder != null && helper().canAccess( folder, needed );
  }

  private RepositoryFile folder( String id ) {
    RepositoryFile folder = asSystem( () -> repository().getFile( DatasetPaths.folder( id ) ) );
    if ( folder == null ) {
      throw new DatasetApiException( Code.NOT_FOUND, "No dataset with id \"" + id + "\"" );
    }
    return folder;
  }

  static EnumSet<RepositoryFilePermission> permissions( DatasetShare.Access access ) {
    return access == DatasetShare.Access.WRITE
      ? EnumSet.of( RepositoryFilePermission.READ, RepositoryFilePermission.WRITE, RepositoryFilePermission.DELETE )
      : EnumSet.of( RepositoryFilePermission.READ );
  }

  private static boolean writes( EnumSet<RepositoryFilePermission> permissions ) {
    return permissions.contains( RepositoryFilePermission.WRITE ) || permissions.contains( RepositoryFilePermission.ALL );
  }

  private IUnifiedRepository repository() {
    if ( repository == null ) {
      repository = repositorySupplier.get();
    }
    return repository;
  }

  private IAclNodeHelper helper() {
    if ( helper == null ) {
      helper = helperFactory.apply( repository() );
    }
    return helper;
  }

  private static <T> T asSystem( Callable<T> work ) {
    try {
      return SecurityHelper.getInstance().runAsSystem( work );
    } catch ( DatasetApiException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new DatasetApiException( Code.INTERNAL, "", "The repository could not complete the request", null, e );
    }
  }
}
