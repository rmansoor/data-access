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

package org.pentaho.platform.dataaccess.datasource.wizard.csv;

import org.junit.Test;
import org.pentaho.metadata.model.concept.types.DataType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.source.CsvSourceAdapter;
import org.pentaho.platform.dataaccess.dataset.source.SourceColumn;
import org.pentaho.platform.dataaccess.dataset.source.SourceLimits;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.datasource.wizard.models.ColumnInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.models.CsvParseException;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The datasets CSV adapter understands the wizard's own test files as the wizard does (CWBA-62):
 * the same columns, names and types. Here because the wizard's reader is package-private.
 */
public class CsvSourceAdapterParityTest {

  private static final String[] FILES = {
    "src/test/resources/example.csv",
    "src/test/resources/test1.csv",
    "src/test/resources/solution1/system/metadata/csvfiles/countries.csv",
    "src/test/resources/solution1/system/metadata/csvfiles/unit_test.csv",
    "src/test/resources/solution1/system/metadata/csvfiles/testfile.csv",
  };

  @Test
  public void detectsTheSameColumnsAsTheWizard() throws Exception {
    List<String> compared = new ArrayList<>();
    List<String> refused = new ArrayList<>();
    for ( String name : FILES ) {
      File file = new File( name );
      assertTrue( name, file.isFile() );
      for ( int header = 0; header <= 1; header++ ) {
        ColumnInfo[] wizard;
        try {
          wizard = new CsvUtils().generateFields( "parity", file.getAbsolutePath(), file.getName(), 100, ",", "\"",
            header, false, true, "UTF-8" ).getColumns();
        } catch ( CsvParseException e ) {
          // A file the wizard can't read must not be read differently by the adapter.
          try {
            adapter( file.toPath() ).schema( source( file.getName(), header == 1 ) );
            fail( name + ": the wizard can't read it, but the adapter did" );
          } catch ( DatasetApiException expected ) {
            assertEquals( Code.SOURCE_FAILED, expected.getCode() );
          }
          refused.add( name );
          continue;
        }
        List<SourceColumn> ours = adapter( file.toPath() ).schema( source( file.getName(), header == 1 ) );

        assertEquals( name + " columns", wizard.length, ours.size() );
        for ( int i = 0; i < wizard.length; i++ ) {
          String where = name + " header=" + header + " column " + i;
          assertEquals( where, wizard[ i ].getTitle(), ours.get( i ).getName() );
          assertEquals( where, wizard[ i ].getDataType(), coarse( ours.get( i ).getType() ) );
        }
        compared.add( name );
      }
    }
    assertEquals( FILES.length * 2, compared.size() + refused.size() );
    assertTrue( "some files are compared column by column: " + compared, !compared.isEmpty() );
  }

  private static CsvSourceAdapter adapter( Path file ) {
    return new CsvSourceAdapter( n -> Optional.of( file ), SourceLimits.standard() );
  }

  private static Source source( String name, boolean header ) {
    FileSource f = new FileSource();
    f.setPath( name );
    f.setHeaderRow( header );
    f.setDelimiter( "," );
    f.setEnclosure( "\"" );
    f.setEncoding( "UTF-8" );
    Source s = new Source();
    s.setType( SourceType.FILE );
    s.setFile( f );
    return s;
  }

  /** The wizard's types are coarser: whole and decimal numbers are both NUMERIC, dates and timestamps both DATE. */
  private static DataType coarse( ColumnType t ) {
    switch ( t ) {
      case INTEGER:
      case DECIMAL:
        return DataType.NUMERIC;
      case DATE:
      case TIMESTAMP:
        return DataType.DATE;
      case BOOLEAN:
        return DataType.BOOLEAN;
      default:
        return DataType.STRING;
    }
  }
}
