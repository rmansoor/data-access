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

import org.pentaho.platform.api.scheduler2.IComplexJobTrigger;
import org.pentaho.platform.api.scheduler2.IJob;
import org.pentaho.platform.api.scheduler2.IScheduler;
import org.pentaho.platform.api.scheduler2.SchedulerException;
import org.pentaho.platform.engine.core.system.PentahoSystem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Supplier;

/** The scheduled reload of each file dataset: at most one job per dataset. */
public interface DatasetSchedules {

  /** The scheduler bean that runs a dataset's reload (plugin.spring.xml). */
  String ACTION_ID = "datasetRefreshAction";
  /** The job parameter naming the dataset. */
  String DATASET_ID = "datasetId";

  Optional<DatasetSchedule> get( String datasetId );

  /** Creates the dataset's job, or replaces it; as {@code user}, who then owns it. */
  DatasetSchedule set( String datasetId, String jobName, String user, DatasetSchedule schedule );

  void remove( String datasetId );

  /** Checks a cron expression's shape: six or seven fields, seconds first. The scheduler checks the rest. */
  static void requireCron( DatasetSchedule s ) {
    String cron = s == null ? null : s.getCron();
    int fields = cron == null || cron.isBlank() ? 0 : cron.trim().split( "\\s+" ).length;
    if ( fields < 6 || fields > 7 ) {
      throw new DatasetApiException( DatasetApiException.Code.INVALID_REQUEST, "cron",
        "cron must be a Quartz expression with six or seven fields, seconds first (e.g. \"0 0 6 ? * 2-6\")", null, null );
    }
    if ( s.getTimeZone() != null && !List.of( TimeZone.getAvailableIDs() ).contains( s.getTimeZone() ) ) {
      throw new DatasetApiException( DatasetApiException.Code.INVALID_REQUEST, "timeZone", "unknown time zone " + s.getTimeZone(),
        null, null );
    }
  }

  /** Kept in memory; for tests. */
  class InMemory implements DatasetSchedules {
    private final Map<String, DatasetSchedule> byDataset = new HashMap<>();

    @Override
    public synchronized Optional<DatasetSchedule> get( String datasetId ) {
      return Optional.ofNullable( byDataset.get( datasetId ) );
    }

    @Override
    public synchronized DatasetSchedule set( String datasetId, String jobName, String user, DatasetSchedule schedule ) {
      requireCron( schedule );
      DatasetSchedule s = new DatasetSchedule();
      s.setCron( schedule.getCron().trim() );
      s.setTimeZone( schedule.getTimeZone() );
      s.setJobId( user + "\t" + jobName );
      s.setOwner( user );
      s.setState( "NORMAL" );
      byDataset.put( datasetId, s );
      return s;
    }

    @Override
    public synchronized void remove( String datasetId ) {
      byDataset.remove( datasetId );
    }
  }

  /** In the platform scheduler: a cron job of {@link #ACTION_ID} with the dataset's id as its parameter. */
  class InScheduler implements DatasetSchedules {
    private final Supplier<IScheduler> scheduler;

    public InScheduler() {
      this( () -> PentahoSystem.get( IScheduler.class, "IScheduler2", null ) );
    }

    public InScheduler( Supplier<IScheduler> scheduler ) {
      this.scheduler = scheduler;
    }

    private IScheduler scheduler() {
      IScheduler s = scheduler.get();
      if ( s == null ) {
        throw new DatasetApiException( DatasetApiException.Code.INTERNAL, "", "The scheduler isn't running on this server", null, null );
      }
      return s;
    }

    private Optional<IJob> job( String datasetId ) throws SchedulerException {
      List<IJob> jobs = scheduler().getJobs( j -> j.getJobParams() != null
        && ACTION_ID.equals( j.getJobParams().get( IScheduler.RESERVEDMAPKEY_ACTIONID ) )
        && datasetId.equals( j.getJobParams().get( DATASET_ID ) ) );
      return jobs.stream().findFirst();
    }

    @Override
    public Optional<DatasetSchedule> get( String datasetId ) {
      try {
        return job( datasetId ).map( InScheduler::toSchedule );
      } catch ( SchedulerException e ) {
        throw failed( e );
      }
    }

    @Override
    public DatasetSchedule set( String datasetId, String jobName, String user, DatasetSchedule schedule ) {
      requireCron( schedule );
      String cron = withYear( schedule.getCron() );
      IScheduler s = scheduler();
      try {
        // The scheduler reads every job's cron back with this parser (listing jobs, and checking
        // blockouts before any job runs), so a cron it can't read would break every schedule.
        s.createComplexTrigger( cron );
      } catch ( RuntimeException e ) {
        throw new DatasetApiException( DatasetApiException.Code.INVALID_REQUEST, "cron", "The scheduler can't read the cron expression \""
          + schedule.getCron().trim() + "\"", null, e );
      }
      try {
        // Quartz takes complex triggers only; one with a cron string uses it as given (the scheduler's
        // REST layer turns cron triggers into these the same way).
        IComplexJobTrigger trigger = s.createComplexJobTrigger();
        trigger.setCronString( cron );
        trigger.setStartTime( new java.util.Date() );
        if ( schedule.getTimeZone() != null ) {
          trigger.setTimeZone( schedule.getTimeZone() );
        }
        Map<String, Object> params = new HashMap<>();
        params.put( DATASET_ID, datasetId );
        params.put( IScheduler.RESERVEDMAPKEY_ACTIONUSER, user );
        Optional<IJob> existing = job( datasetId );
        IJob created = s.createJob( jobName, ACTION_ID, params, trigger );
        if ( existing.isPresent() ) {
          s.removeJob( existing.get().getJobId() );
        }
        return toSchedule( created );
      } catch ( SchedulerException e ) {
        throw new DatasetApiException( DatasetApiException.Code.INVALID_REQUEST, "cron", "The scheduler refused the schedule: "
          + e.getMessage(), null, e );
      }
    }

    @Override
    public void remove( String datasetId ) {
      try {
        Optional<IJob> j = job( datasetId );
        if ( j.isPresent() ) {
          scheduler().removeJob( j.get().getJobId() );
        }
      } catch ( SchedulerException e ) {
        throw failed( e );
      }
    }

    /** The scheduler's parser wants the year field; a six-field cron gets "every year". */
    static String withYear( String cron ) {
      String c = cron.trim().replaceAll( "\\s+", " " );
      return c.split( " " ).length == 6 ? c + " *" : c;
    }

    private static DatasetSchedule toSchedule( IJob job ) {
      DatasetSchedule s = new DatasetSchedule();
      if ( job.getJobTrigger() != null ) {
        s.setCron( job.getJobTrigger().getCronString() );
        s.setTimeZone( job.getJobTrigger().getTimeZone() );
      }
      s.setJobId( job.getJobId() );
      s.setOwner( job.getUserName() );
      s.setState( job.getState() == null ? null : job.getState().name() );
      s.setNextRun( job.getNextRun() == null ? null : job.getNextRun().toInstant().toString() );
      s.setLastRun( job.getLastRun() == null ? null : job.getLastRun().toInstant().toString() );
      return s;
    }

    private static DatasetApiException failed( SchedulerException e ) {
      return new DatasetApiException( DatasetApiException.Code.INTERNAL, "", "The scheduler could not complete the request", null, e );
    }
  }
}
