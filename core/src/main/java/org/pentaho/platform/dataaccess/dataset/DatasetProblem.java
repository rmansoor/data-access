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

import java.util.Objects;

/** One thing wrong with a definition: the field at fault, as a JSON path, and what is wrong in plain words. */
public final class DatasetProblem {

  private final String path;
  private final String message;

  public DatasetProblem( String path, String message ) {
    this.path = path;
    this.message = message;
  }

  /** "source.connection", "columns[1].name", "model.measures[0].column". */
  public String getPath() {
    return path;
  }

  public String getMessage() {
    return message;
  }

  @Override
  public boolean equals( Object o ) {
    if ( this == o ) {
      return true;
    }
    if ( !( o instanceof DatasetProblem ) ) {
      return false;
    }
    DatasetProblem other = (DatasetProblem) o;
    return path.equals( other.path ) && message.equals( other.message );
  }

  @Override
  public int hashCode() {
    return Objects.hash( path, message );
  }

  @Override
  public String toString() {
    return path + ": " + message;
  }
}
