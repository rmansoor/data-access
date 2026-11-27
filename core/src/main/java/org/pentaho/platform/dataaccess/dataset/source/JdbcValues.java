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

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Maps JDBC types to dataset column types, and reads result rows as JSON-ready values. */
final class JdbcValues {

  private JdbcValues() {
  }

  static ColumnType columnType( int sqlType, int scale ) {
    switch ( sqlType ) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
        return ColumnType.INTEGER;
      case Types.NUMERIC:
      case Types.DECIMAL:
        // NUMBER(10) and DECIMAL(10,0) hold whole numbers. Oracle reports a bare NUMBER as scale -127: a decimal.
        return scale == 0 ? ColumnType.INTEGER : ColumnType.DECIMAL;
      case Types.REAL:
      case Types.FLOAT:
      case Types.DOUBLE:
        return ColumnType.DECIMAL;
      case Types.BIT:
      case Types.BOOLEAN:
        return ColumnType.BOOLEAN;
      case Types.DATE:
        return ColumnType.DATE;
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
      case Types.TIME:
      case Types.TIME_WITH_TIMEZONE:
        return ColumnType.TIMESTAMP;
      default:
        return ColumnType.STRING;
    }
  }

  static List<SourceColumn> columns( ResultSetMetaData md, String table ) throws SQLException {
    List<SourceColumn> columns = new ArrayList<>();
    for ( int i = 1; i <= md.getColumnCount(); i++ ) {
      int nullable = md.isNullable( i );
      columns.add( new SourceColumn( label( md, i ), columnType( md.getColumnType( i ), md.getScale( i ) ),
        md.getColumnTypeName( i ),
        nullable == ResultSetMetaData.columnNullableUnknown ? null : nullable == ResultSetMetaData.columnNullable,
        table ) );
    }
    return columns;
  }

  /** Up to {@code limit} rows; the result says whether more were there. */
  static Sample read( ResultSet rs, List<SourceColumn> columns, int limit ) throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    boolean more = false;
    while ( rs.next() ) {
      if ( rows.size() == limit ) {
        more = true;
        break;
      }
      List<Object> row = new ArrayList<>( columns.size() );
      for ( int i = 0; i < columns.size(); i++ ) {
        row.add( value( rs, i + 1, columns.get( i ).getType() ) );
      }
      rows.add( row );
    }
    return new Sample( columns, rows, more );
  }

  private static Object value( ResultSet rs, int i, ColumnType type ) throws SQLException {
    Object v;
    switch ( type ) {
      case INTEGER:
        BigDecimal whole = rs.getBigDecimal( i );
        v = whole == null ? null : whole( whole );
        break;
      case DECIMAL:
        v = rs.getBigDecimal( i );
        break;
      case BOOLEAN:
        v = rs.getBoolean( i );
        break;
      case DATE:
        java.sql.Date d = rs.getDate( i );
        v = d == null ? null : d.toLocalDate().toString();
        break;
      case TIMESTAMP:
        Timestamp t = rs.getTimestamp( i );
        v = t == null ? null : t.toLocalDateTime().toString();
        break;
      default:
        v = rs.getString( i );
    }
    return rs.wasNull() ? null : v;
  }

  /** A Long when it fits, else the exact BigDecimal. */
  private static Object whole( BigDecimal b ) {
    try {
      return b.longValueExact();
    } catch ( ArithmeticException tooBigOrFractional ) {
      return b;
    }
  }

  private static String label( ResultSetMetaData md, int i ) throws SQLException {
    String label = md.getColumnLabel( i );
    return label == null || label.isEmpty() ? md.getColumnName( i ) : label;
  }
}
