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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DatasetFilesTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private Path root;
  private DatasetFiles files;

  @Before
  public void setUp() {
    root = folder.getRoot().toPath().resolve( "uploads" );
    files = new DatasetFiles( () -> root, 100 );
  }

  private static ByteArrayInputStream text( String s ) {
    return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
  }

  private long filesUnder( Path p ) throws Exception {
    if ( !Files.exists( p ) ) {
      return 0;
    }
    try ( Stream<Path> s = Files.walk( p ) ) {
      return s.filter( Files::isRegularFile ).count();
    }
  }

  @Test
  public void storesUnderANameTheServerChoosesInTheDatasetsFolder() throws Exception {
    String name = files.store( "orders", "C:\\Users\\suzy\\Q1 Orders.CSV", text( "a,b\n1,2\n" ) );
    assertTrue( name, name.matches( "orders/[0-9a-f-]{36}\\.csv" ) );
    Path p = files.locate( name ).orElseThrow();
    assertEquals( "a,b\n1,2\n", Files.readString( p ) );
    assertEquals( root.resolve( "orders" ), p.getParent() );
    assertTrue( files.store( "orders", "notes.tsv", text( "x" ) ).endsWith( ".txt" ) );
    assertTrue( files.store( "orders", null, text( "x" ) ).endsWith( ".csv" ) );
  }

  @Test
  public void refusesWhatIsNotACsvFileAndLeavesNothingBehind() throws Exception {
    refused( Code.UNSUPPORTED_FILE, "orders", "report.xlsx", text( "PK" ) );
    refused( Code.UNSUPPORTED_FILE, "orders", "a.csv", new ByteArrayInputStream( new byte[] { 'a', 0, 'b' } ) );
    refused( Code.UNSUPPORTED_FILE, "orders", "a.csv", text( "" ) );
    refused( Code.TOO_LARGE, "orders", "a.csv", text( "x".repeat( 101 ) ) );
    refused( Code.INVALID_REQUEST, "../etc", "a.csv", text( "x" ) );
    assertEquals( 0, filesUnder( root ) );
  }

  @Test
  public void acceptsUtf16TextWithItsByteOrderMark() {
    byte[] utf16 = "\uFEFFa,b\n".getBytes( StandardCharsets.UTF_16LE );
    assertTrue( files.store( "orders", "a.csv", new ByteArrayInputStream( utf16 ) ).startsWith( "orders/" ) );
  }

  @Test
  public void locatesOnlyStoredFileNames() throws Exception {
    String name = files.store( "orders", "a.csv", text( "x" ) );
    Files.writeString( folder.getRoot().toPath().resolve( "secret.csv" ), "s" );
    assertTrue( files.locate( name ).isPresent() );
    assertFalse( files.locate( "../secret.csv" ).isPresent() );
    assertFalse( files.locate( "orders/../../secret.csv" ).isPresent() );
    assertFalse( files.locate( "orders/a.csv" ).isPresent() );
    assertFalse( files.locate( "orders/00000000-0000-0000-0000-000000000000.csv" ).isPresent() );
    assertTrue( DatasetFiles.isStored( name ) );
    assertFalse( DatasetFiles.isStored( "upload.csv.tmp" ) );
  }

  @Test
  public void deletesOneFileOrADatasetsWholeFolder() throws Exception {
    String a = files.store( "orders", "a.csv", text( "x" ) );
    String b = files.store( "orders", "b.csv", text( "y" ) );
    files.delete( a );
    assertFalse( files.locate( a ).isPresent() );
    assertTrue( files.locate( b ).isPresent() );
    files.deleteAll( "orders" );
    assertFalse( Files.exists( root.resolve( "orders" ) ) );
    files.deleteAll( "../.." ); // ignored
    assertTrue( Files.exists( folder.getRoot().toPath() ) );
  }

  private void refused( Code code, String id, String name, ByteArrayInputStream body ) {
    try {
      files.store( id, name, body );
      fail( "stored " + name );
    } catch ( DatasetApiException e ) {
      assertEquals( e.getMessage(), code, e.getCode() );
    }
  }
}
