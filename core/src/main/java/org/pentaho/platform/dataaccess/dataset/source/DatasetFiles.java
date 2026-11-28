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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Files uploaded to datasets, one folder per dataset under a root outside the web application's
 * public paths ({@code system/data-access/uploads}). The server names each file; a stored file is
 * addressed as {@code <dataset id>/<uuid>.<ext>}, which is what {@code source.file.path} holds and
 * the only shape {@link #locate} accepts, so nothing a client sends can reach another path.
 * <p>
 * Uploads are streamed to a temporary file, checked (size, and that it is text), then moved into
 * place, so a failed upload leaves nothing behind.
 */
public class DatasetFiles implements FileLocator {

  private static final Log LOG = LogFactory.getLog( DatasetFiles.class );
  /** What {@code source.file.path} holds for an uploaded file. */
  static final Pattern STORED = Pattern.compile( "([a-z0-9][a-z0-9-]{0,59})/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.(csv|txt|xlsx|parquet)" );
  private static final int SNIFF_BYTES = 8192;

  private final Supplier<Path> root;
  private final long maxBytes;

  /**
   * @param root the uploads folder; created when first needed
   * @param maxBytes the largest file accepted
   */
  public DatasetFiles( Supplier<Path> root, long maxBytes ) {
    this.root = root;
    this.maxBytes = maxBytes;
  }

  public long getMaxBytes() {
    return maxBytes;
  }

  /**
   * Stores an upload for a dataset.
   *
   * @param originalName the file's name on the uploader's computer; only its extension is used
   * @return the stored file's address, for {@code source.file.path}
   * @throws DatasetApiException TOO_LARGE, UNSUPPORTED_FILE, or INTERNAL when it can't be written
   */
  public String store( String datasetId, String originalName, InputStream body ) {
    String ext = extension( originalName );
    if ( ext == null ) {
      boolean oldExcel = originalName != null && originalName.toLowerCase( java.util.Locale.ROOT ).trim().endsWith( ".xls" );
      throw new DatasetApiException( Code.UNSUPPORTED_FILE, "file", oldExcel
        ? "\"" + originalName + "\" is an old-style Excel file (.xls): save it as an Excel Workbook (.xlsx) and upload that"
        : "Only CSV files (.csv or .txt), Excel workbooks (.xlsx) and Parquet files (.parquet) can be uploaded"
          + ( originalName == null ? "" : ", not \"" + originalName + "\"" ), null, null );
    }
    String name = datasetId + "/" + UUID.randomUUID() + "." + ext;
    if ( !STORED.matcher( name ).matches() ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "id", "is not a dataset id files can be stored under", null, null );
    }
    Path folder = root.get().resolve( datasetId );
    Path temp = null;
    try {
      Files.createDirectories( folder );
      temp = Files.createTempFile( folder, ".upload-", ".part" );
      long size = copy( body, temp );
      if ( size == 0 ) {
        throw new DatasetApiException( Code.UNSUPPORTED_FILE, "file", "The file is empty", null, null );
      }
      boolean fits = "xlsx".equals( ext ) ? looksLikeZip( temp ) : "parquet".equals( ext ) ? looksLikeParquet( temp )
        : looksLikeText( temp );
      if ( !fits ) {
        throw new DatasetApiException( Code.UNSUPPORTED_FILE, "file", "xlsx".equals( ext ) ? "The file isn't an Excel workbook (.xlsx)"
          : "parquet".equals( ext ) ? "The file isn't a Parquet file" : "The file isn't text, so it can't be read as CSV", null, null );
      }
      Files.move( temp, root.get().resolve( name ), StandardCopyOption.ATOMIC_MOVE );
      temp = null;
      return name;
    } catch ( IOException e ) {
      throw new DatasetApiException( Code.INTERNAL, "", "The file could not be stored", null, e );
    } finally {
      if ( temp != null ) {
        try {
          Files.deleteIfExists( temp );
        } catch ( IOException e ) {
          LOG.warn( "Could not remove the partial upload " + temp, e );
        }
      }
    }
  }

  @Override
  public Optional<Path> locate( String name ) {
    if ( name == null || !STORED.matcher( name ).matches() ) {
      return Optional.empty();
    }
    Path p = root.get().resolve( name );
    return Files.isRegularFile( p ) ? Optional.of( p ) : Optional.empty();
  }

  /** Whether {@code path} names a file this store keeps (as opposed to a legacy upload). */
  public static boolean isStored( String path ) {
    return path != null && STORED.matcher( path ).matches();
  }

  /** Deletes one stored file; logs rather than fails, since nothing points at it any more. */
  public void delete( String name ) {
    locate( name ).ifPresent( p -> {
      try {
        Files.deleteIfExists( p );
      } catch ( IOException e ) {
        LOG.warn( "Could not delete the uploaded file " + p, e );
      }
    } );
  }

  /** Deletes every file of a deleted dataset, and its folder. */
  public void deleteAll( String datasetId ) {
    if ( datasetId == null || !datasetId.matches( "[a-z0-9][a-z0-9-]{0,59}" ) ) {
      return;
    }
    Path folder = root.get().resolve( datasetId );
    if ( !Files.isDirectory( folder ) ) {
      return;
    }
    try ( DirectoryStream<Path> files = Files.newDirectoryStream( folder ) ) {
      for ( Path f : files ) {
        Files.deleteIfExists( f );
      }
      Files.deleteIfExists( folder );
    } catch ( IOException e ) {
      LOG.warn( "Could not delete the uploads of dataset " + datasetId, e );
    }
  }

  // ------------------------------------------------------------------------------------------------

  private long copy( InputStream in, Path target ) throws IOException {
    byte[] buffer = new byte[ 64 * 1024 ];
    long total = 0;
    try ( OutputStream out = Files.newOutputStream( target ) ) {
      for ( int n = in.read( buffer ); n >= 0; n = in.read( buffer ) ) {
        total += n;
        if ( total > maxBytes ) {
          throw new DatasetApiException( Code.TOO_LARGE, "file",
            "The file is larger than " + ( maxBytes / ( 1024 * 1024 ) ) + " MB, the most this server accepts", null, null );
        }
        out.write( buffer, 0, n );
      }
    }
    return total;
  }

  /** A Parquet file starts (and ends) with "PAR1". */
  static boolean looksLikeParquet( Path file ) throws IOException {
    byte[] head;
    try ( InputStream in = Files.newInputStream( file ) ) {
      head = in.readNBytes( 4 );
    }
    return new String( head, java.nio.charset.StandardCharsets.US_ASCII ).equals( "PAR1" );
  }

  /** An Office Open XML file (.xlsx) is a zip archive: it starts with the zip signature. */
  static boolean looksLikeZip( Path file ) throws IOException {
    byte[] head;
    try ( InputStream in = Files.newInputStream( file ) ) {
      head = in.readNBytes( 4 );
    }
    return head.length == 4 && head[ 0 ] == 'P' && head[ 1 ] == 'K' && head[ 2 ] == 3 && head[ 3 ] == 4;
  }

  /** No NUL bytes in the first 8 KB, unless it starts with a UTF-16 byte order mark (UTF-16 text is full of them). */
  static boolean looksLikeText( Path file ) throws IOException {
    byte[] head;
    try ( InputStream in = Files.newInputStream( file ) ) {
      head = in.readNBytes( SNIFF_BYTES );
    }
    if ( head.length >= 2 && ( ( head[ 0 ] == (byte) 0xFE && head[ 1 ] == (byte) 0xFF )
      || ( head[ 0 ] == (byte) 0xFF && head[ 1 ] == (byte) 0xFE ) ) ) {
      return true;
    }
    for ( byte b : head ) {
      if ( b == 0 ) {
        return false;
      }
    }
    return true;
  }

  private static String extension( String originalName ) {
    if ( originalName == null ) {
      return "csv";
    }
    String n = originalName.toLowerCase( java.util.Locale.ROOT ).trim();
    if ( n.endsWith( ".csv" ) ) {
      return "csv";
    }
    if ( n.endsWith( ".txt" ) || n.endsWith( ".tsv" ) ) {
      return "txt";
    }
    if ( n.endsWith( ".xlsx" ) ) {
      return "xlsx";
    }
    if ( n.endsWith( ".parquet" ) ) {
      return "parquet";
    }
    return n.contains( "." ) ? null : "csv";
  }
}
