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
import org.pentaho.platform.api.repository2.unified.IAclNodeHelper;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryFileAcl;
import org.pentaho.platform.api.repository2.unified.RepositoryFilePermission;
import org.pentaho.platform.api.repository2.unified.RepositoryFileSid;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.Access;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.Entry;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.RecipientType;
import org.pentaho.platform.engine.security.SecurityHelper;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RepositoryDatasetSharingTest {

  private MockedStatic<SecurityHelper> securityHelper;
  private IAclNodeHelper helper;
  private RepositoryDatasetSharing sharing;
  private final RepositoryFile folder = new RepositoryFile.Builder( "orders-folder", "orders" ).folder( true ).build();

  @Before
  @SuppressWarnings( "unchecked" )
  public void setUp() throws Exception {
    ISecurityHelper security = mock( ISecurityHelper.class );
    when( security.runAsSystem( any( Callable.class ) ) ).thenAnswer( call -> ( (Callable<?>) call.getArgument( 0 ) ).call() );
    securityHelper = mockStatic( SecurityHelper.class );
    securityHelper.when( SecurityHelper::getInstance ).thenReturn( security );
    IUnifiedRepository repository = mock( IUnifiedRepository.class );
    when( repository.getFile( "/etc/datasets/orders" ) ).thenReturn( folder );
    helper = mock( IAclNodeHelper.class );
    sharing = new RepositoryDatasetSharing( () -> repository, r -> helper, () -> "Administrator" );
  }

  @After
  public void tearDown() {
    securityHelper.close();
  }

  @Test
  public void storesRestrictedSharingAsAnAclNodeOnTheDatasetFolder() {
    sharing.set( "orders", DatasetShare.restricted( List.of( new Entry( "Sales", RecipientType.ROLE, Access.READ ),
      new Entry( "suzy", RecipientType.USER, Access.WRITE ) ) ) );

    ArgumentCaptor<RepositoryFileAcl> acl = ArgumentCaptor.forClass( RepositoryFileAcl.class );
    verify( helper ).setAclFor( eq( folder ), acl.capture() );
    assertFalse( "explicit entries only", acl.getValue().isEntriesInheriting() );
    assertEquals( EnumSet.of( RepositoryFilePermission.READ ), acl.getValue().getAces().get( 0 ).getPermissions() );
    assertEquals( RepositoryFileSid.Type.ROLE, acl.getValue().getAces().get( 0 ).getSid().getType() );
    assertEquals( EnumSet.of( RepositoryFilePermission.READ, RepositoryFilePermission.WRITE, RepositoryFilePermission.DELETE ),
      acl.getValue().getAces().get( 1 ).getPermissions() );
  }

  @Test
  public void removesTheAclNodeWhenUnrestricted() {
    sharing.set( "orders", DatasetShare.unrestricted() );
    verify( helper ).setAclFor( eq( folder ), (RepositoryFileAcl) eq( null ) );
  }

  @Test
  public void readsSharingBackWithoutTheAdministratorRoleTheHelperAdds() {
    when( helper.getAclFor( folder ) ).thenReturn( new RepositoryFileAcl.Builder( "admin" )
      .ace( "Sales", RepositoryFileSid.Type.ROLE, RepositoryFilePermission.READ )
      .ace( "joe", RepositoryFileSid.Type.USER, RepositoryFilePermission.ALL )
      .ace( "Administrator", RepositoryFileSid.Type.ROLE, RepositoryFilePermission.ALL )
      .build() );
    DatasetShare share = sharing.get( "orders" );
    assertTrue( share.isRestricted() );
    assertEquals( List.of( "USER:joe=WRITE", "ROLE:Sales=READ" ),
      share.getEntries().stream().map( Object::toString ).collect( Collectors.toList() ) );
  }

  @Test
  public void treatsNoAclNodeAsUnrestricted() {
    assertFalse( sharing.get( "orders" ).isRestricted() );
  }

  @Test
  public void asksTheRepositoryWhetherTheCallerPasses() {
    when( helper.canAccess( folder, EnumSet.of( RepositoryFilePermission.READ ) ) ).thenReturn( true );
    assertTrue( sharing.canRead( "orders" ) );
    assertFalse( sharing.canWrite( "orders" ) );
    assertFalse( "a missing dataset passes nobody", sharing.canRead( "missing" ) );
  }
}
