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

package org.pentaho.platform.dataaccess.dataset.source;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The database adapters against a real database (HSQLDB in memory). */
public class JdbcSourceAdaptersTest {

  private Connection db;
  private ConnectionProvider connections;
  private TablesSourceAdapter tables;
  private SqlSourceAdapter sql;

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection( "jdbc:hsqldb:mem:ds" + UUID.randomUUID(), "SA", "" );
    try ( Statement st = db.createStatement() ) {
      st.execute( "CREATE SCHEMA sales" );
      st.execute( "CREATE TABLE sales.orders (id INTEGER NOT NULL PRIMARY KEY, amount DECIMAL(10,2), placed DATE, "
        + "stamped TIMESTAMP, shipped BOOLEAN, note VARCHAR(40), big NUMERIC(20,0))" );
      st.execute( "INSERT INTO sales.orders VALUES (1, 10.50, DATE '2026-01-31', TIMESTAMP '2026-01-31 09:30:00', TRUE, 'first', "
        + "12345678901234567890)" );
      st.execute( "INSERT INTO sales.orders VALUES (2, 99.99, DATE '2026-02-01', NULL, FALSE, NULL, 2)" );
      st.execute( "INSERT INTO sales.orders VALUES (3, 0.01, DATE '2026-02-02', NULL, TRUE, 'third', 3)" );
      st.execute( "CREATE TABLE \"Mixed Case\" (\"Order Id\" INTEGER)" );
      st.execute( "INSERT INTO \"Mixed Case\" VALUES (7)" );
    }
    // Like a pool: the adapters get the same connection back each time, and close() only hands it back.
    connections = name -> {
      if ( !"Sample".equals( name ) ) {
        throw new NoSuchElementException( name );
      }
      return (Connection) Proxy.newProxyInstance( getClass().getClassLoader(), new Class<?>[] { Connection.class },
        ( proxy, method, args ) -> {
          if ( "close".equals( method.getName() ) ) {
            return null;
          }
          try {
            return method.invoke( db, args );
          } catch ( java.lang.reflect.InvocationTargetException e ) {
            throw e.getCause();
          }
        } );
    };
    tables = new TablesSourceAdapter( connections, SourceLimits.standard() );
    sql = new SqlSourceAdapter( connections, SourceLimits.standard() );
  }

  @After
  public void tearDown() throws Exception {
    try ( Statement st = db.createStatement() ) {
      st.execute( "SHUTDOWN" );
    }
  }

  private static Source tables( String... names ) {
    Source s = new Source();
    s.setType( SourceType.TABLES );
    s.setConnection( "Sample" );
    s.setTables( new java.util.ArrayList<>( List.of( names ) ) );
    return s;
  }

  private static Source sql( String query ) {
    Source s = new Source();
    s.setType( SourceType.SQL );
    s.setConnection( "Sample" );
    s.setSql( query );
    return s;
  }

  @Test
  public void describesTableColumnsWithDatasetTypes() {
    List<SourceColumn> columns = tables.schema( tables( "sales.orders" ) );
    assertEquals( "[ID:INTEGER, AMOUNT:DECIMAL, PLACED:DATE, STAMPED:TIMESTAMP, SHIPPED:BOOLEAN, NOTE:STRING, BIG:INTEGER]",
      columns.toString() );
    assertFalse( columns.get( 0 ).getNullable() );
    assertTrue( columns.get( 1 ).getNullable() );
    assertEquals( "sales.orders", columns.get( 0 ).getTable() );
  }

  @Test
  public void findsTablesHoweverTheNameIsCased() {
    assertEquals( 7, tables.schema( tables( "SALES.ORDERS" ) ).size() );
    assertEquals( 7, tables.schema( tables( "Sales.Orders" ) ).size() );
    assertEquals( "[Order Id:INTEGER]", tables.schema( tables( "Mixed Case" ) ).toString() );
    assertEquals( 7L, tables.sample( tables( "Mixed Case" ), 10, null ).getRows().get( 0 ).get( 0 ) );
  }

  @Test
  public void samplesTypedValuesAndSaysWhenThereAreMore() {
    Sample s = tables.sample( tables( "sales.orders" ), 2, null );
    assertEquals( 2, s.getRows().size() );
    assertTrue( s.isTruncated() );
    List<Object> first = s.getRows().get( 0 );
    assertEquals( 1L, first.get( 0 ) );
    assertEquals( new BigDecimal( "10.50" ), first.get( 1 ) );
    assertEquals( "2026-01-31", first.get( 2 ) );
    assertEquals( "2026-01-31T09:30", first.get( 3 ) );
    assertEquals( Boolean.TRUE, first.get( 4 ) );
    assertEquals( "first", first.get( 5 ) );
    assertEquals( "too big for a long", new BigDecimal( "12345678901234567890" ), first.get( 6 ) );
    assertNull( s.getRows().get( 1 ).get( 3 ) );
    assertNull( s.getRows().get( 1 ).get( 5 ) );

    assertFalse( tables.sample( tables( "sales.orders" ), 3, null ).isTruncated() );
  }

  @Test
  public void samplesTheTableAsked() {
    Sample s = tables.sample( tables( "sales.orders", "Mixed Case" ), 10, "Mixed Case" );
    assertEquals( "Order Id", s.getColumns().get( 0 ).getName() );
    expect( Code.VALIDATION_FAILED, "table", () -> tables.sample( tables( "sales.orders" ), 10, "other" ) );
  }

  @Test
  public void neverBuildsSqlFromTheGivenName() {
    DatasetApiException e = expect( Code.VALIDATION_FAILED, "source.tables[0]",
      () -> tables.sample( tables( "orders; DROP TABLE orders" ), 10, null ) );
    assertTrue( e.getMessage(), e.getMessage().contains( "names no table" ) );
    expect( Code.VALIDATION_FAILED, "source.tables[1]", () -> tables.schema( tables( "sales.orders", "sales.ord_rs" ) ) );
    expect( Code.VALIDATION_FAILED, "source.tables[0]", () -> tables.schema( tables( "a.b.c" ) ) );
    assertEquals( 3, tables.sample( tables( "sales.orders" ), 10, null ).getRows().size() );
  }

  @Test
  public void runsAQueryAndDescribesItsColumns() {
    Source q = sql( "SELECT id, amount * 2 AS doubled, note FROM sales.orders ORDER BY id" );
    assertEquals( "[ID:INTEGER, DOUBLED:DECIMAL, NOTE:STRING]", sql.schema( q ).toString() );
    Sample s = sql.sample( q, 5, null );
    assertEquals( 3, s.getRows().size() );
    assertEquals( new BigDecimal( "21.00" ), s.getRows().get( 0 ).get( 1 ) );
  }

  @Test
  public void refusesWritesBeforeTheyReachTheDatabase() {
    expect( Code.VALIDATION_FAILED, "source.sql", () -> sql.sample( sql( "DELETE FROM sales.orders" ), 5, null ) );
    expect( Code.VALIDATION_FAILED, "source.sql", () -> sql.sample( sql( "SELECT 1 FROM sales.orders; DROP TABLE sales.orders" ), 5,
      null ) );
    assertEquals( 3, tables.sample( tables( "sales.orders" ), 10, null ).getRows().size() );
  }

  @Test
  public void reportsDatabaseErrorsAsSourceFailures() {
    DatasetApiException e = expect( Code.SOURCE_FAILED, "source", () -> sql.sample( sql( "SELECT nope FROM sales.orders" ), 5, null ) );
    assertTrue( e.getMessage(), e.getMessage().startsWith( "The database refused the request" ) );
  }

  @Test
  public void handsConnectionsBackAsItFoundThem() throws Exception {
    sql.sample( sql( "SELECT * FROM sales.orders" ), 5, null );
    assertTrue( db.getAutoCommit() );
    assertFalse( db.isReadOnly() );
  }

  @Test
  public void namesTheMissingConnection() {
    Source s = sql( "SELECT 1 FROM sales.orders" );
    s.setConnection( "Nope" );
    expect( Code.VALIDATION_FAILED, "source.connection", () -> sql.sample( s, 5, null ) );
    s.setConnection( null );
    expect( Code.VALIDATION_FAILED, "source.connection", () -> sql.sample( s, 5, null ) );
  }

  @Test
  public void mapsJdbcTypes() {
    assertEquals( ColumnType.INTEGER, JdbcValues.columnType( java.sql.Types.NUMERIC, 0 ) );
    assertEquals( "bare Oracle NUMBER", ColumnType.DECIMAL, JdbcValues.columnType( java.sql.Types.NUMERIC, -127 ) );
    assertEquals( ColumnType.STRING, JdbcValues.columnType( java.sql.Types.CLOB, 0 ) );
  }

  private static DatasetApiException expect( Code code, String path, Runnable call ) {
    try {
      call.run();
      fail( "expected " + code );
      return null;
    } catch ( DatasetApiException e ) {
      assertEquals( e.getMessage(), code, e.getCode() );
      assertEquals( e.getMessage(), path, e.getPath() );
      return e;
    }
  }

  @Test
  public void listsTheConnectionsTablesAndViewsWithoutTheDatabasesOwn() throws Exception {
    try ( Statement st = db.createStatement() ) {
      st.execute( "CREATE VIEW sales.big_orders AS SELECT * FROM sales.orders WHERE amount > 50" );
    }
    TableList list = tables.list( "Sample" );
    assertFalse( list.isTruncated() );
    assertEquals( "[Mixed Case:TABLE, SALES.BIG_ORDERS:VIEW, SALES.ORDERS:TABLE]",
      list.getTables().stream().map( e -> ( e.getSchema() == null || e.getSchema().equals( "PUBLIC" ) ? e.getName() : e.getQualifiedName() )
        + ":" + e.getType() ).collect( java.util.stream.Collectors.toList() ).toString() );
    assertTrue( list.getTables().stream().noneMatch( e -> "INFORMATION_SCHEMA".equals( e.getSchema() ) ) );
    try {
      tables.list( "Nope" );
      fail();
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
    }
  }
}
