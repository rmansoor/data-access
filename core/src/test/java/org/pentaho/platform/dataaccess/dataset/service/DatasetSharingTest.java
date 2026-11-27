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
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.Access;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.Entry;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.RecipientType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Who sees and changes a dataset once it is shared restrictively. */
public class DatasetSharingTest {

  private String user;
  private List<String> roles;
  private boolean admin;
  private DatasetService service;

  @Before
  public void setUp() {
    user = "suzy";
    roles = new ArrayList<>();
    admin = false;
    InMemoryDatasetSharing sharing = new InMemoryDatasetSharing( () -> user, () -> roles );
    service = new DatasetService( new InMemoryDatasetStore(), new DatasetPermissions() {
      @Override
      public boolean canView() {
        return true;
      }

      @Override
      public boolean canManage() {
        return true;
      }

      @Override
      public boolean canAdminister() {
        return admin;
      }
    }, () -> user, sharing );
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Orders" );
    service.create( d );
  }

  private static DatasetShare only( Entry... entries ) {
    return DatasetShare.restricted( List.of( entries ) );
  }

  private void share( DatasetShare share ) {
    service.setSharing( "orders", share, service.getSharing( "orders" ).tag() );
  }

  @Test
  public void startsUnrestrictedAndVisibleToEveryone() {
    assertFalse( service.getSharing( "orders" ).isRestricted() );
    user = "joe";
    assertEquals( 1, service.list().size() );
  }

  @Test
  public void hidesARestrictedDatasetFromOthersAsIfItDidNotExist() {
    share( only( new Entry( "Sales", RecipientType.ROLE, Access.READ ) ) );
    user = "joe";
    assertTrue( service.list().isEmpty() );
    expect( Code.NOT_FOUND, () -> service.get( "orders" ) );

    roles.add( "Sales" );
    assertEquals( "Orders", service.get( "orders" ).getDefinition().getName() );
  }

  @Test
  public void letsReadersUseButNotChangeIt() {
    share( only( new Entry( "joe", RecipientType.USER, Access.READ ) ) );
    user = "joe";
    String v = service.get( "orders" ).getVersion();
    expect( Code.FORBIDDEN, () -> service.update( "orders", draft( "Mine now" ), v ) );
    expect( Code.FORBIDDEN, () -> service.delete( "orders", v ) );
  }

  @Test
  public void letsWritersChangeItButNotTakeItOver() {
    share( only( new Entry( "joe", RecipientType.USER, Access.WRITE ) ) );
    user = "joe";
    String v = service.get( "orders" ).getVersion();
    StoredDataset changed = service.update( "orders", draft( "Orders, cleaned" ), v );
    assertEquals( "suzy", changed.getDefinition().getOwner() );

    DatasetDefinition takeover = draft( "Orders" );
    takeover.setOwner( "joe" );
    DatasetApiException e = expect( Code.FORBIDDEN, () -> service.update( "orders", takeover, changed.getVersion() ) );
    assertEquals( "owner", e.getPath() );
    expect( Code.FORBIDDEN, () -> share( DatasetShare.unrestricted() ) );
  }

  @Test
  public void alwaysKeepsTheOwnerWithWriteAccess() {
    share( only( new Entry( "suzy", RecipientType.USER, Access.READ ), new Entry( "Sales", RecipientType.ROLE, Access.READ ) ) );
    assertEquals( List.of( "USER:suzy=WRITE", "ROLE:Sales=READ" ),
      service.getSharing( "orders" ).getEntries().stream().map( Object::toString ).collect( Collectors.toList() ) );
  }

  @Test
  public void letsAdministratorsSeeAndReshareEverything() {
    share( only( new Entry( "Sales", RecipientType.ROLE, Access.READ ) ) );
    user = "admin";
    admin = true;
    assertEquals( 1, service.list().size() );
    share( DatasetShare.unrestricted() );
    assertFalse( service.getSharing( "orders" ).isRestricted() );
  }

  @Test
  public void refusesAStaleSharingChange() {
    String before = service.getSharing( "orders" ).tag();
    share( only( new Entry( "Sales", RecipientType.ROLE, Access.READ ) ) );
    assertNotEquals( before, service.getSharing( "orders" ).tag() );
    expect( Code.STALE_VERSION, () -> service.setSharing( "orders", DatasetShare.unrestricted(), before ) );
    expect( Code.VERSION_REQUIRED, () -> service.setSharing( "orders", DatasetShare.unrestricted(), null ) );
  }

  @Test
  public void giveTheSameTagForTheSameSharingInAnyOrder() {
    DatasetShare a = only( new Entry( "Sales", RecipientType.ROLE, Access.READ ), new Entry( "joe", RecipientType.USER, Access.WRITE ) );
    DatasetShare b = only( new Entry( "joe", RecipientType.USER, Access.WRITE ), new Entry( "Sales", RecipientType.ROLE, Access.READ ) );
    assertEquals( a.tag(), b.tag() );
    assertEquals( a, b );
    assertNotEquals( a.tag(), DatasetShare.unrestricted().tag() );
  }

  @Test
  public void reportsEveryProblemWithTheSharingAndItsPath() {
    DatasetShare bad = only( new Entry( " ", RecipientType.USER, Access.READ ), new Entry( "Sales", null, Access.READ ),
      new Entry( "joe", RecipientType.USER, null ), new Entry( "joe", RecipientType.USER, Access.WRITE ) );
    DatasetApiException e = expect( Code.VALIDATION_FAILED, () -> share( bad ) );
    assertEquals( List.of( "entries[0].recipient: is required", "entries[1].type: is required (USER or ROLE)",
        "entries[2].access: is required (READ or WRITE)", "entries[3]: repeats user \"joe\"" ),
      e.getProblems().stream().map( Object::toString ).collect( Collectors.toList() ) );

    DatasetShare mixed = DatasetShare.unrestricted();
    mixed.setEntries( new ArrayList<>( List.of( new Entry( "joe", RecipientType.USER, Access.READ ) ) ) );
    assertEquals( "entries", expect( Code.VALIDATION_FAILED, () -> share( mixed ) ).getPath() );
  }

  private static DatasetDefinition draft( String name ) {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( name );
    return d;
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
