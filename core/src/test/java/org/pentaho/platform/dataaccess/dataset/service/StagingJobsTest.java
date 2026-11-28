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

import org.junit.Test;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.source.CsvStager;
import org.pentaho.platform.dataaccess.dataset.source.StagedTable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StagingJobsTest {

  private final StagingJobs jobs = new StagingJobs( 2 );

  private static void await( StagingJobs.Job job ) throws InterruptedException {
    for ( int i = 0; i < 500 && job.getState() == StagingJobs.State.RUNNING; i++ ) {
      Thread.sleep( 10 );
    }
  }

  @Test
  public void runsOneLoadPerDatasetAndReportsProgressAndTheOutcome() throws Exception {
    CountDownLatch release = new CountDownLatch( 1 );
    CountDownLatch progressed = new CountDownLatch( 1 );
    StagingJobs.Job job = jobs.start( "orders", progress -> {
      progress.read( 3000 );
      progress.loaded( 2000 );
      progressed.countDown();
      try {
        release.await( 5, TimeUnit.SECONDS );
      } catch ( InterruptedException e ) {
        throw new IllegalStateException( e );
      }
      return Map.entry( new StagedTable( "Staging", "DS_ORDERS_1", 3000, List.of(), List.of() ), "7" );
    } );
    assertTrue( progressed.await( 5, TimeUnit.SECONDS ) );
    assertEquals( StagingJobs.State.RUNNING, job.getState() );
    assertEquals( 3000, job.getRowsRead() );
    assertEquals( 2000, job.getRowsLoaded() );
    try {
      jobs.start( "orders", p -> {
        throw new AssertionError( "a second load started" );
      } );
      fail( "two loads of one dataset" );
    } catch ( DatasetApiException e ) {
      assertEquals( Code.ALREADY_EXISTS, e.getCode() );
    }

    release.countDown();
    await( job );
    assertEquals( StagingJobs.State.SUCCEEDED, job.getState() );
    assertEquals( "7", job.getVersion() );
    assertEquals( 3000, job.getRowsLoaded() );
    assertNotNull( job.getFinished() );
    assertEquals( job, jobs.last( "orders" ) );

    // As the API writes it: ISO timestamps, the table, no error.
    com.fasterxml.jackson.databind.JsonNode json = org.pentaho.platform.dataaccess.dataset.DatasetJson.mapper()
      .readTree( org.pentaho.platform.dataaccess.dataset.DatasetJson.mapper().writeValueAsString( job ) );
    assertEquals( "SUCCEEDED", json.get( "state" ).asText() );
    assertTrue( json.get( "started" ).asText().matches( "20\\d\\d-\\d\\d-\\d\\dT.*Z" ) );
    assertTrue( json.get( "finished" ).asText().endsWith( "Z" ) );
    assertEquals( "DS_ORDERS_1", json.at( "/table/table" ).asText() );
    assertEquals( "7", json.get( "version" ).asText() );
    assertNull( "no error when it succeeded", json.get( "error" ) );
  }

  @Test
  public void stopsWhenCancelledAndReportsFailures() throws Exception {
    CountDownLatch started = new CountDownLatch( 1 );
    StagingJobs.Job job = jobs.start( "orders", progress -> {
      started.countDown();
      for ( int i = 0; i < 500; i++ ) {
        if ( progress.cancelled() ) {
          throw new CsvStager.Cancelled();
        }
        sleep();
      }
      fail( "never cancelled" );
      return null;
    } );
    assertTrue( started.await( 5, TimeUnit.SECONDS ) );
    jobs.cancel( "orders" );
    await( job );
    assertEquals( StagingJobs.State.CANCELLED, job.getState() );

    StagingJobs.Job failed = jobs.start( "orders", p -> {
      throw new DatasetApiException( Code.SOURCE_FAILED, "source", "The file could not be read", null, null );
    } );
    await( failed );
    assertEquals( StagingJobs.State.FAILED, failed.getState() );
    assertEquals( "source-failed", failed.getError().getCode() );
    assertEquals( "source", failed.getError().getPath() );
    assertNull( jobs.last( "other" ) );
  }

  private static void sleep() {
    try {
      Thread.sleep( 10 );
    } catch ( InterruptedException e ) {
      throw new IllegalStateException( e );
    }
  }

}
