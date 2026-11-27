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

import java.nio.file.Path;
import java.util.Optional;

/** Finds an uploaded file by the name a dataset's file source gives it. */
@FunctionalInterface
public interface FileLocator {

  /** The file, if a file by that name was uploaded; never a path outside the upload folders. */
  Optional<Path> locate( String name );
}
