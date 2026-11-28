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

package org.pentaho.platform.dataaccess.dataset.model;

import org.junit.Test;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.model.SqlDataSource;
import org.pentaho.metadata.util.XmiParser;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Aggregation;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Column;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Dimension;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileFormat;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.LegacyIds;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Measure;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Model;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.StagedColumn;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Definition in, XMI and Mondrian schema out, compared with the checked-in files under
 * {@code dataset/model/golden}. After a deliberate change to the output, regenerate them with
 * {@code -Ddatasets.golden.update=<path to src/test/resources>} and review the diff.
 */
public class DatasetModelGeneratorTest {

  private static final String GOLDEN = "/org/pentaho/platform/dataaccess/dataset/model/golden/";
  private final DatasetModelGenerator generator = new DatasetModelGenerator();

  // ------------------------------------------------------------------------------------------------
  // Golden files

  @Test
  public void fileDatasetWithAModel() throws Exception {
    DatasetDefinition d = sales();
    DatasetModelGenerator.Generated g = generate( d );
    golden( "file-sales.xmi", g.getXmi() );
    golden( "file-sales.mondrian.xml", g.getMondrianSchema() );
  }

  @Test
  public void fileDatasetWithoutAModelGetsTheSuggestedOne() throws Exception {
    DatasetDefinition d = sales();
    d.setModel( null );
    DatasetModelGenerator.Generated g = generate( d );
    golden( "file-sales-auto.mondrian.xml", g.getMondrianSchema() );
  }

