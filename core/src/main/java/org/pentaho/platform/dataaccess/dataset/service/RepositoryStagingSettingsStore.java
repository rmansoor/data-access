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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.data.simple.SimpleRepositoryFileData;
import org.pentaho.platform.dataaccess.dataset.DatasetPaths;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.source.StagingSettings;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Dataset settings in the repository, at {@code /etc/datasets/settings.json} (the store lists folders only). */
public class RepositoryStagingSettingsStore implements StagingSettings.Store {

  static final String PATH = DatasetPaths.ROOT + "/settings.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Supplier<IUnifiedRepository> repositorySupplier;

  public RepositoryStagingSettingsStore() {
    this( () -> PentahoSystem.get( IUnifiedRepository.class ) );
  }

  public RepositoryStagingSettingsStore( Supplier<IUnifiedRepository> repository ) {
    this.repositorySupplier = repository;
  }

  @Override
  public StagingSettings get() {
    return asSystem( () -> {
      IUnifiedRepository repository = repositorySupplier.get();
      RepositoryFile file = repository.getFile( PATH );
      if ( file == null ) {
        return new StagingSettings();
      }
      SimpleRepositoryFileData data = repository.getDataForRead( file.getId(), SimpleRepositoryFileData.class );
      try ( InputStream in = data.getInputStream() ) {
        return MAPPER.readValue( in, StagingSettings.class );
      }
    } );
  }

  @Override
  public void set( StagingSettings settings ) {
    asSystem( () -> {
      IUnifiedRepository repository = repositorySupplier.get();
      SimpleRepositoryFileData data = new SimpleRepositoryFileData(
        new ByteArrayInputStream( MAPPER.writeValueAsBytes( settings ) ), StandardCharsets.UTF_8.name(), "application/json" );
      RepositoryFile file = repository.getFile( PATH );
      if ( file != null ) {
        repository.updateFile( file, data, "Update dataset settings" );
        return null;
      }
      RepositoryFile root = repository.getFile( DatasetPaths.ROOT );
      if ( root == null ) {
        root = repository.createFolder( repository.getFile( "/etc" ).getId(),
          new RepositoryFile.Builder( "datasets" ).folder( true ).build(), "Create the datasets folder" );
      }
      repository.createFile( root.getId(), new RepositoryFile.Builder( "settings.json" ).build(), data, "Create dataset settings" );
      return null;
    } );
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
