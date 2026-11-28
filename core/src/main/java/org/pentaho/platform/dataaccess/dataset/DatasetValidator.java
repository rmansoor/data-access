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

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Aggregation;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Column;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Dimension;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Measure;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Model;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Refresh;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Relationship;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.RowRule;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;

import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a definition is complete and consistent before it is saved or published: required
 * fields, unique names, and that the model, relationships and security only refer to columns and
 * tables that exist. Every problem names its field as a JSON path, so the console can show it next
 * to that field. Checks that need a database or a file (does the table exist, does the SQL parse)
 * belong to the source adapters, not here.
 */
public final class DatasetValidator {

  /** Repository-safe ids: letters, digits, dot, dash and underscore; starts with a letter or digit. */
  static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}";
  static final int MAX_NAME = 200;

  private static final Set<ColumnType> NUMERIC = EnumSet.of( ColumnType.INTEGER, ColumnType.DECIMAL );
  private static final Set<Aggregation> ANY_TYPE = EnumSet.of( Aggregation.COUNT, Aggregation.COUNT_DISTINCT );

  /** Every problem found; empty when the definition is valid. */
  public List<DatasetProblem> validate( DatasetDefinition d ) {
    List<DatasetProblem> problems = new ArrayList<>();
    if ( d == null ) {
      problems.add( new DatasetProblem( "", "is missing" ) );
      return problems;
    }
    Run run = new Run( problems );

    if ( blank( d.getId() ) ) {
      run.add( "id", "is required" );
    } else if ( !d.getId().matches( ID_PATTERN ) ) {
      run.add( "id", "may use letters, digits, '.', '-' and '_', start with a letter or digit, and be at most 100 characters" );
    }
    if ( blank( d.getName() ) ) {
      run.add( "name", "is required" );
    } else if ( d.getName().length() > MAX_NAME ) {
      run.add( "name", "must be at most " + MAX_NAME + " characters" );
    }

    Set<String> tables = source( d.getSource(), run );
    Map<String, Column> columns = columns( d, tables, run );
    model( d.getModel(), columns, run );
    excludedColumns( d, tables, columns, run );
    relationships( d, tables, run );
    security( d, columns, run );
    refresh( d.getRefresh(), run );
    return problems;
  }

  /** @return the source's tables (empty for SQL and FILE) */
  private static Set<String> source( Source s, Run run ) {
    if ( s == null ) {
      run.add( "source", "is required" );
      return Collections.emptySet();
    }
    if ( s.getType() == null ) {
      run.add( "source.type", "is required" );
      return Collections.emptySet();
    }
    Set<String> tables = new HashSet<>();
    switch ( s.getType() ) {
      case TABLES:
        requireConnection( s, run );
        if ( s.getTables().isEmpty() ) {
          run.add( "source.tables", "needs at least one table" );
        }
        for ( int i = 0; i < s.getTables().size(); i++ ) {
          String table = s.getTables().get( i );
          if ( blank( table ) ) {
            run.add( "source.tables[" + i + "]", "is empty" );
          } else if ( !tables.add( table ) ) {
            run.add( "source.tables[" + i + "]", "\"" + table + "\" is listed twice" );
          }
        }
        notAllowed( s.getSql() != null, "source.sql", "TABLES", run );
        notAllowed( s.getFile() != null, "source.file", "TABLES", run );
        break;
      case SQL:
        requireConnection( s, run );
        if ( blank( s.getSql() ) ) {
          run.add( "source.sql", "is required" );
        }
        notAllowed( !s.getTables().isEmpty(), "source.tables", "SQL", run );
        notAllowed( s.getFile() != null, "source.file", "SQL", run );
        break;
      case FILE:
        file( s.getFile(), run );
        notAllowed( !s.getTables().isEmpty(), "source.tables", "FILE", run );
        notAllowed( s.getSql() != null, "source.sql", "FILE", run );
        break;
      default:
        break;
    }
    return tables;
  }

  private static void requireConnection( Source s, Run run ) {
    if ( blank( s.getConnection() ) ) {
      run.add( "source.connection", "is required" );
    }
  }

  private static void notAllowed( boolean present, String path, String type, Run run ) {
    if ( present ) {
      run.add( path, "is not used by a " + type + " source; remove it" );
    }
  }

  private static void file( FileSource f, Run run ) {
    if ( f == null ) {
      run.add( "source.file", "is required" );
      return;
    }
    if ( f.getFormat() == null ) {
      run.add( "source.file.format", "is required" );
    }
    if ( blank( f.getPath() ) ) {
      run.add( "source.file.path", "is required" );
    }
    if ( f.getEncoding() != null ) {
      try {
        if ( !Charset.isSupported( f.getEncoding() ) ) {
          run.add( "source.file.encoding", "\"" + f.getEncoding() + "\" is not an encoding this server supports" );
        }
      } catch ( IllegalArgumentException e ) {
        run.add( "source.file.encoding", "\"" + f.getEncoding() + "\" is not a valid encoding name" );
      }
    }
    if ( f.getDelimiter() != null && f.getDelimiter().length() != 1 ) {
      run.add( "source.file.delimiter", "must be a single character" );
    }
    if ( f.getEnclosure() != null && f.getEnclosure().length() > 1 ) {
      run.add( "source.file.enclosure", "must be a single character or empty" );
    }
  }

  /** @return columns by name (first occurrence wins) */
  private static Map<String, Column> columns( DatasetDefinition d, Set<String> tables, Run run ) {
    Map<String, Column> byName = new HashMap<>();
    Set<String> seen = new HashSet<>();
    if ( d.getColumns().isEmpty() ) {
      run.add( "columns", "needs at least one column" );
    }
    boolean several = tables.size() > 1;
    for ( int i = 0; i < d.getColumns().size(); i++ ) {
      Column c = d.getColumns().get( i );
      String at = "columns[" + i + "]";
      if ( c == null ) {
        run.add( at, "is empty" );
        continue;
      }
      if ( blank( c.getName() ) ) {
        run.add( at + ".name", "is required" );
      } else if ( !seen.add( c.getName().toLowerCase( Locale.ROOT ) ) ) {
        run.add( at + ".name", "\"" + c.getName() + "\" is used by another column" );
      } else {
        byName.put( c.getName(), c );
      }
      if ( c.getType() == null ) {
        run.add( at + ".type", "is required" );
      }
      if ( c.getTable() != null && !tables.contains( c.getTable() ) ) {
        run.add( at + ".table", "\"" + c.getTable() + "\" is not one of the source's tables" );
      } else if ( several && c.getTable() == null ) {
        run.add( at + ".table", "is required when the source has more than one table" );
      }
    }
    return byName;
  }

  private static void model( Model m, Map<String, Column> columns, Run run ) {
    if ( m == null ) {
      return;
    }
    Set<String> names = new HashSet<>();
    for ( int i = 0; i < m.getDimensions().size(); i++ ) {
      Dimension dim = m.getDimensions().get( i );
      String at = "model.dimensions[" + i + "]";
      if ( dim == null ) {
        run.add( at, "is empty" );
        continue;
      }
      uniqueName( dim.getName(), at, names, run );
      if ( dim.getLevels().isEmpty() ) {
        run.add( at + ".levels", "needs at least one level" );
      }
      for ( int j = 0; j < dim.getLevels().size(); j++ ) {
        knownColumn( dim.getLevels().get( j ), at + ".levels[" + j + "]", columns, run );
      }
    }
    for ( int i = 0; i < m.getMeasures().size(); i++ ) {
      Measure measure = m.getMeasures().get( i );
      String at = "model.measures[" + i + "]";
      if ( measure == null ) {
        run.add( at, "is empty" );
        continue;
      }
      uniqueName( measure.getName(), at, names, run );
      Column column = knownColumn( measure.getColumn(), at + ".column", columns, run );
      if ( measure.getAggregation() == null ) {
        run.add( at + ".aggregation", "is required" );
      } else if ( column != null && column.getType() != null && !ANY_TYPE.contains( measure.getAggregation() )
        && !NUMERIC.contains( column.getType() ) ) {
        run.add( at + ".aggregation", measure.getAggregation() + " needs a number column; \"" + column.getName()
          + "\" is " + column.getType() + " (use COUNT or COUNT_DISTINCT)" );
      }
    }
  }

  private static void uniqueName( String name, String at, Set<String> names, Run run ) {
    if ( blank( name ) ) {
      run.add( at + ".name", "is required" );
    } else if ( !names.add( name.toLowerCase( Locale.ROOT ) ) ) {
      run.add( at + ".name", "\"" + name + "\" is used by another dimension or measure" );
    }
  }

  private static Column knownColumn( String name, String at, Map<String, Column> columns, Run run ) {
    if ( blank( name ) ) {
      run.add( at, "is required" );
      return null;
    }
    Column column = columns.get( name );
    if ( column == null ) {
      run.add( at, "\"" + name + "\" is not a column of this dataset" );
    }
    return column;
  }

  private static void relationships( DatasetDefinition d, Set<String> tables, Run run ) {
    List<Relationship> rels = d.getRelationships();
    if ( rels.isEmpty() ) {
      return;
    }
    Source s = d.getSource();
    if ( s == null || s.getType() != SourceType.TABLES ) {
      run.add( "relationships", "are only used by a TABLES source" );
      return;
    }
    for ( int i = 0; i < rels.size(); i++ ) {
      Relationship r = rels.get( i );
      String at = "relationships[" + i + "]";
      if ( r == null ) {
        run.add( at, "is empty" );
        continue;
      }
      knownTable( r.getFromTable(), at + ".fromTable", tables, run );
      knownTable( r.getToTable(), at + ".toTable", tables, run );
      if ( blank( r.getFromColumn() ) ) {
        run.add( at + ".fromColumn", "is required" );
      }
      if ( blank( r.getToColumn() ) ) {
        run.add( at + ".toColumn", "is required" );
      }
      if ( r.getFromTable() != null && r.getFromTable().equals( r.getToTable() ) ) {
        run.add( at + ".toTable", "must be a different table from fromTable" );
      }
      if ( r.getJoinType() == null ) {
        run.add( at + ".joinType", "is required" );
      }
    }
  }

  private static void knownTable( String table, String at, Set<String> tables, Run run ) {
    if ( blank( table ) ) {
      run.add( at, "is required" );
    } else if ( !tables.contains( table ) ) {
      run.add( at, "\"" + table + "\" is not one of the source's tables" );
    }
  }

  private static void security( DatasetDefinition d, Map<String, Column> columns, Run run ) {
    if ( d.getSecurity() == null ) {
      return;
    }
    List<RowRule> rules = d.getSecurity().getRules();
    for ( int i = 0; i < rules.size(); i++ ) {
      RowRule rule = rules.get( i );
      String at = "security.rules[" + i + "]";
      if ( rule == null ) {
        run.add( at, "is empty" );
        continue;
      }
      boolean role = !blank( rule.getRole() );
      boolean user = !blank( rule.getUser() );
      if ( role == user ) {
        run.add( at, "needs either a role or a user, not both" );
      }
      knownColumn( rule.getColumn(), at + ".column", columns, run );
      if ( rule.getValues().isEmpty() ) {
        run.add( at + ".values", "needs at least one value" );
      }
    }
  }

  private static void refresh( Refresh r, Run run ) {
    if ( r == null ) {
      return;
    }
    if ( r.getMode() == null ) {
      run.add( "refresh.mode", "is required" );
    } else if ( r.getMode() == DatasetDefinition.RefreshMode.SCHEDULED && blank( r.getCron() ) ) {
      run.add( "refresh.cron", "is required for a scheduled refresh" );
    }
    if ( r.getTimeZone() != null ) {
      try {
        ZoneId.of( r.getTimeZone() );
      } catch ( DateTimeException e ) {
        run.add( "refresh.timeZone", "\"" + r.getTimeZone() + "\" is not a known time zone" );
      }
    }
  }

  /** Each left-out column names a source column (of one of the source's tables) that isn't also kept. */
  private static void excludedColumns( DatasetDefinition d, Set<String> tables, Map<String, Column> columns, Run run ) {
    if ( d.getExcludedColumns() == null ) {
      return;
    }
    Set<String> kept = new HashSet<>();
    for ( Column c : columns.values() ) {
      kept.add( ( c.getTable() == null ? "" : c.getTable() ) + '\u0000' + ( blank( c.getSourceName() ) ? c.getName() : c.getSourceName() ) );
    }
    for ( int i = 0; i < d.getExcludedColumns().size(); i++ ) {
      DatasetDefinition.ColumnRef ref = d.getExcludedColumns().get( i );
      String at = "excludedColumns[" + i + "]";
      if ( ref == null || blank( ref.getSourceName() ) ) {
        run.add( at + ".sourceName", "is required" );
        continue;
      }
      if ( ref.getTable() != null && !tables.contains( ref.getTable() ) ) {
        run.add( at + ".table", "\"" + ref.getTable() + "\" is not one of the source's tables" );
      }
      if ( kept.contains( ( ref.getTable() == null ? "" : ref.getTable() ) + '\u0000' + ref.getSourceName() ) ) {
        run.add( at, "\"" + ref.getSourceName() + "\" is kept as a column, so it can't also be left out" );
      }
    }
  }

  private static boolean blank( String s ) {
    return s == null || s.trim().isEmpty();
  }

  /** Collects problems in order. */
  private static final class Run {
    private final List<DatasetProblem> problems;

    Run( List<DatasetProblem> problems ) {
      this.problems = problems;
    }

    void add( String path, String message ) {
      problems.add( new DatasetProblem( path, message ) );
    }
  }
}
