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
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Aggregation;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Column;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Dimension;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Measure;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Model;
import org.pentaho.platform.dataaccess.dataset.model.InMemoryModelPublisher;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.source.CsvSourceAdapter;
import org.pentaho.platform.dataaccess.dataset.source.CsvStager;
import org.pentaho.platform.dataaccess.dataset.source.DatasetFiles;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;
import org.pentaho.platform.dataaccess.dataset.source.SourceLimits;
import org.pentaho.platform.dataaccess.dataset.source.StagingConfig;
import org.pentaho.platform.dataaccess.dataset.source.StagingSettings;
import org.pentaho.platform.dataaccess.dataset.source.TestConnections;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Refreshing a file dataset: append, replace, reload; matching columns; strict loads; republishing; history. */
public class DatasetRefreshTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Connection db;
  private DatasetService service;
  private InMemoryModelPublisher published;
  private SourceAdapters sources;
  private DatasetFiles files;

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:refresh" + UUID.randomUUID(), "SA", "" );
    files = new DatasetFiles( () -> folder.getRoot().toPath().resolve( "uploads" ), 10 * 1024 * 1024 );
    CsvSourceAdapter csv = new CsvSourceAdapter( files::locate, SourceLimits.standard() );
    org.pentaho.platform.dataaccess.dataset.source.ConnectionProvider connections = TestConnections.pooled( db, "Staging" );
    StagingConfig config = new StagingConfig( new StagingSettings.InMemoryStore(), () -> "Staging", () -> null );
    AtomicLong clock = new AtomicLong( 1_800_000_000_000L );
    CsvStager stager = new CsvStager( csv, connections, config::connection, config::schema, 1_000_000, clock::incrementAndGet );
    published = new InMemoryModelPublisher();
    sources = new SourceAdapters( List.of( csv ), stager, files ).withStaging( config, connections ).withPublisher( published );
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
        return false;
      }
    }, () -> "suzy", new InMemoryDatasetSharing( () -> "suzy", List::of ), sources );
  }

  @After
  public void tearDown() throws Exception {
    db.close();
  }

  private static InputStream text( String s ) {
    return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
  }

  /** January's sales, loaded and published: month, region, amount. */
  private StoredDataset januaryPublished() {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Sales" );
    d.setColumns( new ArrayList<>( List.of( column( "month", ColumnType.DATE ), column( "region", ColumnType.STRING ),
      column( "amount", ColumnType.DECIMAL ) ) ) );
    Model m = new Model();
    Dimension region = new Dimension();
    region.setName( "Region" );
    region.getLevels().add( "region" );
    m.getDimensions().add( region );
    Measure amount = new Measure();
    amount.setName( "Amount" );
    amount.setColumn( "amount" );
    amount.setAggregation( Aggregation.SUM );
    m.getMeasures().add( amount );
    d.setModel( m );
    StoredDataset s = service.create( d );
    s = service.uploadFile( "sales", s.getVersion(), "january.csv",
      text( "month,region,amount\n2026-01-01,EMEA,10.50\n2026-01-01,NA,20.25\n" ) );
    s = service.stage( "sales", s.getVersion() ).getDataset();
    return service.publish( "sales", s.getVersion() ).getDataset();
  }

  private static Column column( String name, ColumnType type ) {
    Column c = new Column();
    c.setName( name );
    c.setSourceName( name );
    c.setType( type );
    return c;
  }

  private StagingJobs.Job await( String id ) throws InterruptedException {
    for ( int i = 0; i < 200; i++ ) {
      StagingJobs.Job job = service.loadStatus( id );
      if ( job.getState() != StagingJobs.State.RUNNING ) {
        return job;
      }
      Thread.sleep( 25 );
    }
    throw new AssertionError( "the load didn't finish" );
  }

  private String table() {
    return service.get( "sales" ).getDefinition().getSource().getFile().getStagingTable();
  }

  private List<String> rows() throws Exception {
    List<String> out = new ArrayList<>();
    try ( Statement st = db.createStatement();
          ResultSet rs = st.executeQuery( "SELECT MONTH, REGION, AMOUNT FROM \"" + table() + "\" ORDER BY MONTH, REGION" ) ) {
      while ( rs.next() ) {
        out.add( rs.getDate( 1 ) + " " + rs.getString( 2 ) + " " + rs.getBigDecimal( 3 ).stripTrailingZeros().toPlainString() );
      }
    }
    return out;
  }

  @Test
  public void appendingAMonthKeepsTheOldRowsAndRepublishes() throws Exception {
    StoredDataset s = januaryPublished();
    String before = table();
    service.startRefresh( "sales", s.getVersion(), DatasetRefresh.Mode.APPEND, "february.csv",
      text( "month,region,amount\n2026-02-01,EMEA,30\n2026-02-01,NA,40.75\n" ) );
    StagingJobs.Job job = await( "sales" );
    assertEquals( StagingJobs.State.SUCCEEDED, job.getState() );
    assertEquals( List.of( "2026-01-01 EMEA 10.5", "2026-01-01 NA 20.25", "2026-02-01 EMEA 30", "2026-02-01 NA 40.75" ), rows() );
    assertNotEquals( "a new table was swapped in", before, table() );

    // Reports read the new table: the catalog was republished under the same name.
    assertTrue( published.catalogs.get( "Sales" ), published.catalogs.get( "Sales" ).contains( "<Table name=\"" + table() + "\"/>" ) );
    DatasetDefinition d = service.get( "sales" ).getDefinition();
    assertEquals( "january.csv", d.getSource().getFile().getOriginalName() );
    assertEquals( "february.csv", d.getSource().getFile().getAppended().get( 0 ).getOriginalName() );

    DatasetRefresh last = service.refreshes( "sales" ).get( 0 );
    assertEquals( DatasetRefresh.Mode.APPEND, last.getMode() );
    assertEquals( DatasetRefresh.State.SUCCEEDED, last.getState() );
    assertEquals( Long.valueOf( 4 ), last.getRows() );
    assertEquals( Boolean.TRUE, last.getRepublished() );
    assertEquals( "february.csv", last.getFile() );
    assertEquals( "suzy", last.getUser() );
  }

  @Test
  public void replacingLoadsOnlyTheNewFileAndDiscardsTheOldOnes() throws Exception {
    StoredDataset s = januaryPublished();
    String january = s.getDefinition().getSource().getFile().getPath();
    service.startRefresh( "sales", s.getVersion(), DatasetRefresh.Mode.APPEND, "february.csv",
      text( "month,region,amount\n2026-02-01,EMEA,30\n" ) );
    await( "sales" );
    String february = service.get( "sales" ).getDefinition().getSource().getFile().getAppended().get( 0 ).getPath();
    service.startRefresh( "sales", service.get( "sales" ).getVersion(), DatasetRefresh.Mode.REPLACE, "march.csv",
      text( "month,region,amount\n2026-03-01,APAC,5\n" ) );
    assertEquals( StagingJobs.State.SUCCEEDED, await( "sales" ).getState() );
    assertEquals( List.of( "2026-03-01 APAC 5" ), rows() );
    DatasetDefinition.FileSource f = service.get( "sales" ).getDefinition().getSource().getFile();
    assertEquals( "march.csv", f.getOriginalName() );
    assertTrue( f.getAppended().isEmpty() );
    assertFalse( "replaced files are deleted", files.locate( january ).isPresent() || files.locate( february ).isPresent() );
  }

  @Test
  public void aFileWhoseColumnsDontMatchIsRefusedWithEveryDifference() throws Exception {
    StoredDataset s = januaryPublished();
    try {
      service.startRefresh( "sales", s.getVersion(), DatasetRefresh.Mode.APPEND, "february.csv",
        text( "month,area,amount,note\n2026-02-01,EMEA,lots,x\n" ) );
      fail( "accepted" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
      List<String> problems = new ArrayList<>();
      e.getProblems().forEach( p -> problems.add( p.getMessage() ) );
      assertTrue( problems.toString(), problems.contains( "column \"region\" is missing" ) );
      assertTrue( problems.toString(), problems.contains( "column \"area\" isn't one of the dataset's" ) );
      assertTrue( problems.toString(), problems.contains( "column \"note\" isn't one of the dataset's" ) );
      assertTrue( problems.toString(), problems.contains( "column \"amount\" holds STRING values; the dataset's is DECIMAL" ) );
    }
    assertEquals( "nothing changed", s.getVersion(), service.get( "sales" ).getVersion() );
    assertEquals( 2, rows().size() );
  }

  @Test
  public void aRefreshWithABadValueFailsCleanlyAndIsRecorded() throws Exception {
    StoredDataset s = januaryPublished();
    String before = table();
    String catalog = published.catalogs.get( "Sales" );
    // Good rows first, so the column check passes; the bad value comes after the rows it samples.
    StringBuilder csv = new StringBuilder( "month,region,amount\n" );
    for ( int i = 0; i < 120; i++ ) {
      csv.append( "2026-02-01,EMEA," ).append( i ).append( '\n' );
    }
    csv.append( "2026-02-01,NA,twelve\n" );
    service.startRefresh( "sales", s.getVersion(), DatasetRefresh.Mode.APPEND, "february.csv", text( csv.toString() ) );
    StagingJobs.Job job = await( "sales" );
    assertEquals( StagingJobs.State.FAILED, job.getState() );
    assertTrue( job.getError().getMessage(), job.getError().getMessage().contains( "don't fit their column's type" ) );

    assertEquals( "the previous data stays", before, table() );
    assertEquals( 2, rows().size() );
    assertEquals( catalog, published.catalogs.get( "Sales" ) );
    assertTrue( service.get( "sales" ).getDefinition().getSource().getFile().getAppended().isEmpty() );
    DatasetRefresh last = service.refreshes( "sales" ).get( 0 );
    assertEquals( DatasetRefresh.State.FAILED, last.getState() );
    assertTrue( last.getError(), last.getError().contains( "twelve" ) || last.getError().contains( "don't fit" ) );
    try ( ResultSet rs = db.getMetaData().getTables( null, null, "DS_%", new String[] { "TABLE" } ) ) {
      int n = 0;
      while ( rs.next() ) {
        n++;
      }
      assertEquals( "the failed load's table is gone", 1, n );
    }
  }

  @Test
  public void aScheduledReloadReadsEveryFileAgain() throws Exception {
    StoredDataset s = januaryPublished();
    service.startRefresh( "sales", s.getVersion(), DatasetRefresh.Mode.APPEND, "february.csv",
      text( "month,region,amount\n2026-02-01,EMEA,30\n" ) );
    await( "sales" );
    String before = table();
    DatasetRefresh r = service.reload( "sales", DatasetRefresh.Trigger.SCHEDULE );
    assertEquals( DatasetRefresh.State.SUCCEEDED, r.getState() );
    assertEquals( DatasetRefresh.Trigger.SCHEDULE, r.getTrigger() );
    assertEquals( Long.valueOf( 3 ), r.getRows() );
    assertNotEquals( before, table() );
    assertEquals( 3, rows().size() );
    assertEquals( List.of( DatasetRefresh.Mode.LOAD, DatasetRefresh.Mode.APPEND, DatasetRefresh.Mode.LOAD ),
      service.refreshes( "sales" ).stream().map( DatasetRefresh::getMode ).collect( java.util.stream.Collectors.toList() ) );
  }

  @Test
  public void anAppendedFileMayOrderItsColumnsDifferently() throws Exception {
    StoredDataset s = januaryPublished();
    service.startRefresh( "sales", s.getVersion(), DatasetRefresh.Mode.APPEND, "february.csv",
      text( "amount,month,region\n7.5,2026-02-01,APAC\n" ) );
    assertEquals( StagingJobs.State.SUCCEEDED, await( "sales" ).getState() );
    assertTrue( rows().toString(), rows().contains( "2026-02-01 APAC 7.5" ) );
  }

  @Test
  public void appendingNeedsAFirstLoad() {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Empty" );
    StoredDataset s = service.create( d );
    s = service.uploadFile( "empty", s.getVersion(), "a.csv", text( "x\n1\n" ) );
    try {
      service.startRefresh( "empty", s.getVersion(), DatasetRefresh.Mode.APPEND, "b.csv", text( "x\n2\n" ) );
      fail( "appended" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
      assertEquals( "mode", e.getPath() );
    }
  }

  @Test
  public void aPlainReloadOfAPublishedDatasetRepublishesToo() throws Exception {
    StoredDataset s = januaryPublished();
    s = service.stage( "sales", s.getVersion() ).getDataset();
    assertTrue( "reports follow the new table", published.catalogs.get( "Sales" ).contains( "<Table name=\"" + table() + "\"/>" ) );
    assertEquals( Boolean.TRUE, service.refreshes( "sales" ).get( 0 ).getRepublished() );
  }

  @Test
  public void aLoadedDatasetIsScheduledReplacedAndUnscheduled() throws Exception {
    januaryPublished();
    DatasetSchedule weekdays = new DatasetSchedule();
    weekdays.setCron( "0 0 6 ? * 2-6" );
    weekdays.setTimeZone( "Europe/Berlin" );
    DatasetSchedule set = service.setSchedule( "sales", weekdays );
    assertEquals( "suzy", set.getOwner() );
    assertEquals( "0 0 6 ? * 2-6", service.schedule( "sales" ).get().getCron() );
    DatasetSchedule hourly = new DatasetSchedule();
    hourly.setCron( "0 0 * * * ?" );
    service.setSchedule( "sales", hourly );
    assertEquals( "0 0 * * * ?", service.schedule( "sales" ).get().getCron() );
    service.removeSchedule( "sales" );
    assertFalse( service.schedule( "sales" ).isPresent() );
  }

  @Test
  public void aScheduleNeedsAQuartzCronAndAFirstLoad() throws Exception {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Empty" );
    StoredDataset s = service.create( d );
    service.uploadFile( "empty", s.getVersion(), "a.csv", text( "x\n1\n" ) );
    DatasetSchedule daily = new DatasetSchedule();
    daily.setCron( "0 0 6 * * ?" );
    try {
      service.setSchedule( "empty", daily );
      fail( "scheduled" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
    }
    januaryPublished();
    for ( String bad : new String[] { "", "0 6 * * *", "a b c d e f g h" } ) {
      DatasetSchedule b = new DatasetSchedule();
      b.setCron( bad );
      try {
        service.setSchedule( "sales", b );
        fail( "accepted " + bad );
      } catch ( DatasetApiException e ) {
        assertEquals( "cron", e.getPath() );
      }
    }
    DatasetSchedule zone = new DatasetSchedule();
    zone.setCron( "0 0 6 * * ?" );
    zone.setTimeZone( "Mars/Olympus" );
    try {
      service.setSchedule( "sales", zone );
      fail( "accepted the zone" );
    } catch ( DatasetApiException e ) {
      assertEquals( "timeZone", e.getPath() );
    }
  }

  @Test
  public void deletingADatasetRemovesItsScheduleAndHistory() throws Exception {
    januaryPublished();
    DatasetSchedule daily = new DatasetSchedule();
    daily.setCron( "0 0 6 * * ?" );
    service.setSchedule( "sales", daily );
    service.delete( "sales", service.get( "sales" ).getVersion() );
    assertFalse( sources.schedules().get( "sales" ).isPresent() );
    assertTrue( sources.history().list( "sales" ).isEmpty() );
  }
}
