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

/** How hard a source may be pushed while designing a dataset. */
public final class SourceLimits {

  /** The most rows a sample may ask for. */
  public static final int MAX_SAMPLE_ROWS = 1000;
  /** Rows sampled when the caller doesn't say. */
  public static final int DEFAULT_SAMPLE_ROWS = 100;

  private final int queryTimeoutSeconds;
  private final int typeDetectionRows;

  /** 30-second queries; types detected from the first 100 file rows, as the wizard does today. */
  public static SourceLimits standard() {
    return new SourceLimits( 30, 100 );
  }

  public SourceLimits( int queryTimeoutSeconds, int typeDetectionRows ) {
    this.queryTimeoutSeconds = queryTimeoutSeconds;
    this.typeDetectionRows = typeDetectionRows;
  }

  public int getQueryTimeoutSeconds() {
    return queryTimeoutSeconds;
  }

  public int getTypeDetectionRows() {
    return typeDetectionRows;
  }
}
