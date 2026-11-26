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

import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryRequest;
import org.pentaho.platform.api.repository2.unified.data.simple.SimpleRepositoryFileData;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetFormatException;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.DatasetPaths;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

/**
 * Keeps each dataset in the repository as {@code /etc/datasets/{id}/dataset.json}.
 * <p>
 * The file is versioned, so repository history is the dataset's version history. The version token
 * is the repository version id (or the last-modified time where versioning is off). Repository
 * calls run as the system user, as the data-access services do for {@code /etc}: who may do what is
 * decided by {@link DatasetService} before the store is reached. Writes are serialized within this
 * server so the version check and the write can't interleave.
 */
public class RepositoryDatasetStore implements DatasetStore {

  private static final String MIME = "application/json";
  private static final Object LOCK = new Object();

  private final java.util.function.Supplier<IUnifiedRepository> repositorySupplier;
  private final DatasetJson json = new DatasetJson();
  private IUnifiedRepository repository;

  /** Looks the repository up on first use, so the plugin's Spring context can start before it is ready. */
  public RepositoryDatasetStore() {
    this( () -> PentahoSystem.get( IUnifiedRepository.class ) );
  }

  public RepositoryDatasetStore( IUnifiedRepository repository ) {
    this( () -> repository );
  }

  private RepositoryDatasetStore( java.util.function.Supplier<IUnifiedRepository> repositorySupplier ) {
    this.repositorySupplier = repositorySupplier;
  }

  @Override
  public List<StoredDataset> list() {
    return asSystem( () -> {
      List<StoredDataset> all = new ArrayList<>();
      RepositoryFile root = repository.getFile( DatasetPaths.ROOT );
      if ( root == null ) {
        return all;
      }
      // Child folders, hidden included (/etc is hidden); the folder id goes where the request takes a path, as DefaultUnifiedRepository does.
      RepositoryRequest request = new RepositoryRequest( root.getId().toString(), true, -1, null );
      request.setTypes( RepositoryRequest.FILES_TYPE_FILTER.FOLDERS );
      for ( RepositoryFile folder : repository.getChildren( request ) ) {
        if ( folder.isFolder() && DatasetPaths.isValidId( folder.getName() ) ) {
          readQuietly( folder.getName() ).ifPresent( all::add );
        }
      }
      return all;
    } );
  }

  @Override
  public Optional<StoredDataset> load( String id ) {
    return asSystem( () -> {
      RepositoryFile file = repository.getFile( DatasetPaths.definitionPath( id ) );
      return file == null ? Optional.<StoredDataset>empty() : Optional.of( read( file ) );
    } );
  }

  @Override
  public StoredDataset create( DatasetDefinition definition ) {
    return asSystem( () -> {
      synchronized ( LOCK ) {
        String id = definition.getId();
        if ( repository.getFile( DatasetPaths.folder( id ) ) != null ) {
          throw new DatasetApiException( Code.ALREADY_EXISTS, "id", "A dataset with id \"" + id + "\" already exists", null, null );
        }
        RepositoryFile folder = repository.createFolder( rootFolder().getId(),
          new RepositoryFile.Builder( id ).folder( true ).build(), "Create dataset " + id );
        RepositoryFile file = repository.createFile( folder.getId(),
          new RepositoryFile.Builder( DatasetPaths.DEFINITION_FILE ).versioned( true ).build(), data( definition ),
          "Create dataset " + id );
        return stored( definition, file );
      }
    } );
  }

  @Override
  public StoredDataset update( DatasetDefinition definition, String expectedVersion ) {
    return asSystem( () -> {
      synchronized ( LOCK ) {
        RepositoryFile file = current( definition.getId(), expectedVersion );
        RepositoryFile updated = repository.updateFile( file, data( definition ), "Update dataset " + definition.getId() );
        return stored( definition, updated );
      }
    } );
  }

  @Override
  public void delete( String id, String expectedVersion ) {
    asSystem( () -> {
      synchronized ( LOCK ) {
        current( id, expectedVersion );
        RepositoryFile folder = repository.getFile( DatasetPaths.folder( id ) );
        // Into the trash, not gone: a deleted dataset can be restored like any repository file.
        repository.deleteFile( folder.getId(), false, "Delete dataset " + id );
        return null;
      }
    } );
  }

  // ------------------------------------------------------------------------------------------------

  /** The stored file, after checking the caller's expected version. */
  private RepositoryFile current( String id, String expectedVersion ) {
    RepositoryFile file = repository.getFile( DatasetPaths.definitionPath( id ) );
    if ( file == null ) {
      throw new DatasetApiException( Code.NOT_FOUND, "No dataset with id \"" + id + "\"" );
    }
    String version = versionOf( file );
    if ( !version.equals( expectedVersion ) ) {
      throw new DatasetApiException( Code.STALE_VERSION,
        "The dataset changed since it was read (now version " + version + "); read it again and reapply the change" );
    }
    return file;
  }

  private RepositoryFile rootFolder() {
    RepositoryFile root = repository.getFile( DatasetPaths.ROOT );
    if ( root != null ) {
      return root;
    }
    RepositoryFile etc = repository.getFile( "/etc" );
    return repository.createFolder( etc.getId(), new RepositoryFile.Builder( "datasets" ).folder( true ).build(), "Create the datasets folder" );
  }

  private Optional<StoredDataset> readQuietly( String id ) {
    try {
      return load( id );
    } catch ( DatasetFormatException e ) {
      // One unreadable file must not hide every other dataset from the list.
      return Optional.empty();
    }
  }

  private StoredDataset read( RepositoryFile file ) throws IOException {
    SimpleRepositoryFileData data = repository.getDataForRead( file.getId(), SimpleRepositoryFileData.class );
    try ( InputStream in = data.getInputStream() ) {
      return stored( json.read( in ), file );
    }
  }

  private SimpleRepositoryFileData data( DatasetDefinition definition ) {
    byte[] bytes = json.write( definition ).getBytes( StandardCharsets.UTF_8 );
    return new SimpleRepositoryFileData( new ByteArrayInputStream( bytes ), StandardCharsets.UTF_8.name(), MIME );
  }

  private static StoredDataset stored( DatasetDefinition definition, RepositoryFile file ) {
    Instant updated = file.getLastModifiedDate() == null ? Instant.now() : file.getLastModifiedDate().toInstant();
    return new StoredDataset( definition, versionOf( file ), updated );
  }

  static String versionOf( RepositoryFile file ) {
    if ( file.getVersionId() != null ) {
      return String.valueOf( file.getVersionId() );
    }
    return file.getLastModifiedDate() == null ? "0" : String.valueOf( file.getLastModifiedDate().getTime() );
  }

  private <T> T asSystem( Callable<T> work ) {
    if ( repository == null ) {
      repository = repositorySupplier.get();
    }
    try {
      return SecurityHelper.getInstance().runAsSystem( work );
    } catch ( DatasetApiException | DatasetFormatException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new DatasetApiException( Code.INTERNAL, "", "The repository could not complete the request", null, e );
    }
  }
}
