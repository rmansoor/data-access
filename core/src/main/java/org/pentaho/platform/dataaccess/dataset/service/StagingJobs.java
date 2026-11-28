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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.source.CsvStager;
import org.pentaho.platform.dataaccess.dataset.source.StagedTable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Loads running in the background, at most one per dataset, on a small shared pool. Each
 * dataset's last load is remembered (in memory: a restart forgets it) so its progress and outcome
 * can be read.
 */
public class StagingJobs {

  private static final Log LOG = LogFactory.getLog( StagingJobs.class );

  public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

  /** What a load has done so far, or how it ended. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonPropertyOrder( { "id", "datasetId", "state", "rowsRead", "rowsLoaded", "started", "finished", "table", "version", "error" } )
  public static final class Job {
    private final String id = UUID.randomUUID().toString();
    private final String datasetId;
    private final Instant started = Instant.now();
    private volatile State state = State.RUNNING;
    private volatile long rowsRead;
    private volatile long rowsLoaded;
    private volatile boolean cancelRequested;
    private volatile Instant finished;
    private volatile StagedTable table;
    private volatile String version;
    private volatile Error error;

    Job( String datasetId ) {
      this.datasetId = datasetId;
    }

    public String getId() {
      return id;
    }

    public String getDatasetId() {
      return datasetId;
    }

    public State getState() {
      return state;
    }

    public long getRowsRead() {
      return rowsRead;
    }

    public long getRowsLoaded() {
      return rowsLoaded;
    }

    /** ISO-8601, as the API writes times. */
    public String getStarted() {
      return started.toString();
    }

    /** ISO-8601; null while running. */
    public String getFinished() {
      Instant f = finished;
      return f == null ? null : f.toString();
    }

    /** When it succeeded: the table it loaded. */
    public StagedTable getTable() {
      return table;
    }

    /** When it succeeded: the dataset's new version (its ETag). */
    public String getVersion() {
      return version;
    }

    /** When it failed: why. */
    public Error getError() {
      return error;
    }

    CsvStager.Progress progress() {
      return new CsvStager.Progress() {
        @Override
        public void read( long rows ) {
          rowsRead = rows;
        }

        @Override
        public void loaded( long rows ) {
          rowsLoaded = rows;
        }

        @Override
        public boolean cancelled() {
          return cancelRequested;
        }
      };
    }
  }

  /** A failed load's error, as the API reports errors. */
  @JsonPropertyOrder( { "code", "message", "path" } )
  @JsonInclude( JsonInclude.Include.NON_NULL )
  public static final class Error {
    private final String code;
    private final String message;
    private final String path;

    Error( String code, String message, String path ) {
      this.code = code;
      this.message = message;
      this.path = path == null || path.isEmpty() ? null : path;
    }

    public String getCode() {
      return code;
    }

    public String getMessage() {
      return message;
    }

    public String getPath() {
      return path;
    }
  }

  private final Map<String, Job> last = new ConcurrentHashMap<>();
  private final ExecutorService pool;

  /** Two loads at a time, on daemon threads. */
  public StagingJobs() {
    this( 2 );
  }

  public StagingJobs( int threads ) {
    AtomicInteger n = new AtomicInteger();
    this.pool = Executors.newFixedThreadPool( threads, r -> {
      Thread t = new Thread( r, "dataset-load-" + n.incrementAndGet() );
      t.setDaemon( true );
      return t;
    } );
  }

  /**
   * Starts {@code work} for a dataset unless a load of it is already running.
   *
   * @param work the load; given the job's progress, it answers the staged table and the new version
   */
  public Job start( String datasetId, Function<CsvStager.Progress, Map.Entry<StagedTable, String>> work ) {
    Job job = new Job( datasetId );
    Job running = last.compute( datasetId, ( id, previous ) ->
      previous != null && previous.state == State.RUNNING ? previous : job );
    if ( running != job ) {
      throw new DatasetApiException( Code.ALREADY_EXISTS, "A load of this dataset is already running; wait for it or cancel it" );
    }
    pool.execute( () -> {
      try {
        Map.Entry<StagedTable, String> result = work.apply( job.progress() );
        job.table = result.getKey();
        job.version = result.getValue();
        job.rowsLoaded = result.getKey().getRows();
        job.state = State.SUCCEEDED;
      } catch ( CsvStager.Cancelled e ) {
        job.state = State.CANCELLED;
      } catch ( DatasetApiException e ) {
        job.error = new Error( e.getCode().wire(), e.getMessage(), e.getPath() );
        job.state = State.FAILED;
      } catch ( RuntimeException e ) {
        LOG.error( "Loading dataset " + datasetId + " failed", e );
        job.error = new Error( Code.INTERNAL.wire(), "The load failed; see the server log", null );
        job.state = State.FAILED;
      } finally {
        job.finished = Instant.now();
      }
    } );
    return job;
  }

  /** The dataset's running or last load, if any since the server started. */
  public Job last( String datasetId ) {
    return last.get( datasetId );
  }

  /** Asks the dataset's running load to stop; it stops at its next thousand rows. */
  public Job cancel( String datasetId ) {
    Job job = last.get( datasetId );
    if ( job != null && job.state == State.RUNNING ) {
      job.cancelRequested = true;
    }
    return job;
  }

}
