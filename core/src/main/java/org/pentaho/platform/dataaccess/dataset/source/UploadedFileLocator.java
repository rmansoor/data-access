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

import org.pentaho.platform.dataaccess.datasource.wizard.csv.CsvUtils;
import org.pentaho.platform.engine.core.system.PentahoSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Files uploaded through the data source wizard: names ending ".tmp" are in the upload staging
 * folder, others in {@code system/metadata/csvfiles} (or the folder set as
 * {@code file-upload-defaults/relative-path}), as {@link CsvUtils} has them today. Only bare file
 * names are accepted, so a definition can't reach any other file on the server.
 */
public class UploadedFileLocator implements FileLocator {

  @Override
  public Optional<Path> locate( String name ) {
    if ( !isPlainName( name ) ) {
      return Optional.empty();
    }
    String folder = name.endsWith( ".tmp" ) ? CsvUtils.TMP_FILE_PATH
      : PentahoSystem.getSystemSetting( "file-upload-defaults/relative-path", CsvUtils.DEFAULT_RELATIVE_UPLOAD_FILE_PATH );
    Path dir = Paths.get( PentahoSystem.getApplicationContext().getSolutionPath( folder ) ).normalize();
    Path file = dir.resolve( name ).normalize();
    return file.startsWith( dir ) && Files.isRegularFile( file ) ? Optional.of( file ) : Optional.empty();
  }

  /** "orders.csv", never "../x", "a/b" or "C:x". */
  static boolean isPlainName( String name ) {
    return name != null && !name.isEmpty() && name.length() <= 255 && !name.equals( "." ) && !name.equals( ".." )
      && name.chars().noneMatch( c -> c == '/' || c == '\\' || c == ':' || c == 0 || Character.isISOControl( c ) );
  }
}