  @Test
  public void queryDatasetReadsTheQueryAsATable() throws Exception {
    DatasetDefinition d = sales();
    Source s = new Source();
    s.setType( SourceType.SQL );
    s.setConnection( "SampleData" );
    s.setSql( "SELECT TERRITORY AS region, ORDERDATE AS month, QUANTITYORDERED AS units, TOTALPRICE AS revenue FROM ORDERFACT" );
    d.setSource( s );
    DatasetModelGenerator.Generated g = generator.generate( d, PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) ),
      PublishedIds.of( d ) );
    golden( "sql-sales.mondrian.xml", g.getMondrianSchema() );
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "<View alias=\"FACT\">" ) );
  }

  // ------------------------------------------------------------------------------------------------
  // Stable output and ids

  @Test
  public void generatingAgainGivesTheSameBytes() {
    DatasetModelGenerator.Generated one = generate( sales() );
    DatasetModelGenerator.Generated two = generate( sales() );
    assertEquals( one.getXmi(), two.getXmi() );
    assertEquals( one.getMondrianSchema(), two.getMondrianSchema() );
    assertTrue( "no export time", !one.getXmi().contains( "timestamp=" ) );
  }

  @Test
  public void theIdsComeFromTheDefinitionAndSurviveARename() throws Exception {
    DatasetDefinition d = sales();
    PublishedIds first = PublishedIds.of( d );
    assertEquals( "regional-sales.xmi", first.getDomainId() );
    assertEquals( PublishedIds.MODEL_ID, first.getModelId() );
    assertEquals( "Regional sales", first.getCatalogName() );

    first.keepIn( d );
    d.setName( "Sales by region" );
    PublishedIds again = PublishedIds.of( d );
    assertEquals( "regional-sales.xmi", again.getDomainId() );
    assertEquals( "Regional sales", again.getCatalogName() );

    DatasetModelGenerator.Generated g = generate( d );
    assertEquals( "regional-sales.xmi", g.getDomain().getId() );
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "<Cube name=\"Regional sales\">" ) );
    // The XMI doesn't carry the domain id (the repository stores it with the file); the model id it does.
    assertNotNull( new XmiParser().parseXmi( stream( g.getXmi() ) ).findLogicalModel( PublishedIds.MODEL_ID ) );
  }

  @Test
  public void keepsTheWizardsIdsOfAnUpgradedSource() {
    DatasetDefinition d = sales();
    LegacyIds legacy = new LegacyIds();
    legacy.setDomainId( "Orders by region.xmi" );
    legacy.setModelId( "BV_MODEL_7" );
    legacy.setCatalogName( "Orders by region" );
    d.setLegacy( legacy );
    DatasetModelGenerator.Generated g = generate( d );
    assertEquals( "Orders by region.xmi", g.getDomain().getId() );
    assertNotNull( g.getDomain().findLogicalModel( "BV_MODEL_7" ) );
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "<Cube name=\"Orders by region\">" ) );
  }

  // ------------------------------------------------------------------------------------------------
  // What the report tools see

  @Test
  public void theDomainHasAReportingAndAnAnalysisModel() throws Exception {
    Domain domain = new XmiParser().parseXmi( stream( generate( sales() ).getXmi() ) );
    assertEquals( 2, domain.getLogicalModels().size() );
    LogicalModel analysis = null;
    for ( LogicalModel m : domain.getLogicalModels() ) {
      assertEquals( "TRUE", m.getProperty( "AGILE_BI_GENERATED_SCHEMA" ) );
      if ( m.getProperty( "MondrianCatalogRef" ) != null ) {
        analysis = m;
      }
    }
    assertNotNull( "an analysis model pointing at the catalog", analysis );
    assertEquals( "Regional sales", analysis.getProperty( "MondrianCatalogRef" ) );
    // Interactive Reports lists the reporting model's categories: one with every kept column.
    LogicalModel reporting = domain.findLogicalModel( PublishedIds.MODEL_ID );
    assertEquals( 1, reporting.getCategories().size() );
    assertEquals( 5, reporting.getCategories().get( 0 ).getLogicalColumns().size() );
  }

  @Test
  public void hiddenColumnsAndLabelsAreRespected() {
    DatasetDefinition d = sales();
    d.getColumns().get( 3 ).setHidden( true ); // note
    d.getColumns().get( 0 ).setLabel( "Sales region" );
    DatasetModelGenerator.Generated g = generate( d );
    assertTrue( g.getXmi(), g.getXmi().contains( "Sales region" ) );
    assertTrue( "hidden column left out", !g.getXmi().contains( "\"NOTE\"" ) );
  }

  @Test
  public void aTimeDimensionGetsTimeLevels() {
    DatasetDefinition d = sales();
    d.getModel().getDimensions().get( 1 ).setTime( true ); // Month, on the date column "month"
    DatasetModelGenerator.Generated g = generate( d );
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "type=\"TimeDimension\"" ) );
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "levelType=\"TimeMonths\"" ) );
    assertTrue( g.getWarnings().toString(), g.getWarnings().isEmpty() );
  }

  @Test
  public void aTimeDimensionWhoseLevelsCantBeToldIsPublishedPlainWithAWarning() {
    DatasetDefinition d = sales();
    d.getModel().getDimensions().get( 0 ).setTime( true ); // Region: not a time column
    DatasetModelGenerator.Generated g = generate( d );
    assertTrue( g.getMondrianSchema(), !g.getMondrianSchema().contains( "TimeDimension" ) );
    assertEquals( 1, g.getWarnings().size() );
    assertTrue( g.getWarnings().get( 0 ), g.getWarnings().get( 0 ).contains( "\"Region\" was published as a plain dimension" ) );
  }

  @Test
  public void withoutMeasuresThereIsNothingToAnalyse() {
    DatasetDefinition d = sales();
    d.getModel().getMeasures().clear();
    DatasetModelGenerator.Generated g = generate( d );
    assertNull( g.getMondrianSchema() );
    assertEquals( 1, g.getDomain().getLogicalModels().size() );
  }

  @Test
  public void aModelNamingAColumnTheDatasetDoesntKeepIsRefused() {
    DatasetDefinition d = sales();
    d.getModel().getMeasures().get( 0 ).setColumn( "profit" );
    try {
      generate( d );
      fail( "accepted" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
      assertEquals( "model.measures[0].column", e.getPath() );
    }
  }

  @Test
  public void suggestsAModelLikeTheWizard() {
    Model m = generator.suggest( sales(), fileSource( sales() ) );
    List<String> dims = new ArrayList<>();
    m.getDimensions().forEach( x -> dims.add( x.getName() + "=" + x.getLevels() ) );
    List<String> measures = new ArrayList<>();
    m.getMeasures().forEach( x -> measures.add( x.getName() + "=" + x.getAggregation() + "(" + x.getColumn() + ")" ) );
    assertTrue( dims.toString(), dims.contains( "region=[region]" ) );
    assertTrue( measures.toString(), measures.contains( "units=SUM(units)" ) );
    assertTrue( measures.toString(), measures.contains( "revenue=SUM(revenue)" ) );
  }

  // ------------------------------------------------------------------------------------------------
  // What can't be published yet

  @Test
  public void aFileMustBeLoadedFirst() {
    DatasetDefinition d = sales();
    d.getSource().getFile().setStagingTable( null );
    expectProblem( "source.file.stagingTable", () -> fileSource( d ) );
  }

  @Test
  public void aFileLoadedBeforePublishingExistedMustBeLoadedAgain() {
    DatasetDefinition d = sales();
    d.getSource().getFile().setStagedColumns( null );
    expectProblem( "source.file.stagedColumns", () -> fileSource( d ) );
  }

  @Test
  public void severalTablesNeedRelationships() {
    DatasetDefinition d = star();
    d.getRelationships().clear();
    expectProblem( "relationships", () -> PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) ) );
  }

  @Test
  public void tablesMustFormAStar() {
    DatasetDefinition d = star();
    // Products joined to customers, not to the fact table: a snowflake.
    d.getRelationships().get( 1 ).setFromTable( "PUBLIC.CUSTOMER_W_TER" );
    expectProblem( "relationships[1]", () -> PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) ) );
  }

  @Test
  public void aSingleTableIsReadWithItsSchema() {
    DatasetDefinition d = sales();
    Source s = new Source();
    s.setType( SourceType.TABLES );
    s.setConnection( "SampleData" );
    s.setTables( new ArrayList<>( List.of( "PUBLIC.ORDERFACT" ) ) );
    d.setSource( s );
    PhysicalSource p = PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) );
    assertEquals( 1, p.getTables().size() );
    assertEquals( "PUBLIC", p.getTables().get( 0 ).getSchema() );
    assertEquals( "ORDERFACT", p.getTables().get( 0 ).getName() );
    assertEquals( "region", p.getColumns().get( "region" ) );
  }

  @Test
  public void aStarHasTheFactTableFirstAndKeepsJoinKeysOutOfSight() {
    PhysicalSource p = PhysicalSource.of( star(), "SampleData", dataSource( "SampleData" ) );
    assertEquals( "ORDERFACT", p.getTables().get( 0 ).getName() );
    assertTrue( p.getTables().get( 0 ).isFact() );
    assertEquals( Set.of( "CUSTOMERNUMBER", "PRODUCTCODE" ), p.getTables().get( 0 ).getKeys() );
    assertEquals( Set.of( "CUSTOMERNUMBER" ), p.getTables().get( 1 ).getKeys() );
    assertTrue( p.getJoins().get( 1 ).isOuter() );
  }

  @Test
  public void aStarOfTables() throws Exception {
    DatasetDefinition d = star();
    DatasetModelGenerator.Generated g = generator.generate( d, PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) ),
      PublishedIds.of( d ) );
    golden( "star-orders.mondrian.xml", g.getMondrianSchema() );
    golden( "star-orders.xmi", g.getXmi() );
    // Dimensions on other tables join the fact table by its key.
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "foreignKey=\"CUSTOMERNUMBER\"" ) );
    assertTrue( g.getMondrianSchema(), g.getMondrianSchema().contains( "foreignKey=\"PRODUCTCODE\"" ) );

    Domain domain = new XmiParser().parseXmi( stream( g.getXmi() ) );
    LogicalModel reporting = domain.findLogicalModel( PublishedIds.MODEL_ID );
    assertEquals( 2, reporting.getLogicalRelationships().size() );
    List<String> offered = new ArrayList<>();
    reporting.getCategories().forEach( c -> c.getLogicalColumns().forEach( lc -> offered.add( lc.getPhysicalColumn().getId() ) ) );
    assertEquals( List.of( "QUANTITYORDERED", "TOTALPRICE", "STATUS", "CUSTOMERNAME", "COUNTRY", "PRODUCTLINE" ), sorted( offered,
      List.of( "QUANTITYORDERED", "TOTALPRICE", "STATUS", "CUSTOMERNAME", "COUNTRY", "PRODUCTLINE" ) ) );
  }

  @Test
  public void aStarWithoutAModelGetsMeasuresFromTheFactTable() {
    DatasetDefinition d = star();
    d.setModel( null );
    Model m = generator.suggest( d, PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) ) );
    List<String> measures = new ArrayList<>();
    m.getMeasures().forEach( x -> measures.add( x.getColumn() ) );
    List<String> levels = new ArrayList<>();
    m.getDimensions().forEach( x -> levels.addAll( x.getLevels() ) );
    assertEquals( List.of( "price", "quantity" ), measures ); // the modeler sorts fields by name
    assertTrue( levels.toString(), levels.containsAll( List.of( "customer", "country", "productLine" ) ) );
    assertTrue( "no join keys", !levels.contains( null ) );
    DatasetModelGenerator.Generated g = generator.generate( d, PhysicalSource.of( d, "SampleData", dataSource( "SampleData" ) ),
      PublishedIds.of( d ) );
    assertTrue( g.getMondrianSchema(), !g.getMondrianSchema().contains( "name=\"CUSTOMERNUMBER\"" ) );
  }

  /** Every expected name is offered, nothing else (order aside). */
  private static List<String> sorted( List<String> actual, List<String> expected ) {
    List<String> a = new ArrayList<>( actual );
    a.sort( java.util.Comparator.comparingInt( x -> expected.indexOf( x ) < 0 ? Integer.MAX_VALUE : expected.indexOf( x ) ) );
    return a;
  }

  /** Orders (fact) joined to customers (left outer… inner) and products: Steel Wheels' shape. */
  static DatasetDefinition star() {
    DatasetDefinition d = new DatasetDefinition();
    d.setId( "orders-star" );
    d.setName( "Orders star" );
    Source s = new Source();
    s.setType( SourceType.TABLES );
    s.setConnection( "SampleData" );
    s.setTables( new ArrayList<>( List.of( "PUBLIC.ORDERFACT", "PUBLIC.CUSTOMER_W_TER", "PUBLIC.PRODUCTS" ) ) );
    d.setSource( s );
    d.setColumns( new ArrayList<>( List.of(
      tableColumn( "quantity", "PUBLIC.ORDERFACT", "QUANTITYORDERED", ColumnType.INTEGER ),
      tableColumn( "price", "PUBLIC.ORDERFACT", "TOTALPRICE", ColumnType.DECIMAL ),
      tableColumn( "status", "PUBLIC.ORDERFACT", "STATUS", ColumnType.STRING ),
      tableColumn( "customer", "PUBLIC.CUSTOMER_W_TER", "CUSTOMERNAME", ColumnType.STRING ),
      tableColumn( "country", "PUBLIC.CUSTOMER_W_TER", "COUNTRY", ColumnType.STRING ),
      tableColumn( "productLine", "PUBLIC.PRODUCTS", "PRODUCTLINE", ColumnType.STRING ) ) ) );
    d.getRelationships().add( relationship( "PUBLIC.ORDERFACT", "CUSTOMERNUMBER", "PUBLIC.CUSTOMER_W_TER", "CUSTOMERNUMBER",
      DatasetDefinition.JoinType.INNER, DatasetDefinition.Cardinality.MANY_TO_ONE ) );
    // Written from the dimension's side; read from the many side.
    d.getRelationships().add( relationship( "PUBLIC.PRODUCTS", "PRODUCTCODE", "PUBLIC.ORDERFACT", "PRODUCTCODE",
      DatasetDefinition.JoinType.LEFT_OUTER, DatasetDefinition.Cardinality.ONE_TO_MANY ) );
    Model m = new Model();
    Dimension customer = new Dimension();
    customer.setName( "Customer" );
    customer.getLevels().addAll( List.of( "country", "customer" ) );
    Dimension line = new Dimension();
    line.setName( "Product line" );
    line.getLevels().add( "productLine" );
    Dimension status = new Dimension();
    status.setName( "Status" );
    status.getLevels().add( "status" );
    m.getDimensions().addAll( List.of( customer, line, status ) );
    m.getMeasures().add( measure( "Quantity", "quantity", Aggregation.SUM, null ) );
    m.getMeasures().add( measure( "Sales", "price", Aggregation.SUM, "#,##0.00" ) );
    d.setModel( m );
    return d;
  }

  private static Column tableColumn( String name, String table, String sourceName, ColumnType type ) {
    Column c = column( name, type );
    c.setTable( table );
    c.setSourceName( sourceName );
    return c;
  }

  private static DatasetDefinition.Relationship relationship( String fromTable, String fromColumn, String toTable, String toColumn,
                                                              DatasetDefinition.JoinType join, DatasetDefinition.Cardinality cardinality ) {
    DatasetDefinition.Relationship r = new DatasetDefinition.Relationship();
    r.setFromTable( fromTable );
    r.setFromColumn( fromColumn );
    r.setToTable( toTable );
    r.setToColumn( toColumn );
    r.setJoinType( join );
    r.setCardinality( cardinality );
    return r;
  }

  // ------------------------------------------------------------------------------------------------

  /** A CSV of regional sales, loaded into DS_REGIONAL_SALES_ABC: region and month by units and revenue. */
  static DatasetDefinition sales() {
    DatasetDefinition d = new DatasetDefinition();
    d.setId( "regional-sales" );
    d.setName( "Regional sales" );
    d.setDescription( "Units and revenue by region and month" );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setConnection( "Hibernate" );
    FileSource f = new FileSource();
    f.setFormat( FileFormat.CSV );
    f.setPath( "regional-sales/00000000-0000-0000-0000-000000000000.csv" );
    f.setHeaderRow( true );
    f.setStagingTable( "DS_REGIONAL_SALES_ABC" );
    f.setStagedColumns( new ArrayList<>( List.of( new StagedColumn( "region", "REGION" ), new StagedColumn( "month", "MONTH" ),
      new StagedColumn( "units", "UNITS" ), new StagedColumn( "note", "NOTE" ), new StagedColumn( "revenue", "REVENUE" ) ) ) );
    s.setFile( f );
    d.setSource( s );
    d.setColumns( new ArrayList<>( List.of( column( "region", ColumnType.STRING ), column( "month", ColumnType.DATE ),
      column( "units", ColumnType.INTEGER ), column( "note", ColumnType.STRING ), column( "revenue", ColumnType.DECIMAL ) ) ) );
    Model m = new Model();
    Dimension region = new Dimension();
    region.setName( "Region" );
    region.getLevels().add( "region" );
    Dimension month = new Dimension();
    month.setName( "Month" );
    month.getLevels().add( "month" );
    m.getDimensions().add( region );
    m.getDimensions().add( month );
    m.getMeasures().add( measure( "Units", "units", Aggregation.SUM, null ) );
    m.getMeasures().add( measure( "Revenue", "revenue", Aggregation.SUM, "#,##0.00" ) );
    m.getMeasures().add( measure( "Average order", "revenue", Aggregation.AVERAGE, null ) );
    d.setModel( m );
    return d;
  }

  private static Column column( String name, ColumnType type ) {
    Column c = new Column();
    c.setName( name );
    c.setSourceName( name );
    c.setType( type );
    return c;
  }

  private static Measure measure( String name, String column, Aggregation a, String format ) {
    Measure m = new Measure();
    m.setName( name );
    m.setColumn( column );
    m.setAggregation( a );
    m.setFormat( format );
    return m;
  }

  static SqlDataSource dataSource( String jndi ) {
    SqlDataSource ds = new SqlDataSource();
    ds.setType( SqlDataSource.DataSourceType.JNDI );
    ds.setDatabaseName( jndi );
    ds.setDialectType( "HYPERSONIC" );
    return ds;
  }

  private static PhysicalSource fileSource( DatasetDefinition d ) {
    return PhysicalSource.of( d, "Hibernate", dataSource( "Hibernate" ) );
  }

  private DatasetModelGenerator.Generated generate( DatasetDefinition d ) {
    return generator.generate( d, fileSource( d ), PublishedIds.of( d ) );
  }

  private static InputStream stream( String s ) {
    return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
  }

  private static void expectProblem( String path, Runnable call ) {
    try {
      call.run();
      fail( "accepted" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
      assertEquals( path, e.getPath() );
    }
  }

  /** Compares with the checked-in file; with -Ddatasets.golden.update=<resources dir>, rewrites it instead. */
  private static void golden( String name, String actual ) throws Exception {
    assertNotNull( name, actual );
    String update = System.getProperty( "datasets.golden.update" );
    if ( update != null ) {
      Path p = Path.of( update + GOLDEN + name );
      Files.createDirectories( p.getParent() );
      Files.writeString( p, actual );
      return;
    }
    try ( InputStream in = DatasetModelGeneratorTest.class.getResourceAsStream( GOLDEN + name ) ) {
      assertNotNull( "golden file " + name + " (generate it with -Ddatasets.golden.update)", in );
      assertEquals( name, new String( in.readAllBytes(), StandardCharsets.UTF_8 ), actual );
    }
  }
}
