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
import org.pentaho.platform.dataaccess.dataset.source.DatasetFiles;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;
import org.pentaho.platform.dataaccess.dataset.source.SourceLimits;
import org.pentaho.platform.dataaccess.dataset.source.TestConnections;

import java.io.ByteArrayInputStream;
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
import java.util.stream.Stream;
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
  private DatasetFiles files;

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:svc" + UUID.randomUUID(), "SA", "" );
    Files.write( folder.getRoot().toPath().resolve( "orders.csv" ), "id,amount\n1,2.50\n2,3.75\n".getBytes( StandardCharsets.UTF_8 ) );
    files = new DatasetFiles( () -> folder.getRoot().toPath().resolve( "uploads" ), 1024 * 1024 );
    CsvSourceAdapter csv = new CsvSourceAdapter( name -> files.locate( name ).or( () -> {
      Path p = folder.getRoot().toPath().resolve( name );
      return Files.exists( p ) ? Optional.of( p ) : Optional.empty();
    } ), SourceLimits.standard() );
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
    }, () -> user, sharing, new SourceAdapters( List.of( csv ), stager, files ) );
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

    // A client can't choose where a file is loaded: the connection it names is not kept.
    Source elsewhere = csv( "orders.csv" );
    elsewhere.setConnection( "Production" );
    StoredDataset e = create( "Elsewhere", elsewhere );
    assertEquals( null, e.getDefinition().getSource().getConnection() );
    assertEquals( "Staging", service.stage( "elsewhere", e.getVersion() ).getTable().getConnection() );

    StoredDataset m = create( "Missing", csv( "missing.csv" ) );
    assertEquals( "source.file.path", expect( Code.VALIDATION_FAILED, () -> service.stage( "missing", m.getVersion() ) ).getPath() );
  }

  @Test
  public void neverDropsATableOrReadsAFileADefinitionNamesOnItsOwn() throws Exception {
    try ( java.sql.Statement st = db.createStatement() ) {
      st.execute( "CREATE TABLE IMPORTANT (x INT)" );
    }
    StoredDataset v1 = create( "Orders", csv( "orders.csv" ) );
    StoredDataset v2 = service.stage( "orders", v1.getVersion() ).getDataset();

    // An update naming another table as the staging table, and another connection, keeps the server's values.
    DatasetDefinition tampered = v2.getDefinition();
    tampered.getSource().getFile().setStagingTable( "IMPORTANT" );
    tampered.getSource().setConnection( "Production" );
    StoredDataset v3 = service.update( "orders", tampered, v2.getVersion() );
    assertEquals( v2.getDefinition().getSource().getFile().getStagingTable(), v3.getDefinition().getSource().getFile().getStagingTable() );
    assertEquals( "Staging", v3.getDefinition().getSource().getConnection() );
    service.delete( "orders", v3.getVersion() );
    try ( java.sql.Statement st = db.createStatement() ) {
      st.executeQuery( "SELECT COUNT(*) FROM IMPORTANT" ).close();
    }

    // Another dataset's uploaded file can't be named.
    DatasetDefinition a = new DatasetDefinition();
    a.setName( "Private" );
    StoredDataset pa = service.create( a );
    String secret = service.uploadFile( "private", pa.getVersion(), "s.csv", csvBytes( "x\n1\n" ) )
      .getDefinition().getSource().getFile().getPath();
    Source steal = csv( secret );
    DatasetDefinition b = new DatasetDefinition();
    b.setName( "Thief" );
    b.setSource( steal );
    assertEquals( "source.file.path", expect( Code.VALIDATION_FAILED, () -> service.create( b ) ).getPath() );
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

  private static ByteArrayInputStream csvBytes( String text ) {
    return new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
  }

  private long uploads() throws Exception {
    Path root = folder.getRoot().toPath().resolve( "uploads" );
    if ( !Files.exists( root ) ) {
      return 0;
    }
    try ( Stream<Path> s = Files.walk( root ) ) {
      return s.filter( Files::isRegularFile ).count();
    }
  }

  @Test
  public void uploadsAFileToADraftAndLoadsIt() throws Exception {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Sales" );
    StoredDataset v1 = service.create( d );

    StoredDataset v2 = service.uploadFile( "sales", v1.getVersion(), "C:\\data\\Sales Q1.csv", csvBytes( "region,units\nEMEA,3\nNA,4\n" ) );
    Source source = v2.getDefinition().getSource();
    assertEquals( SourceType.FILE, source.getType() );
    assertTrue( source.getFile().isHeaderRow() );
    assertEquals( "Sales Q1.csv", source.getFile().getOriginalName() );
    assertTrue( DatasetFiles.isStored( source.getFile().getPath() ) );

    DatasetService.Staging staged = service.stage( "sales", v2.getVersion() );
    assertEquals( 2, staged.getTable().getRows() );
  }

  @Test
  public void replacingTheFileDeletesTheOldOneAndDeletingTheDatasetDeletesItsFiles() throws Exception {
    StoredDataset v1 = create( "Orders", csv( "orders.csv" ) );
    StoredDataset v2 = service.uploadFile( "orders", v1.getVersion(), "a.csv", csvBytes( "x\n1\n" ) );
    StoredDataset v3 = service.uploadFile( "orders", v2.getVersion(), "b.csv", csvBytes( "x\n2\n" ) );
    assertEquals( 1, uploads() );
    assertEquals( "b.csv", v3.getDefinition().getSource().getFile().getOriginalName() );

    service.delete( "orders", v3.getVersion() );
    assertEquals( 0, uploads() );
  }

  @Test
  public void storesNothingForAStaleVersionOrAnotherKindOfSource() throws Exception {
    StoredDataset v1 = create( "Orders", csv( "orders.csv" ) );
    service.uploadFile( "orders", v1.getVersion(), "a.csv", csvBytes( "x\n1\n" ) );
    expect( Code.STALE_VERSION, () -> service.uploadFile( "orders", v1.getVersion(), "b.csv", csvBytes( "x\n2\n" ) ) );

    Source sql = new Source();
    sql.setType( SourceType.SQL );
    sql.setConnection( "Staging" );
    sql.setSql( "SELECT 1" );
    StoredDataset q = create( "Query", sql );
    assertEquals( "source.type", expect( Code.INVALID_REQUEST, () -> service.uploadFile( "query", q.getVersion(), "a.csv",
      csvBytes( "x" ) ) ).getPath() );
    assertEquals( 1, uploads() );
  }

  @Test
  public void loadsColumnsAsTheTypesTheDatasetChose() throws Exception {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Typed" );
    StoredDataset v1 = service.create( d );
    StoredDataset v2 = service.uploadFile( "typed", v1.getVersion(), "t.csv",
      csvBytes( "code,qty,day\n007,5,2026-01-31\n008,n/a,2026-02-01\n009,7,2026-02-02\n" ) );
    DatasetDefinition typed = v2.getDefinition();
    DatasetDefinition.Column code = new DatasetDefinition.Column();
    code.setName( "code" );
    code.setType( DatasetDefinition.ColumnType.STRING );
    DatasetDefinition.Column qty = new DatasetDefinition.Column();
    qty.setName( "qty" );
    qty.setType( DatasetDefinition.ColumnType.INTEGER );
    typed.setColumns( new ArrayList<>( List.of( code, qty ) ) );
    StoredDataset v3 = service.update( "typed", typed, v2.getVersion() );

    DatasetService.Staging staged = service.stage( "typed", v3.getVersion() );
    assertEquals( "[code->CODE:STRING(VARCHAR(3)), qty->QTY:INTEGER(BIGINT), day->DAY:DATE(DATE)]",
      staged.getTable().getColumns().toString() );
    assertEquals( List.of( "Column \"qty\" has 1 value(s) that aren't whole numbers (first: line 3, \"n/a\"); they were loaded as empty" ),
      staged.getTable().getWarnings() );
    try ( java.sql.Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT CODE, QTY FROM \"" + staged.getTable().getTable() + "\" ORDER BY CODE" ) ) {
      rs.next();
      assertEquals( "007", rs.getString( 1 ) );
      assertEquals( 5, rs.getLong( 2 ) );
      rs.next();
      rs.getLong( 2 );
      assertTrue( "a misfit loads as empty", rs.wasNull() );
    }
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
