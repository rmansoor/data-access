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
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.source.Sample;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapter;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;
import org.pentaho.platform.dataaccess.dataset.source.SourceColumn;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Sampling through the service: who may, which limits, and what must be defined first. */
public class DatasetSamplingTest {

  private final List<Object[]> calls = new ArrayList<>();
  private String user = "suzy";
  private InMemoryDatasetSharing sharing;
  private DatasetService service;

  @Before
  public void setUp() {
    SourceAdapter fake = new SourceAdapter() {
      @Override
      public SourceType type() {
        return SourceType.SQL;
      }

      @Override
      public List<SourceColumn> schema( Source source ) {
        return List.of( new SourceColumn( "n", ColumnType.INTEGER, "INTEGER", false, null ) );
      }

      @Override
      public Sample sample( Source source, int limit, String table ) {
        calls.add( new Object[] { source.getSql(), limit, table } );
        return new Sample( schema( source ), List.of( List.of( 1L ) ), false );
      }
    };
    sharing = new InMemoryDatasetSharing( () -> user, List::of );
    service = new DatasetService( new InMemoryDatasetStore(), new DatasetPermissions() {
      @Override
      public boolean canView() {
        return true;
      }

      @Override
      public boolean canManage() {
        return true;
      }
    }, () -> user, sharing, new SourceAdapters( List.of( fake ) ) );
  }

  private void create( String name, Source source ) {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( name );
    d.setSource( source );
    service.create( d );
  }

  private static Source sql( String query ) {
    Source s = new Source();
    s.setType( SourceType.SQL );
    s.setConnection( "Sample" );
    s.setSql( query );
    return s;
  }

  @Test
  public void samplesThroughTheAdapterForTheSourceType() {
    create( "Orders", sql( "SELECT 1" ) );
    assertEquals( 1, service.sample( "orders", null, null ).getRows().size() );
    assertEquals( "SELECT 1", calls.get( 0 )[ 0 ] );
    assertEquals( "the default", 100, calls.get( 0 )[ 1 ] );
    assertNull( calls.get( 0 )[ 2 ] );
    service.sample( "orders", 1000, "x" );
    assertEquals( 1000, calls.get( 1 )[ 1 ] );
    assertEquals( "x", calls.get( 1 )[ 2 ] );
  }

  @Test
  public void keepsTheLimitWithinTheServersCap() {
    create( "Orders", sql( "SELECT 1" ) );
    assertEquals( "limit", expect( Code.INVALID_REQUEST, () -> service.sample( "orders", 0, null ) ).getPath() );
    expect( Code.INVALID_REQUEST, () -> service.sample( "orders", 1001, null ) );
  }

  @Test
  public void saysWhatTheSourceStillNeeds() {
    create( "Empty", null );
    assertEquals( "source", expect( Code.VALIDATION_FAILED, () -> service.sample( "empty", null, null ) ).getPath() );
    Source noSql = sql( null );
    create( "No SQL", noSql );
    assertEquals( "source.sql", expect( Code.VALIDATION_FAILED, () -> service.sample( "no-sql", null, null ) ).getPath() );
  }

  @Test
  public void answersNotImplementedForSourcesThisServerCantRead() {
    Source tables = new Source();
    tables.setType( SourceType.TABLES );
    tables.setConnection( "Sample" );
    tables.setTables( new ArrayList<>( List.of( "orders" ) ) );
    create( "Tables", tables );
    expect( Code.NOT_IMPLEMENTED, () -> service.sample( "tables", null, null ) );
  }

  @Test
  public void hidesDatasetsSharedWithOthers() {
    create( "Orders", sql( "SELECT 1" ) );
    service.setSharing( "orders", DatasetShare.restricted( List.of() ), service.getSharing( "orders" ).tag() );
    user = "joe";
    expect( Code.NOT_FOUND, () -> service.sample( "orders", null, null ) );
    assertEquals( 0, calls.size() );
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
