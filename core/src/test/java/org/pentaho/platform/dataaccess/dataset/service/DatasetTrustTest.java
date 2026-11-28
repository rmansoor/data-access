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
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** CWBA-71: certification by permission holders, quality warnings from failed loads, certified first. */
public class DatasetTrustTest {

  private InMemoryDatasetStore store;
  private SourceAdapters sources;
  private String user;
  private boolean canCertify;
  private DatasetService service;

  @Before
  public void setUp() {
    store = new InMemoryDatasetStore();
    sources = SourceAdapters.none();
    user = "suzy";
    canCertify = false;
    service = new DatasetService( store, new DatasetPermissions() {
      @Override
      public boolean canView() {
        return true;
      }

      @Override
      public boolean canManage() {
        return true;
      }

      @Override
      public boolean canCertify() {
        return canCertify;
      }
    }, () -> user, new InMemoryDatasetSharing( () -> user, List::of ), sources );
  }

  private StoredDataset create( String name ) {
    return service.create( new DatasetJson().read( "{\"schemaVersion\":1,\"name\":\"" + name + "\",\"source\":{\"type\":\"SQL\","
      + "\"connection\":\"c\",\"sql\":\"select 1 as A\"},\"columns\":[{\"name\":\"A\",\"type\":\"INTEGER\"}]}" ) );
  }

  private static void assertForbidden( Runnable r ) {
    try {
      r.run();
      fail( "expected FORBIDDEN" );
    } catch ( DatasetApiException e ) {
      assertEquals( DatasetApiException.Code.FORBIDDEN, e.getCode() );
    }
  }

  @Test
  public void onlyHoldersOfThePermissionCanCertifyOrWithdraw() {
    create( "Orders" );
    assertFalse( service.canCertify() );
    assertForbidden( () -> service.certify( "orders", "checked" ) );
    assertNull( service.get( "orders" ).getDefinition().getCertification() );

    canCertify = true;
    user = "pat";
    assertTrue( service.canCertify() );
    StoredDataset certified = service.certify( "orders", "  Reconciled with finance  " );
    DatasetDefinition.Certification c = certified.getDefinition().getCertification();
    assertEquals( "pat", c.getBy() );
    assertEquals( "Reconciled with finance", c.getNote() );
    assertNotNull( java.time.Instant.parse( c.getAt() ) );
    assertEquals( "pat", service.get( "orders" ).getDefinition().getCertification().getBy() );

    canCertify = false;
    assertForbidden( () -> service.uncertify( "orders" ) );
    canCertify = true;
    assertNull( service.uncertify( "orders" ).getDefinition().getCertification() );
  }

  @Test
  public void refusesALongNote() {
    create( "Orders" );
    canCertify = true;
    try {
      service.certify( "orders", "x".repeat( DatasetService.MAX_CERTIFICATION_NOTE + 1 ) );
      fail();
    } catch ( DatasetApiException e ) {
      assertEquals( DatasetApiException.Code.VALIDATION_FAILED, e.getCode() );
    }
  }

  @Test
  public void anAuthorCantSetACertificationAndAContentChangeWithdrawsIt() {
    StoredDataset s = create( "Orders" );
    // Sent by a client, it is ignored: only certify sets it.
    DatasetDefinition forged = s.getDefinition();
    forged.setCertification( new DatasetDefinition.Certification( "suzy", "2026-01-01T00:00:00Z", "trust me" ) );
    s = service.update( "orders", forged, s.getVersion() );
    assertNull( s.getDefinition().getCertification() );

    canCertify = true;
    s = service.certify( "orders", null );
    // Renaming or describing keeps it; the client's copy (without it) doesn't remove it.
    DatasetDefinition renamed = new DatasetJson().read( new DatasetJson().write( s.getDefinition() ) );
    renamed.setCertification( null );
    renamed.setName( "Orders (all regions)" );
    s = service.update( "orders", renamed, s.getVersion() );
    assertNotNull( s.getDefinition().getCertification() );

    DatasetDefinition changed = new DatasetJson().read( new DatasetJson().write( s.getDefinition() ) );
    changed.getSource().setSql( "select 2 as A" );
    s = service.update( "orders", changed, s.getVersion() );
    assertNull( s.getDefinition().getCertification() );
  }

  @Test
  public void aFailedLoadIsAWarningUntilTheNextOneSucceeds() {
    create( "Orders" );
    assertFalse( service.failedRefresh( "orders" ).isPresent() );

    sources.history().add( "orders", load( DatasetRefresh.State.SUCCEEDED, null ) );
    assertFalse( service.failedRefresh( "orders" ).isPresent() );

    sources.history().add( "orders", load( DatasetRefresh.State.FAILED, "File not found" ) );
    DatasetRefresh failed = service.failedRefresh( "orders" ).orElseThrow();
    assertEquals( "File not found", failed.getError() );
    assertEquals( DatasetRefresh.Trigger.SCHEDULE, failed.getTrigger() );

    sources.history().add( "orders", load( DatasetRefresh.State.SUCCEEDED, null ) );
    assertFalse( service.failedRefresh( "orders" ).isPresent() );
  }

  @Test
  public void listsCertifiedDatasetsFirst() {
    create( "Alpha" );
    create( "Beta" );
    create( "Gamma" );
    canCertify = true;
    service.certify( "gamma", null );
    service.certify( "beta", null );
    assertEquals( List.of( "beta", "gamma", "alpha" ),
      service.list().stream().map( s -> s.getDefinition().getId() ).collect( Collectors.toList() ) );
  }

  private static DatasetRefresh load( DatasetRefresh.State state, String error ) {
    DatasetRefresh r = new DatasetRefresh();
    r.setTrigger( DatasetRefresh.Trigger.SCHEDULE );
    r.setState( state );
    r.setError( error );
    r.setFinished( java.time.Instant.now().toString() );
    return r;
  }
}
