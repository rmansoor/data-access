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
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.LegacyIds;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatasetPublishTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Connection db;
  private DatasetService service;
  private InMemoryModelPublisher published;

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:pub" + UUID.randomUUID(), "SA", "" );
    DatasetFiles files = new DatasetFiles( () -> folder.getRoot().toPath().resolve( "uploads" ), 1024 * 1024 );
    CsvSourceAdapter csv = new CsvSourceAdapter( files::locate, SourceLimits.standard() );
    org.pentaho.platform.dataaccess.dataset.source.ConnectionProvider connections = TestConnections.pooled( db, "Staging" );
    StagingConfig config = new StagingConfig( new StagingSettings.InMemoryStore(), () -> "Staging", () -> null );
    AtomicLong clock = new AtomicLong( 1_800_000_000_000L );
    CsvStager stager = new CsvStager( csv, connections, config::connection, config::schema, 1000, clock::incrementAndGet );
    published = new InMemoryModelPublisher();
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
    }, () -> "suzy", new InMemoryDatasetSharing( () -> "suzy", List::of ),
      new SourceAdapters( List.of( csv ), stager, files ).withStaging( config, connections ).withPublisher( published ) );
  }

  @After
  public void tearDown() throws Exception {
    db.close();
  }

  /** "Orders" from a CSV of region and amount, uploaded and loaded, with a model of one dimension and one measure. */
  private StoredDataset loadedOrders() {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Orders" );
    d.setColumns( new ArrayList<>( List.of( column( "region", ColumnType.STRING ), column( "amount", ColumnType.DECIMAL ) ) ) );
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
    s = service.uploadFile( s.getDefinition().getId(), s.getVersion(), "orders.csv",
      new ByteArrayInputStream( "region,amount\nEMEA,2.50\nNA,3.75\n".getBytes( StandardCharsets.UTF_8 ) ) );
    return service.stage( s.getDefinition().getId(), s.getVersion() ).getDataset();
  }

  private static Column column( String name, ColumnType type ) {
    Column c = new Column();
    c.setName( name );
    c.setSourceName( name );
    c.setType( type );
    return c;
  }

  @Test
  public void loadingRecordsTheStagedColumns() {
    DatasetDefinition d = loadedOrders().getDefinition();
    List<String> staged = new ArrayList<>();
    d.getSource().getFile().getStagedColumns().forEach( c -> staged.add( c.getSourceName() + "=" + c.getName() ) );
    assertEquals( List.of( "region=REGION", "amount=AMOUNT" ), staged );
  }

  @Test
  public void publishesTheDomainAndCatalogAndKeepsTheIds() {
    StoredDataset s = loadedOrders();
    DatasetService.Published p = service.publish( "orders", s.getVersion() );
    assertEquals( "orders.xmi", p.getIds().getDomainId() );
    assertEquals( "Orders", p.getIds().getCatalogName() );
    assertTrue( p.isAnalysis() );
    assertTrue( published.domains.containsKey( "orders.xmi" ) );
    assertTrue( published.catalogs.get( "Orders" ).contains( "<Table name=\"" + s.getDefinition().getSource().getFile().getStagingTable() + "\"/>" ) );
    assertEquals( "Staging", published.catalogConnections.get( "Orders" ) );

    // The ids are now part of the definition, so the version moved.
    assertNotEquals( s.getVersion(), p.getDataset().getVersion() );
    LegacyIds kept = service.get( "orders" ).getDefinition().getLegacy();
    assertEquals( "orders.xmi", kept.getDomainId() );
    assertEquals( "Orders", kept.getCatalogName() );
  }

  @Test
  public void republishingReplacesTheSameFilesWithTheSameBytes() {
    StoredDataset s = loadedOrders();
    DatasetService.Published first = service.publish( "orders", s.getVersion() );
    String xmi = published.domains.get( "orders.xmi" );
    String schema = published.catalogs.get( "Orders" );
    DatasetService.Published again = service.publish( "orders", first.getDataset().getVersion() );
    assertEquals( "unchanged when the ids were already kept", first.getDataset().getVersion(), again.getDataset().getVersion() );
    assertEquals( xmi, published.domains.get( "orders.xmi" ) );
    assertEquals( schema, published.catalogs.get( "Orders" ) );
    assertEquals( 1, published.domains.size() );
  }

  @Test
  public void renamingDoesntMoveWhatReportsUse() {
    StoredDataset s = loadedOrders();
    s = service.publish( "orders", s.getVersion() ).getDataset();
    DatasetDefinition d = s.getDefinition();
    d.setName( "Orders by region" );
    s = service.update( "orders", d, s.getVersion() );
    service.publish( "orders", s.getVersion() );
    assertEquals( List.of( "Orders" ), new ArrayList<>( published.catalogs.keySet() ) );
  }

  @Test
  public void aFirstPublishDoesntTakeAnotherSourcesNames() {
    published.domains.put( "orders.xmi", "<another data source/>" );
    published.catalogs.put( "Orders", "<another schema/>" );
    StoredDataset s = loadedOrders();
    DatasetService.Published p = service.publish( "orders", s.getVersion() );
    assertEquals( "orders-2.xmi", p.getIds().getDomainId() );
    assertEquals( "Orders (2)", p.getIds().getCatalogName() );
    assertEquals( "<another schema/>", published.catalogs.get( "Orders" ) );
  }

  @Test
  public void clientsCantSetThePublishedIds() {
    StoredDataset s = loadedOrders();
    DatasetDefinition d = s.getDefinition();
    LegacyIds theirs = new LegacyIds();
    theirs.setDomainId( "someone-elses.xmi" );
    theirs.setModelId( "MODEL_1" );
    theirs.setCatalogName( "SteelWheels" );
    d.setLegacy( theirs );
    s = service.update( "orders", d, s.getVersion() );
    assertNull( s.getDefinition().getLegacy() );
    assertEquals( "orders.xmi", service.publish( "orders", s.getVersion() ).getIds().getDomainId() );
    assertFalse( published.catalogs.containsKey( "SteelWheels" ) );
  }

  @Test
  public void aFileMustBeLoadedBeforePublishing() {
    DatasetDefinition d = new DatasetDefinition();
    d.setName( "Draft" );
    d.setColumns( new ArrayList<>( List.of( column( "region", ColumnType.STRING ) ) ) );
    StoredDataset s = service.create( d );
    s = service.uploadFile( "draft", s.getVersion(), "draft.csv",
      new ByteArrayInputStream( "region\nEMEA\n".getBytes( StandardCharsets.UTF_8 ) ) );
    try {
      service.publish( "draft", s.getVersion() );
      fail( "published" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
    }
    assertTrue( published.domains.isEmpty() );
  }

  @Test
  public void aStaleVersionIsRefused() {
    loadedOrders();
    try {
      service.publish( "orders", "1.0" );
      fail( "published" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.STALE_VERSION, e.getCode() );
    }
  }

  @Test
  public void deletingUnpublishes() {
    StoredDataset s = loadedOrders();
    s = service.publish( "orders", s.getVersion() ).getDataset();
    service.delete( "orders", s.getVersion() );
    assertTrue( published.domains.isEmpty() );
    assertTrue( published.catalogs.isEmpty() );
  }

  @Test
  public void suggestsAModelFromTheKeptColumns() {
    loadedOrders();
    Model m = service.suggestModel( "orders" );
    List<String> measures = new ArrayList<>();
    m.getMeasures().forEach( x -> measures.add( x.getAggregation() + "(" + x.getColumn() + ")" ) );
    assertEquals( List.of( "SUM(amount)" ), measures );
    assertEquals( 2, m.getDimensions().size() );
  }
}
