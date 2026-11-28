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

import org.junit.Test;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SqlGuardTest {

  @Test
  public void acceptsOneReadOnlyQuery() {
    assertEquals( "SELECT * FROM orders", SqlGuard.check( "  SELECT * FROM orders ;  -- done\n" ) );
    assertEquals( "with t as (select 1 x) select x from t", SqlGuard.check( "with t as (select 1 x) select x from t" ) );
    assertEquals( "(SELECT 1) UNION (SELECT 2)", SqlGuard.check( "(SELECT 1) UNION (SELECT 2)" ) );
    assertEquals( "VALUES (1, 2)", SqlGuard.check( "VALUES (1, 2)" ) );
  }

  @Test
  public void ignoresKeywordsAndSemicolonsInsideTextNamesAndComments() {
    SqlGuard.check( "SELECT 'drop table x; delete' AS note FROM orders" );
    SqlGuard.check( "SELECT 'it''s; fine' FROM orders" );
    SqlGuard.check( "SELECT \"update\", `delete`, [Order Details].qty FROM [Order Details]" );
    SqlGuard.check( "SELECT a /* ; insert into x */ FROM t -- ; drop table t" );
    SqlGuard.check( "SELECT updated_at, deleted, insert_count, created_by FROM audit" );
    SqlGuard.check( "SELECT * FROM V$SESSION" );
  }

  @Test
  public void refusesAnythingThatChangesData() {
    refused( "DELETE FROM orders", "must be a query" );
    refused( "UPDATE orders SET x = 1", "must be a query" );
    refused( "drop table orders", "must be a query" );
    refused( "WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone", "DELETE is not allowed" );
    refused( "SELECT * INTO backup FROM orders", "INTO is not allowed" );
    refused( "SELECT * FROM orders INTO OUTFILE '/tmp/x'", "INTO is not allowed" );
    refused( "SELECT * FROM orders FOR UPDATE", "UPDATE is not allowed" );
    refused( "CALL do_something()", "must be a query" );
  }

  @Test
  public void refusesMoreThanOneStatement() {
    refused( "SELECT 1; DROP TABLE orders", "single statement" );
    refused( "SELECT 1;SELECT 2", "single statement" );
  }

  @Test
  public void neverSkipsTextSomeDatabaseWouldRun() {
    // MySQL reads 1--1 as arithmetic, PostgreSQL # as XOR, MySQL runs /*! … */, and $tag$ is a name there.
    refused( "SELECT 1--1; DROP TABLE orders", "single statement" );
    refused( "SELECT 5 # 3; DROP TABLE orders", "single statement" );
    refused( "SELECT 1 /*! ; DROP TABLE orders */", "executable comment" );
    refused( "SELECT $a$ ; DROP TABLE orders ; $a$", "single statement" );
    // Backslash escapes the quote in MySQL but not elsewhere, so the end of the text is ambiguous.
    refused( "SELECT 'a\\'' ; DROP TABLE orders --'", "backslash" );
    refused( "SELECT \"a\\\"\" ; DROP TABLE orders", "backslash" );
    refused( "SELECT a /* /* */ ; DROP TABLE t */", "nested comment" );
  }

  @Test
  public void explainsMalformedSql() {
    refused( "", "is required" );
    refused( "-- just a comment", "only comments" );
    refused( "SELECT 'open", "never closed" );
    refused( "SELECT 1 /* open", "never closed" );
  }

  private static void refused( String sql, String why ) {
    try {
      SqlGuard.check( sql );
      fail( "accepted: " + sql );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.VALIDATION_FAILED, e.getCode() );
      assertEquals( "source.sql", e.getPath() );
      assertTrue( sql + " -> " + e.getMessage(), e.getMessage().contains( why ) );
    }
  }
}
