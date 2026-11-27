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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileFormat;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.Access;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.Entry;
import org.pentaho.platform.dataaccess.dataset.service.DatasetShare.RecipientType;
import org.pentaho.platform.dataaccess.dataset.source.CsvSourceAdapter;
import org.pentaho.platform.dataaccess.dataset.source.CsvStager;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;
import org.pentaho.platform.dataaccess.dataset.source.SourceLimits;
import org.pentaho.platform.dataaccess.dataset.source.TestConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Loading a dataset's file through the service: who may, what changes in the definition, and cleaning up. */
public class DatasetStagingTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Connection db;
  private String user = "suzy";
  private InMemoryDatasetSharing sharing;
  private DatasetService service;

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:svc" + UUID.randomUUID(), "SA", "" );
    Files.write( folder.getRoot().toPath().resolve( "orders.csv" ), "id,amount\n1,2.50\n2,3.75\n".getBytes( StandardCharsets.UTF_8 ) );
    CsvSourceAdapter csv = new CsvSourceAdapter( name -> {
      Path p = folder.getRoot().toPath().resolve( name );
      return Files.exists( p ) ? Optional.of( p ) : Optional.empty();
    }, SourceLimits.standard() );
    AtomicLong clock = new AtomicLong( 1_800_000_000_000L );
    CsvStager stager = new CsvStager( csv, TestConnections.pooled( db, "Staging" ), () -> "Staging", () -> null, 1000,
      clock::incrementAndGet );
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
    }, () -> user, sharing, new SourceAdapters( List.of( csv ), stager ) );
  }

  @After
  public void tearDown() throws Exception {
    db.close();
  }

  private StoredDataset create( String name, Source source ) {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( name );
    d.setSource( source );
    return service.create( d );
  }

  private static Source csv( String path ) {
    FileSource f = new FileSource();
    f.setFormat( FileFormat.CSV );
    f.setPath( path );
    f.setHeaderRow( true );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setFile( f );
    return s;
  }

  private List<String> tables() throws Exception {
    List<String> names = new ArrayList<>();
    try ( ResultSet rs = db.getMetaData().getTables( null, null, "DS_%", new String[] { "TABLE" } ) ) {
      while ( rs.next() ) {
        names.add( rs.getString( "TABLE_NAME" ) );
      }
    }
    return names;
  }

  @Test
  public void pointsTheDatasetAtItsNewTableAndDropsTheOldOne() throws Exception {
    StoredDataset v1 = create( "Orders", csv( "orders.csv" ) );
    DatasetService.Staging first = service.stage( "orders", v1.getVersion() );
    assertEquals( 2, first.getTable().getRows() );
    Source stored = service.get( "orders" ).getDefinition().getSource();
    assertEquals( "Staging", stored.getConnection() );
    assertEquals( first.getTable().getTable(), stored.getFile().getStagingTable() );
    assertNotEquals( v1.getVersion(), first.getDataset().getVersion() );
    assertEquals( List.of( first.getTable().getTable() ), tables() );

    DatasetService.Staging second = service.stage( "orders", first.getDataset().getVersion() );
    assertEquals( "the first table is gone", List.of( second.getTable().getTable() ), tables() );

    service.delete( "orders", second.getDataset().getVersion() );
    assertTrue( "deleting the dataset drops its table", tables().isEmpty() );
  }

  @Test
  public void needsTheCurrentVersionAndLoadsNothingWithoutIt() throws Exception {
    StoredDataset v1 = create( "Orders", csv( "orders.csv" ) );
    service.stage( "orders", v1.getVersion() );
    expect( Code.STALE_VERSION, () -> service.stage( "orders", v1.getVersion() ) );
    expect( Code.VERSION_REQUIRED, () -> service.stage( "orders", null ) );
    assertEquals( 1, tables().size() );
  }

  @Test
  public void loadsOnlyFilesAndOnlyIntoTheStagingConnection() throws Exception {
    Source sql = new Source();
    sql.setType( SourceType.SQL );
    sql.setConnection( "Staging" );
    sql.setSql( "SELECT 1" );
    StoredDataset q = create( "Query", sql );
    assertEquals( "source.type", expect( Code.INVALID_REQUEST, () -> service.stage( "query", q.getVersion() ) ).getPath() );

    Source elsewhere = csv( "orders.csv" );
    elsewhere.setConnection( "Production" );
    StoredDataset e = create( "Elsewhere", elsewhere );
    assertEquals( "source.connection", expect( Code.VALIDATION_FAILED, () -> service.stage( "elsewhere", e.getVersion() ) ).getPath() );

    StoredDataset m = create( "Missing", csv( "missing.csv" ) );
    assertEquals( "source.file.path", expect( Code.VALIDATION_FAILED, () -> service.stage( "missing", m.getVersion() ) ).getPath() );
    assertTrue( tables().isEmpty() );
  }

  @Test
  public void letsOnlyThoseWhoMayChangeTheDatasetLoadIt() throws Exception {
    StoredDataset v1 = create( "Orders", csv( "orders.csv" ) );
    service.setSharing( "orders", DatasetShare.restricted( List.of( new Entry( "joe", RecipientType.USER, Access.READ ) ) ),
      service.getSharing( "orders" ).tag() );
    user = "joe";
    expect( Code.FORBIDDEN, () -> service.stage( "orders", v1.getVersion() ) );
    user = "ann";
    expect( Code.NOT_FOUND, () -> service.stage( "orders", v1.getVersion() ) );
    assertTrue( tables().isEmpty() );
  }

  @Test
  public void saysWhenTheServerCannotLoadFiles() {
    DatasetService plain = new DatasetService( new InMemoryDatasetStore(), new DatasetPermissions() {
      @Override
      public boolean canView() {
        return true;
      }

      @Override
      public boolean canManage() {
        return true;
      }
    }, () -> user );
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Orders" );
    d.setSource( csv( "orders.csv" ) );
    StoredDataset v1 = plain.create( d );
    expect( Code.NOT_IMPLEMENTED, () -> plain.stage( "orders", v1.getVersion() ) );
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
