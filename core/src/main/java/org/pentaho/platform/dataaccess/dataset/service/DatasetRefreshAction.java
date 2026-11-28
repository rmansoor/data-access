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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.action.IAction;

/**
 * Loads a file dataset's files again, for its schedule ({@link DatasetSchedules}). The scheduler runs
 * it as the schedule's owner, who must still be able to change the dataset. A failed load leaves the
 * dataset as it was, is recorded in its refresh history, and fails the job.
 */
public class DatasetRefreshAction implements IAction {
  private static final Log LOG = LogFactory.getLog( DatasetRefreshAction.class );
  private String datasetId;
  private boolean succeeded;

  /** Set by the scheduler from the job's parameter of the same name. */
  public void setDatasetId( String datasetId ) {
    this.datasetId = datasetId;
  }

  public String getDatasetId() {
    return datasetId;
  }

  @Override
  public void execute() throws Exception {
    succeeded = false;
    if ( datasetId == null || datasetId.isEmpty() ) {
      throw new IllegalStateException( "The schedule names no dataset" );
    }
    DatasetRefresh r = new PlatformDatasetServiceFactory().get().reload( datasetId, DatasetRefresh.Trigger.SCHEDULE );
    LOG.info( "Dataset " + datasetId + " reloaded on schedule: " + ( r == null ? "?" : r.getRows() ) + " rows" );
    succeeded = true;
  }

  @Override
  public boolean isExecutionSuccessful() {
    return succeeded;
  }
}
