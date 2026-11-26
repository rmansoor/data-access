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

package org.pentaho.platform.dataaccess.dataset;

/**
 * A stored or submitted definition could not be read: malformed JSON, an unknown field, a value of
 * the wrong type, or a version this release can't open. {@link #getPath()} names the field at fault
 * ("columns[2].type"), so callers can point people at it.
 */
public class DatasetFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String path;

  public DatasetFormatException( String path, String message ) {
    this( path, message, null );
  }

  public DatasetFormatException( String path, String message, Throwable cause ) {
    super( ( path == null || path.isEmpty() ? "" : path + ": " ) + message, cause );
    this.path = path == null ? "" : path;
  }

  /** The field at fault, as a JSON path ("source.sql", "columns[0].name"); empty for the whole document. */
  public String getPath() {
    return path;
  }
}
