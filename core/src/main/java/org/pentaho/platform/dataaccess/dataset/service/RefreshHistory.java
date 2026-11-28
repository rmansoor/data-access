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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.data.simple.SimpleRepositoryFileData;
import org.pentaho.platform.dataaccess.dataset.DatasetPaths;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Each file dataset's loads, newest first; the oldest are dropped past {@link #KEEP}. */
public interface RefreshHistory {

  int KEEP = 100;

  void add( String datasetId, DatasetRefresh refresh );

  /** Newest first. */
  List<DatasetRefresh> list( String datasetId );

  void deleteAll( String datasetId );

  /** Kept in memory; for tests. */
  class InMemory implements RefreshHistory {
    private final Map<String, List<DatasetRefresh>> byDataset = new HashMap<>();

    @Override
    public synchronized void add( String datasetId, DatasetRefresh refresh ) {
      List<DatasetRefresh> list = byDataset.computeIfAbsent( datasetId, k -> new ArrayList<>() );
      list.add( 0, refresh );
      while ( list.size() > KEEP ) {
        list.remove( list.size() - 1 );
      }
    }

    @Override
    public synchronized List<DatasetRefresh> list( String datasetId ) {
      return new ArrayList<>( byDataset.getOrDefault( datasetId, List.of() ) );
    }

    @Override
    public synchronized void deleteAll( String datasetId ) {
      byDataset.remove( datasetId );
    }
  }

  /** In the repository, one JSON file per dataset under {@code /etc/datasets/refreshes}, written as the system. */
  class InRepository implements RefreshHistory {
    static final String FOLDER = DatasetPaths.ROOT + "/refreshes";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Supplier<IUnifiedRepository> repository;

    public InRepository() {
      this( () -> PentahoSystem.get( IUnifiedRepository.class ) );
    }

    public InRepository( Supplier<IUnifiedRepository> repository ) {
      this.repository = repository;
    }

    @Override
    public synchronized void add( String datasetId, DatasetRefresh refresh ) {
      asSystem( () -> {
        List<DatasetRefresh> list = read( datasetId );
        list.add( 0, refresh );
        while ( list.size() > KEEP ) {
          list.remove( list.size() - 1 );
        }
        write( datasetId, list );
        return null;
      } );
    }

    @Override
    public synchronized List<DatasetRefresh> list( String datasetId ) {
      return asSystem( () -> read( datasetId ) );
    }

    @Override
    public synchronized void deleteAll( String datasetId ) {
      asSystem( () -> {
        RepositoryFile file = repository.get().getFile( path( datasetId ) );
        if ( file != null ) {
          repository.get().deleteFile( file.getId(), true, "Dataset deleted" );
        }
        return null;
      } );
    }

    private static String path( String datasetId ) {
      return FOLDER + "/" + datasetId + ".json";
    }

    private List<DatasetRefresh> read( String datasetId ) throws Exception {
      IUnifiedRepository repo = repository.get();
      RepositoryFile file = repo.getFile( path( datasetId ) );
      if ( file == null ) {
        return new ArrayList<>();
      }
      try ( InputStream in = repo.getDataForRead( file.getId(), SimpleRepositoryFileData.class ).getInputStream() ) {
        return MAPPER.readValue( in, new TypeReference<List<DatasetRefresh>>() { } );
      }
    }

    private void write( String datasetId, List<DatasetRefresh> list ) throws Exception {
      IUnifiedRepository repo = repository.get();
      SimpleRepositoryFileData data = new SimpleRepositoryFileData( new ByteArrayInputStream( MAPPER.writeValueAsBytes( list ) ),
        StandardCharsets.UTF_8.name(), "application/json" );
      RepositoryFile file = repo.getFile( path( datasetId ) );
      if ( file != null ) {
        repo.updateFile( file, data, "Record a dataset refresh" );
        return;
      }
      RepositoryFile folder = repo.getFile( FOLDER );
      if ( folder == null ) {
        RepositoryFile root = repo.getFile( DatasetPaths.ROOT );
        if ( root == null ) {
          root = repo.createFolder( repo.getFile( "/etc" ).getId(), new RepositoryFile.Builder( "datasets" ).folder( true ).build(),
            "Create the datasets folder" );
        }
        folder = repo.createFolder( root.getId(), new RepositoryFile.Builder( "refreshes" ).folder( true ).build(),
          "Create the dataset refresh history folder" );
      }
      repo.createFile( folder.getId(), new RepositoryFile.Builder( datasetId + ".json" ).build(), data, "Record a dataset refresh" );
    }

    private static <T> T asSystem( Callable<T> work ) {
      try {
        return SecurityHelper.getInstance().runAsSystem( work );
      } catch ( DatasetApiException e ) {
        throw e;
      } catch ( Exception e ) {
        throw new DatasetApiException( DatasetApiException.Code.INTERNAL, "", "The refresh history could not be read or written",
          null, e );
      }
    }
  }
}
