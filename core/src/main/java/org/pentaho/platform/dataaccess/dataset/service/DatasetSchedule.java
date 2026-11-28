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

/**
 * When a file dataset is loaded again on its own: a Quartz cron expression (seconds first; days of
 * the week 1 = Sunday … 7 = Saturday, or SUN … SAT) and the time zone it is read in. The job runs in
 * the platform scheduler as the person who set it, so it shows on the Schedules page, can be paused
 * there, and blockouts apply.
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonPropertyOrder( { "cron", "timeZone", "jobId", "state", "owner", "nextRun", "lastRun" } )
public class DatasetSchedule {
  private String cron;
  private String timeZone;
  /** Set by the server. */
  private String jobId;
  /** NORMAL, PAUSED, BLOCKED, …; set by the server. */
  private String state;
  private String owner;
  /** ISO-8601 instants; set by the server. */
  private String nextRun;
  private String lastRun;

  public String getCron() {
    return cron;
  }

  public void setCron( String cron ) {
    this.cron = cron;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public void setTimeZone( String timeZone ) {
    this.timeZone = timeZone;
  }

  public String getJobId() {
    return jobId;
  }

  public void setJobId( String jobId ) {
    this.jobId = jobId;
  }

  public String getState() {
    return state;
  }

  public void setState( String state ) {
    this.state = state;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner( String owner ) {
    this.owner = owner;
  }

  public String getNextRun() {
    return nextRun;
  }

  public void setNextRun( String nextRun ) {
    this.nextRun = nextRun;
  }

  public String getLastRun() {
    return lastRun;
  }

  public void setLastRun( String lastRun ) {
    this.lastRun = lastRun;
  }
}
