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
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CsvSourceAdapterTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  private CsvSourceAdapter csv;

  @Before
  public void setUp() {
    csv = new CsvSourceAdapter( name -> {
      Path p = folder.getRoot().toPath().resolve( name );
      return Files.exists( p ) ? Optional.of( p ) : Optional.empty();
    }, SourceLimits.standard() );
  }

  private Source file( String name, String text, Charset charset, boolean header ) throws Exception {
    Files.write( folder.getRoot().toPath().resolve( name ), text.getBytes( charset ) );
    FileSource f = new FileSource();
    f.setPath( name );
    f.setHeaderRow( header );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setFile( f );
    return s;
  }

  @Test
  public void detectsColumnTypesAndParsesValues() throws Exception {
    Source s = file( "orders.csv", "id,amount,placed,shipped,customer\n"
      + "1,10.50,01/31/2026,true,Acme\n2,99.99,02/01/2026,false,\"Smith, Jones\"\n3,0.01,02/02/2026,true,Zed\n",
      StandardCharsets.UTF_8, true );
    Sample sample = csv.sample( s, 2, null );
    assertEquals( "[id:INTEGER, amount:DECIMAL, placed:DATE, shipped:BOOLEAN, customer:STRING]", sample.getColumns().toString() );
    assertTrue( sample.isTruncated() );
    List<Object> second = sample.getRows().get( 1 );
    assertEquals( 2L, second.get( 0 ) );
    assertEquals( new BigDecimal( "99.99" ), second.get( 1 ) );
    assertEquals( "2026-02-01", second.get( 2 ) );
    assertEquals( Boolean.FALSE, second.get( 3 ) );
    assertEquals( "Smith, Jones", second.get( 4 ) );
  }

  @Test
  public void namesColumnsWithoutAHeaderAsTheWizardDoes() throws Exception {
    Sample sample = csv.sample( file( "plain.csv", "a,1\nb,2\n", StandardCharsets.UTF_8, false ), 10, null );
    assertEquals( "[Field_001:STRING, Field_002:INTEGER]", sample.getColumns().toString() );
    assertEquals( 2, sample.getRows().size() );
    assertFalse( sample.isTruncated() );
  }

  @Test
  public void guessesTheDelimiterAndSkipsAByteOrderMark() throws Exception {
    Sample sample = csv.sample( file( "semi.csv", "﻿name;qty\nx;1\ny;2\n", StandardCharsets.UTF_8, true ), 10, null );
    assertEquals( "[name:STRING, qty:INTEGER]", sample.getColumns().toString() );
  }

  @Test
  public void readsTheEncodingItIsGivenOrDetects() throws Exception {
    Source s = file( "latin.csv", "Stadt,Umsatz\nMünchen,1\nKöln,2\n", Charset.forName( "ISO-8859-1" ), true );
    s.getFile().setEncoding( "ISO-8859-1" );
    assertEquals( "München", csv.sample( s, 10, null ).getRows().get( 0 ).get( 0 ) );
    s.getFile().setEncoding( "klingon" );
    expect( Code.VALIDATION_FAILED, "source.file.encoding", () -> csv.sample( s, 10, null ) );
  }

  @Test
  public void namesWhatIsMissing() throws Exception {
    Source s = file( "x.csv", "a\n1\n", StandardCharsets.UTF_8, true );
    s.getFile().setPath( "nope.csv" );
    expect( Code.VALIDATION_FAILED, "source.file.path", () -> csv.sample( s, 10, null ) );
    s.setFile( null );
    expect( Code.VALIDATION_FAILED, "source.file", () -> csv.schema( s ) );
  }

  @Test
  public void acceptsOnlyPlainUploadedFileNames() {
    assertTrue( UploadedFileLocator.isPlainName( "orders 2026.csv" ) );
    for ( String bad : new String[] { "../etc/passwd", "a/b.csv", "a\\b.csv", "C:x.csv", "..", "", null } ) {
      assertFalse( bad, UploadedFileLocator.isPlainName( bad ) );
    }
  }

  private static void expect( Code code, String path, Runnable call ) {
    try {
      call.run();
      fail( "expected " + code );
    } catch ( DatasetApiException e ) {
      assertEquals( e.getMessage(), code, e.getCode() );
      assertEquals( e.getMessage(), path, e.getPath() );
    }
  }
}
