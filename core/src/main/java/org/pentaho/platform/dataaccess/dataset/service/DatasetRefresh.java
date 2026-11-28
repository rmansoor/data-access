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

import java.util.ArrayList;
import java.util.List;

/** One load of a file dataset: what started it, what it read, and how it ended. */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonPropertyOrder( { "id", "mode", "trigger", "user", "file", "started", "finished", "state", "rows", "error", "warnings",
  "republished" } )
public class DatasetRefresh {

  /** LOAD reads the dataset's files again; REPLACE loads a new file instead; APPEND adds one after them. */
  public enum Mode { LOAD, REPLACE, APPEND }

  /** Who started it: someone in the console or API, or a schedule. */
  public enum Trigger { USER, SCHEDULE }

  public enum State { SUCCEEDED, FAILED }

  private String id;
  private Mode mode;
  private Trigger trigger;
  private String user;
  /** The new file's name (REPLACE, APPEND). */
  private String file;
  /** ISO-8601 instants. */
  private String started;
  private String finished;
  private State state;
  private Long rows;
  private String error;
  private List<String> warnings = new ArrayList<>();
  /** Whether the published domain and catalog were brought up to date. */
  private Boolean republished;

  public String getId() {
    return id;
  }

  public void setId( String id ) {
    this.id = id;
  }

  public Mode getMode() {
    return mode;
  }

  public void setMode( Mode mode ) {
    this.mode = mode;
  }

  public Trigger getTrigger() {
    return trigger;
  }

  public void setTrigger( Trigger trigger ) {
    this.trigger = trigger;
  }

  public String getUser() {
    return user;
  }

  public void setUser( String user ) {
    this.user = user;
  }

  public String getFile() {
    return file;
  }

  public void setFile( String file ) {
    this.file = file;
  }

  public String getStarted() {
    return started;
  }

  public void setStarted( String started ) {
    this.started = started;
  }

  public String getFinished() {
    return finished;
  }

  public void setFinished( String finished ) {
    this.finished = finished;
  }

  public State getState() {
    return state;
  }

  public void setState( State state ) {
    this.state = state;
  }

  public Long getRows() {
    return rows;
  }

  public void setRows( Long rows ) {
    this.rows = rows;
  }

  public String getError() {
    return error;
  }

  public void setError( String error ) {
    this.error = error;
  }

  @JsonInclude( JsonInclude.Include.NON_EMPTY )
  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings( List<String> warnings ) {
    this.warnings = warnings == null ? new ArrayList<>() : warnings;
  }

  public Boolean getRepublished() {
    return republished;
  }

  public void setRepublished( Boolean republished ) {
    this.republished = republished;
  }
}
