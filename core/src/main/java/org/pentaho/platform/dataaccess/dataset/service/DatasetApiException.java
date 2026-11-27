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

import org.pentaho.platform.dataaccess.dataset.DatasetProblem;

import java.util.Collections;
import java.util.List;

/**
 * Why a dataset request failed, as a stable machine-readable {@link Code} (which the REST layer maps
 * to an HTTP status), the field at fault when there is one, and any validation problems.
 */
public class DatasetApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Stable error codes; clients switch on these, so never rename one. */
  public enum Code {
    /** The request body is not a readable definition (400). */
    INVALID_DEFINITION( 400 ),
    /** Any other request body or parameter that can't be read, such as sharing (400). */
    INVALID_REQUEST( 400 ),
    /** The definition is readable but incomplete or inconsistent (422). */
    VALIDATION_FAILED( 422 ),
    /** The dataset's source refused or failed: a SQL error, a timeout, an unreadable file (422). */
    SOURCE_FAILED( 422 ),
    /** The caller may not do this (403). */
    FORBIDDEN( 403 ),
    NOT_FOUND( 404 ),
    /** A dataset with this id already exists (409). */
    ALREADY_EXISTS( 409 ),
    /** The dataset changed since the caller read it (412). */
    STALE_VERSION( 412 ),
    /** Updates and deletes must say which version they expect, with If-Match (428). */
    VERSION_REQUIRED( 428 ),
    /** An uploaded file is larger than the server accepts (413). */
    TOO_LARGE( 413 ),
    /** An uploaded file isn't a kind the server reads, such as a binary file sent as CSV (415). */
    UNSUPPORTED_FILE( 415 ),
    /** Planned but not built yet (501). */
    NOT_IMPLEMENTED( 501 ),
    /** Anything else; details are logged, not returned (500). */
    INTERNAL( 500 );

    private final int status;

    Code( int status ) {
      this.status = status;
    }

    public int getStatus() {
      return status;
    }

    /** "STALE_VERSION" -> "stale-version", the form used in JSON. */
    public String wire() {
      return name().toLowerCase( java.util.Locale.ROOT ).replace( '_', '-' );
    }
  }

  private final Code code;
  private final String path;
  /** Not serialized: problems describe one request and are rebuilt on retry. */
  private final transient List<DatasetProblem> problems;

  public DatasetApiException( Code code, String message ) {
    this( code, "", message, Collections.emptyList(), null );
  }

  public DatasetApiException( Code code, String path, String message, List<DatasetProblem> problems, Throwable cause ) {
    super( message, cause );
    this.code = code;
    this.path = path == null ? "" : path;
    this.problems = problems == null ? Collections.emptyList() : Collections.unmodifiableList( problems );
  }

  public Code getCode() {
    return code;
  }

  /** The field at fault ("columns[1].name"), or empty. */
  public String getPath() {
    return path;
  }

  public List<DatasetProblem> getProblems() {
    return problems;
  }
}
