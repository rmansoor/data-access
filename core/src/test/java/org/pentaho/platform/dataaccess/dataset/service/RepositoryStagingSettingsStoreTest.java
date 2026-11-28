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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.pentaho.platform.api.engine.ISecurityHelper;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.data.simple.SimpleRepositoryFileData;
import org.pentaho.platform.dataaccess.dataset.source.StagingSettings;
import org.pentaho.platform.engine.security.SecurityHelper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RepositoryStagingSettingsStoreTest {

  private MockedStatic<SecurityHelper> securityHelper;
  private IUnifiedRepository repository;
  private RepositoryStagingSettingsStore store;

  @Before
  @SuppressWarnings( "unchecked" )
  public void setUp() throws Exception {
    ISecurityHelper security = mock( ISecurityHelper.class );
    when( security.runAsSystem( any( Callable.class ) ) ).thenAnswer( call -> ( (Callable<?>) call.getArgument( 0 ) ).call() );
    securityHelper = mockStatic( SecurityHelper.class );
    securityHelper.when( SecurityHelper::getInstance ).thenReturn( security );
    repository = mock( IUnifiedRepository.class );
    store = new RepositoryStagingSettingsStore( () -> repository );
  }

  @After
  public void tearDown() {
    securityHelper.close();
  }

  @Test
  public void readsNothingSavedAsEmptySettings() {
    StagingSettings s = store.get();
    assertNull( s.getConnection() );
    assertNull( s.getSchema() );
  }

  @Test
  public void createsTheFileOnceThenUpdatesIt() throws Exception {
    RepositoryFile root = new RepositoryFile.Builder( "root-id", "datasets" ).folder( true ).build();
    when( repository.getFile( "/etc/datasets" ) ).thenReturn( root );
    store.set( new StagingSettings( "Warehouse", "STAGE" ) );
    ArgumentCaptor<SimpleRepositoryFileData> data = ArgumentCaptor.forClass( SimpleRepositoryFileData.class );
    verify( repository ).createFile( eq( "root-id" ), any( RepositoryFile.class ), data.capture(), anyString() );
    String json = new String( data.getValue().getInputStream().readAllBytes(), StandardCharsets.UTF_8 );
    assertEquals( "{\"connection\":\"Warehouse\",\"schema\":\"STAGE\"}", json );

    RepositoryFile file = new RepositoryFile.Builder( "file-id", "settings.json" ).build();
    when( repository.getFile( "/etc/datasets/settings.json" ) ).thenReturn( file );
    when( repository.getDataForRead( "file-id", SimpleRepositoryFileData.class ) ).thenReturn(
      new SimpleRepositoryFileData( new ByteArrayInputStream( json.getBytes( StandardCharsets.UTF_8 ) ), "UTF-8", "application/json" ) );
    assertEquals( "Warehouse", store.get().getConnection() );
    store.set( new StagingSettings( null, null ) );
    verify( repository ).updateFile( eq( file ), any( SimpleRepositoryFileData.class ), anyString() );
  }
}
