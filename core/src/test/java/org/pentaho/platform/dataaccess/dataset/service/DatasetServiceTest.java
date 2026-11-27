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

import org.junit.Before;
import org.junit.Test;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatasetServiceTest {

  private InMemoryDatasetStore store;
  private boolean canView;
  private boolean canManage;
  private DatasetService service;

  @Before
  public void setUp() {
    store = new InMemoryDatasetStore();
    canView = true;
    canManage = true;
    service = new DatasetService( store, new DatasetPermissions() {
      @Override
      public boolean canView() {
        return canView;
      }

      @Override
      public boolean canManage() {
        return canManage;
      }
    }, () -> "suzy" );
  }

  private static DatasetDefinition draft( String name ) {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( name );
    return d;
  }

  @Test
  public void createsADraftWithAnIdFromItsNameAndTheCallerAsOwner() {
    StoredDataset created = service.create( draft( "Monthly Orders (EMEA)" ) );
    assertEquals( "monthly-orders-emea", created.getDefinition().getId() );
    assertEquals( "suzy", created.getDefinition().getOwner() );
    assertEquals( "1", created.getVersion() );
  }

  @Test
  public void keepsGeneratedIdsUnique() {
    assertEquals( "sales", service.create( draft( "Sales" ) ).getDefinition().getId() );
    assertEquals( "sales-2", service.create( draft( "sales" ) ).getDefinition().getId() );
    assertEquals( "sales-3", service.create( draft( "SALES!" ) ).getDefinition().getId() );
  }

  @Test
  public void makesReadableIdsFromAnyName() {
    assertEquals( "cafe-umsatze", DatasetService.slug( "Café Umsätze" ) );
    assertEquals( "dataset", DatasetService.slug( "€€€" ) );
    assertEquals( DatasetService.MAX_GENERATED_ID, DatasetService.slug( "x".repeat( 200 ) ).length() );
  }

  @Test
  public void allowsIncompleteDraftsButReportsWhatBlocksPublishing() {
    service.create( draft( "Half done" ) );
    List<String> problems = service.validate( "half-done" ).stream().map( Object::toString ).collect( Collectors.toList() );
    assertEquals( List.of( "source: is required", "columns: needs at least one column" ), problems );
  }

  @Test
  public void refusesADraftWithoutAName() {
    expect( Code.VALIDATION_FAILED, () -> service.create( draft( " " ) ) );
  }

  @Test
  public void refusesAGivenIdThatIsUnsafeOrTaken() {
    DatasetDefinition d = draft( "x" );
    d.setId( "../etc" );
    expect( Code.INVALID_DEFINITION, () -> service.create( d ) );
    DatasetDefinition a = draft( "a" );
    a.setId( "same" );
    service.create( a );
    DatasetDefinition b = draft( "b" );
    b.setId( "same" );
    expect( Code.ALREADY_EXISTS, () -> service.create( b ) );
  }

  @Test
  public void updatesOnlyFromTheVersionLastRead() {
    StoredDataset v1 = service.create( draft( "Orders" ) );
    DatasetDefinition change = draft( "Orders, renamed" );
    StoredDataset v2 = service.update( "orders", change, v1.getVersion() );
    assertNotEquals( v1.getVersion(), v2.getVersion() );
    assertEquals( "Orders, renamed", service.get( "orders" ).getDefinition().getName() );
    // The owner carries over when the update leaves it out.
    assertEquals( "suzy", v2.getDefinition().getOwner() );

    // Someone still holding version 1 is refused, and nothing changes.
    expect( Code.STALE_VERSION, () -> service.update( "orders", draft( "Lost update" ), v1.getVersion() ) );
    assertEquals( "Orders, renamed", service.get( "orders" ).getDefinition().getName() );
    expect( Code.VERSION_REQUIRED, () -> service.update( "orders", draft( "No version" ), null ) );
  }

  @Test
  public void neverChangesADatasetsId() {
    StoredDataset v1 = service.create( draft( "Orders" ) );
    DatasetDefinition moved = draft( "Orders" );
    moved.setId( "other" );
    DatasetApiException e = expect( Code.INVALID_DEFINITION, () -> service.update( "orders", moved, v1.getVersion() ) );
    assertEquals( "id", e.getPath() );
  }

  @Test
  public void deletesOnlyFromTheVersionLastRead() {
    StoredDataset v1 = service.create( draft( "Orders" ) );
    service.update( "orders", draft( "Orders 2" ), v1.getVersion() );
    expect( Code.STALE_VERSION, () -> service.delete( "orders", v1.getVersion() ) );
    service.delete( "orders", service.get( "orders" ).getVersion() );
    expect( Code.NOT_FOUND, () -> service.get( "orders" ) );
  }

  @Test
  public void listsDatasetsByName() {
    service.create( draft( "beta" ) );
    service.create( draft( "Alpha" ) );
    service.create( draft( "gamma" ) );
    assertEquals( List.of( "Alpha", "beta", "gamma" ),
      service.list().stream().map( s -> s.getDefinition().getName() ).collect( Collectors.toList() ) );
  }

  @Test
  public void enforcesViewAndManagePermissions() {
    service.create( draft( "Orders" ) );
    canManage = false;
    expect( Code.FORBIDDEN, () -> service.create( draft( "More" ) ) );
    expect( Code.FORBIDDEN, () -> service.delete( "orders", "1" ) );
    assertEquals( 1, service.list().size() );
    canView = false;
    expect( Code.FORBIDDEN, () -> service.list() );
    expect( Code.FORBIDDEN, () -> service.get( "orders" ) );
  }

  @Test
  public void keepsStoredStateApartFromCallersObjects() {
    StoredDataset created = service.create( draft( "Orders" ) );
    created.getDefinition().setName( "Changed behind the store's back" );
    assertEquals( "Orders", service.get( "orders" ).getDefinition().getName() );
    assertTrue( new DatasetJson().write( service.get( "orders" ).getDefinition() ).contains( "\"owner\" : \"suzy\"" ) );
  }

  private static DatasetApiException expect( Code code, Runnable call ) {
    try {
      call.run();
      fail( "expected " + code );
      return null;
    } catch ( DatasetApiException e ) {
      assertEquals( e.getMessage(), code, e.getCode() );
      return e;
    }
  }
}
