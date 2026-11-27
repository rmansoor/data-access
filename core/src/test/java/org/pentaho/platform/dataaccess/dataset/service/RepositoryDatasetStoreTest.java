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
import org.pentaho.platform.api.repository2.unified.IRepositoryFileData;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryRequest;
import org.pentaho.platform.api.repository2.unified.data.simple.SimpleRepositoryFileData;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.engine.security.SecurityHelper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RepositoryDatasetStoreTest {

  private MockedStatic<SecurityHelper> securityHelper;
  private IUnifiedRepository repository;
  private RepositoryDatasetStore store;
  private final DatasetJson json = new DatasetJson();

  @Before
  @SuppressWarnings( "unchecked" )
  public void setUp() throws Exception {
    ISecurityHelper helper = mock( ISecurityHelper.class );
    when( helper.runAsSystem( any( Callable.class ) ) ).thenAnswer( call -> ( (Callable<?>) call.getArgument( 0 ) ).call() );
    securityHelper = mockStatic( SecurityHelper.class );
    securityHelper.when( SecurityHelper::getInstance ).thenReturn( helper );
    repository = mock( IUnifiedRepository.class );
    store = new RepositoryDatasetStore( repository );
  }

  @After
  public void tearDown() {
    securityHelper.close();
  }

  private static RepositoryFile file( String id, String name, boolean folder, Object versionId ) {
    return new RepositoryFile.Builder( id, name ).folder( folder ).versionId( (java.io.Serializable) versionId )
      .lastModificationDate( new Date( 1_700_000_000_000L ) ).build();
  }

  private static DatasetDefinition definition( String id, String name ) {
    DatasetDefinition d = new DatasetDefinition();
    d.setId( id );
    d.setName( name );
    return d;
  }

  private void stored( String id, String name, String versionId ) {
    RepositoryFile f = file( id + "-file", "dataset.json", false, versionId );
    when( repository.getFile( "/etc/datasets/" + id + "/dataset.json" ) ).thenReturn( f );
    when( repository.getFile( "/etc/datasets/" + id ) ).thenReturn( file( id + "-folder", id, true, null ) );
    when( repository.getDataForRead( f.getId(), SimpleRepositoryFileData.class ) ).thenAnswer( a -> new SimpleRepositoryFileData(
      new ByteArrayInputStream( json.write( definition( id, name ) ).getBytes( StandardCharsets.UTF_8 ) ), "UTF-8", "application/json" ) );
  }

  @Test
  public void createsTheDatasetsFolderOnFirstUseAndAVersionedDefinition() {
    RepositoryFile etc = file( "etc", "etc", true, null );
    RepositoryFile root = file( "root", "datasets", true, null );
    RepositoryFile folder = file( "orders-folder", "orders", true, null );
    when( repository.getFile( "/etc" ) ).thenReturn( etc );
    when( repository.createFolder( eq( "etc" ), any( RepositoryFile.class ), anyString() ) ).thenReturn( root );
    when( repository.createFolder( eq( "root" ), any( RepositoryFile.class ), anyString() ) ).thenReturn( folder );
    when( repository.createFile( eq( "orders-folder" ), any( RepositoryFile.class ), any( IRepositoryFileData.class ), anyString() ) )
      .thenReturn( file( "orders-file", "dataset.json", false, "1.0" ) );

    StoredDataset created = store.create( definition( "orders", "Orders" ) );

    assertEquals( "1.0", created.getVersion() );
    ArgumentCaptor<RepositoryFile> made = ArgumentCaptor.forClass( RepositoryFile.class );
    verify( repository ).createFile( eq( "orders-folder" ), made.capture(), any( IRepositoryFileData.class ), anyString() );
    assertEquals( "dataset.json", made.getValue().getName() );
    assertTrue( "the definition is versioned", made.getValue().isVersioned() );
  }

  @Test
  public void refusesToCreateOverAnExistingDataset() {
    stored( "orders", "Orders", "1.0" );
    try {
      store.create( definition( "orders", "Again" ) );
      fail( "expected ALREADY_EXISTS" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.ALREADY_EXISTS, e.getCode() );
    }
  }

  @Test
  public void readsTheDefinitionWithItsVersion() {
    stored( "orders", "Orders", "1.3" );
    StoredDataset read = store.load( "orders" ).orElseThrow();
    assertEquals( "Orders", read.getDefinition().getName() );
    assertEquals( "1.3", read.getVersion() );
    assertTrue( store.load( "missing" ).isEmpty() );
  }

  @Test
  public void updatesOnlyTheVersionTheCallerRead() {
    stored( "orders", "Orders", "1.3" );
    try {
      store.update( definition( "orders", "Lost" ), "1.2" );
      fail( "expected STALE_VERSION" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.STALE_VERSION, e.getCode() );
    }
    verify( repository, never() ).updateFile( any(), any(), anyString() );

    when( repository.updateFile( any(), any(), anyString() ) ).thenReturn( file( "orders-file", "dataset.json", false, "1.4" ) );
    assertEquals( "1.4", store.update( definition( "orders", "Renamed" ), "1.3" ).getVersion() );
  }

  @Test
  public void deletesIntoTheTrashSoItCanBeRestored() {
    stored( "orders", "Orders", "1.3" );
    store.delete( "orders", "1.3" );
    verify( repository ).deleteFile( eq( "orders-folder" ), eq( false ), anyString() );
  }

  @Test
  public void listsReadableDatasetsAndSkipsOthers() {
    RepositoryFile root = file( "root", "datasets", true, null );
    when( repository.getFile( "/etc/datasets" ) ).thenReturn( root );
    stored( "orders", "Orders", "1.0" );
    RepositoryFile broken = file( "broken-file", "dataset.json", false, "1.0" );
    when( repository.getFile( "/etc/datasets/broken/dataset.json" ) ).thenReturn( broken );
    when( repository.getDataForRead( "broken-file", SimpleRepositoryFileData.class ) ).thenReturn( new SimpleRepositoryFileData(
      new ByteArrayInputStream( "{\"schemaVersion\":1,\"oops\":true}".getBytes( StandardCharsets.UTF_8 ) ), "UTF-8", "application/json" ) );
    when( repository.getChildren( any( RepositoryRequest.class ) ) ).thenReturn( List.of(
      file( "orders-folder", "orders", true, null ),
      file( "broken-folder", "broken", true, null ),
      file( "stray", "notes.txt", false, null ),
      file( "bad-name", "..", true, null ) ) );

    List<StoredDataset> all = store.list();

    assertEquals( 1, all.size() );
    assertEquals( "orders", all.get( 0 ).getDefinition().getId() );
  }

  @Test
  public void usesTheModificationTimeWhenVersioningIsOff() {
    assertEquals( "1700000000000", RepositoryDatasetStore.versionOf( file( "f", "dataset.json", false, null ) ) );
  }
}
