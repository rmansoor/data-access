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
import org.pentaho.platform.api.scheduler2.IComplexJobTrigger;
import org.pentaho.platform.api.scheduler2.IJob;
import org.pentaho.platform.api.scheduler2.IJobFilter;
import org.pentaho.platform.api.scheduler2.IJobTrigger;
import org.pentaho.platform.api.scheduler2.IScheduler;
import org.pentaho.platform.api.scheduler2.JobState;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The platform side of dataset schedules: one cron job of the refresh action per dataset. */
public class DatasetSchedulesTest {

  /** A scheduler that keeps jobs in a list, as the platform's would. */
  private static final class Fake {
    final List<IJob> jobs = new ArrayList<>();
    int next;

    IScheduler scheduler() {
      return (IScheduler) Proxy.newProxyInstance( getClass().getClassLoader(), new Class<?>[] { IScheduler.class }, ( p, m, a ) -> {
        switch ( m.getName() ) {
          case "createComplexJobTrigger":
            return trigger();
          case "createComplexTrigger":
            // As the platform's parser: seven fields, and no names it doesn't know.
            if ( ( (String) a[ 0 ] ).split( " " ).length != 7 || ( (String) a[ 0 ] ).contains( "X" ) ) {
              throw new IllegalArgumentException( "ComplexJobTrigger.ERROR_0001 - Invalid cron expression." );
            }
            return trigger();
          case "createJob":
            if ( a.length == 4 && a[ 1 ] instanceof String ) {
              @SuppressWarnings( "unchecked" )
              Map<String, Object> params = new HashMap<>( (Map<String, Object>) a[ 2 ] );
              params.put( IScheduler.RESERVEDMAPKEY_ACTIONID, a[ 1 ] );
              IJob j = job( "job" + ( ++next ), (String) a[ 0 ], params, (IJobTrigger) a[ 3 ] );
              jobs.add( j );
              return j;
            }
            throw new UnsupportedOperationException( "createJob by class" );
          case "getJobs":
            List<IJob> out = new ArrayList<>();
            for ( IJob j : jobs ) {
              if ( ( (IJobFilter) a[ 0 ] ).accept( j ) ) {
                out.add( j );
              }
            }
            return out;
          case "removeJob":
            jobs.removeIf( j -> j.getJobId().equals( a[ 0 ] ) );
            return null;
          default:
            throw new UnsupportedOperationException( m.getName() );
        }
      } );
    }

    static IComplexJobTrigger trigger() {
      Map<String, Object> state = new HashMap<>();
      return (IComplexJobTrigger) Proxy.newProxyInstance( Fake.class.getClassLoader(), new Class<?>[] { IComplexJobTrigger.class },
        ( p, m, a ) -> {
          String n = m.getName();
          if ( n.startsWith( "set" ) ) {
            state.put( n.substring( 3 ), a[ 0 ] );
            return null;
          }
          if ( n.startsWith( "get" ) ) {
            return state.get( n.substring( 3 ) );
          }
          throw new UnsupportedOperationException( n );
        } );
    }

    static IJob job( String id, String name, Map<String, Object> params, IJobTrigger trigger ) {
      return (IJob) Proxy.newProxyInstance( Fake.class.getClassLoader(), new Class<?>[] { IJob.class }, ( p, m, a ) -> {
        switch ( m.getName() ) {
          case "getJobId":
            return id;
          case "getJobName":
            return name;
          case "getJobParams":
            return params;
          case "getJobTrigger":
            return trigger;
          case "getState":
            return JobState.NORMAL;
          case "getUserName":
            return params.get( IScheduler.RESERVEDMAPKEY_ACTIONUSER );
          case "getNextRun":
            return new Date( 1_800_000_000_000L );
          case "getLastRun":
            return null;
          default:
            throw new UnsupportedOperationException( m.getName() );
        }
      } );
    }
  }

  @Test
  public void createsOneRefreshJobPerDatasetAndReplacesIt() {
    Fake fake = new Fake();
    DatasetSchedules schedules = new DatasetSchedules.InScheduler( fake::scheduler );
    DatasetSchedule s = new DatasetSchedule();
    s.setCron( " 0 0 6 ? * 2-6 " );
    s.setTimeZone( "Europe/Berlin" );
    DatasetSchedule set = schedules.set( "sales", "Refresh Sales", "suzy", s );
    assertEquals( "the year is added for the scheduler's parser", "0 0 6 ? * 2-6 *", set.getCron() );
    assertEquals( "Europe/Berlin", set.getTimeZone() );
    assertEquals( "suzy", set.getOwner() );
    assertEquals( "NORMAL", set.getState() );
    assertEquals( "2027-01-15T08:00:00Z", set.getNextRun() );
    IJob job = fake.jobs.get( 0 );
    assertEquals( "Refresh Sales", job.getJobName() );
    assertEquals( DatasetSchedules.ACTION_ID, job.getJobParams().get( IScheduler.RESERVEDMAPKEY_ACTIONID ) );
    assertEquals( "sales", job.getJobParams().get( DatasetSchedules.DATASET_ID ) );

    // Another dataset's job is left alone; this dataset's is replaced.
    schedules.set( "stock", "Refresh Stock", "suzy", s );
    DatasetSchedule hourly = new DatasetSchedule();
    hourly.setCron( "0 0 * * * ?" );
    schedules.set( "sales", "Refresh Sales", "suzy", hourly );
    assertEquals( 2, fake.jobs.size() );
    assertEquals( "0 0 * * * ? *", schedules.get( "sales" ).get().getCron() );
    assertEquals( "0 0 6 ? * 2-6 *", schedules.get( "stock" ).get().getCron() );

    schedules.remove( "sales" );
    assertFalse( schedules.get( "sales" ).isPresent() );
    assertTrue( schedules.get( "stock" ).isPresent() );
    schedules.remove( "nothing" );
  }

  @Test
  public void aCronTheSchedulerCantReadIsRefusedBeforeAJobExists() {
    Fake fake = new Fake();
    DatasetSchedules schedules = new DatasetSchedules.InScheduler( fake::scheduler );
    DatasetSchedule s = new DatasetSchedule();
    s.setCron( "0 0 6 ? * X" );
    try {
      schedules.set( "sales", "Refresh Sales", "suzy", s );
      throw new AssertionError( "scheduled" );
    } catch ( DatasetApiException e ) {
      assertEquals( "cron", e.getPath() );
    }
    assertTrue( fake.jobs.isEmpty() );
  }

  @Test
  public void theActionNeedsADataset() throws Exception {
    DatasetRefreshAction a = new DatasetRefreshAction();
    try {
      a.execute();
      throw new AssertionError( "ran" );
    } catch ( IllegalStateException e ) {
      assertFalse( a.isExecutionSuccessful() );
    }
  }
}
