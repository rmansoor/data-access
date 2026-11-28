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

package org.pentaho.platform.dataaccess.dataset.model;

import org.pentaho.agilebi.modeler.BaseModelerWorkspaceHelper;
import org.pentaho.agilebi.modeler.ColumnBackedNode;
import org.pentaho.agilebi.modeler.ModelerException;
import org.pentaho.agilebi.modeler.ModelerPerspective;
import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.nodes.AvailableField;
import org.pentaho.agilebi.modeler.nodes.AvailableTable;
import org.pentaho.agilebi.modeler.nodes.DimensionMetaData;
import org.pentaho.agilebi.modeler.nodes.HierarchyMetaData;
import org.pentaho.agilebi.modeler.nodes.LevelMetaData;
import org.pentaho.agilebi.modeler.nodes.MainModelNode;
import org.pentaho.agilebi.modeler.nodes.MeasureMetaData;
import org.pentaho.agilebi.modeler.nodes.RelationalModelNode;
import org.pentaho.agilebi.modeler.nodes.TimeRole;
import org.pentaho.di.core.Props;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.IPhysicalColumn;
import org.pentaho.metadata.model.LogicalColumn;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.model.LogicalTable;
import org.pentaho.metadata.model.SqlPhysicalColumn;
import org.pentaho.metadata.model.SqlPhysicalModel;
import org.pentaho.metadata.model.SqlPhysicalTable;
import org.pentaho.metadata.model.concept.types.AggregationType;
import org.pentaho.metadata.model.concept.types.DataType;
import org.pentaho.metadata.model.concept.types.FieldType;
import org.pentaho.metadata.model.concept.types.LocaleType;
import org.pentaho.metadata.model.concept.types.LocalizedString;
import org.pentaho.metadata.model.concept.types.TargetTableType;
import org.pentaho.metadata.util.MondrianModelExporter;
import org.pentaho.metadata.util.Util;
import org.pentaho.metadata.util.XmiParser;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Aggregation;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Column;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.ColumnType;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Dimension;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Measure;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Model;
import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a dataset definition into what Pentaho's report tools read: a metadata domain (XMI) for
 * Interactive Reports and Report Designer, and a Mondrian schema for Analyzer.
 * <p>
 * The domain is built from the definition alone (no database is read), then the Agile BI modeler
 * produces the reporting and analysis models from it, exactly as the Data Source Wizard did, so the
 * report tools see the same shape. The definition's model gives the dimensions and measures; a
 * definition without one gets the modeler's automatic suggestion ({@link #suggest}).
 * <p>
 * The output depends only on the definition, the source and the ids: generating it again gives the
 * same bytes.
 */
public class DatasetModelGenerator {

  static final String LOCALE = "en_US";
  private static final String PHYSICAL_TABLE_ID = "PT_DATASET";
  private static final String INLINE_SQL_TABLE_ID = "INLINE_SQL_1";

  /** What publishing stores: the domain, its XMI, and the Mondrian schema (null when the model has no measures). */
  public static final class Generated {
    private final Domain domain;
    private final String xmi;
    private final String mondrianSchema;
    private final PublishedIds ids;
    private final List<String> warnings;

    Generated( Domain domain, String xmi, String mondrianSchema, PublishedIds ids, List<String> warnings ) {
      this.domain = domain;
      this.xmi = xmi;
      this.mondrianSchema = mondrianSchema;
      this.ids = ids;
      this.warnings = List.copyOf( warnings );
    }

    /** What was published differently from the definition, such as a time dimension published as a plain one. */
    public List<String> getWarnings() {
      return warnings;
    }

    public Domain getDomain() {
      return domain;
    }

    public String getXmi() {
      return xmi;
    }

    /** The Mondrian schema, or null when there is nothing to analyse (no measures). */
    public String getMondrianSchema() {
      return mondrianSchema;
    }

    public PublishedIds getIds() {
      return ids;
    }
  }

  /**
   * Generates the domain and schema.
   *
   * @throws DatasetApiException VALIDATION_FAILED when the model names a column the dataset doesn't keep
   */
  public Generated generate( DatasetDefinition d, PhysicalSource source, PublishedIds ids ) {
    ModelerWorkspace ws = workspace( d, source, ids );
    List<String> warnings = new ArrayList<>();
    Model model = d.getModel();
    boolean empty = model == null || ( model.getDimensions().isEmpty() && model.getMeasures().isEmpty() );
    try {
      if ( empty ) {
        helper( ws ).autoModelFlat( ws );
      } else {
        apply( ws, d, source, model, warnings );
      }
      // The reporting model's category of fields (Interactive Reports, Report Designer), as the wizard made it.
      helper( ws ).autoModelRelationalFlat( ws );
      helper( ws ).populateDomain( ws );
    } catch ( ModelerException e ) {
      throw new DatasetApiException( Code.INTERNAL, "", "The model could not be generated: " + e.getMessage(), null, e );
    }
    Domain domain = ws.getDomain();
    domain.setId( ids.getDomainId() ); // the modeler names the domain after the model; the wizard set it back too
    LogicalModel analysis = ws.getLogicalModel( ModelerPerspective.ANALYSIS );
    LogicalModel reporting = ws.getLogicalModel( ModelerPerspective.REPORTING );
    // The modeler names the models MODEL_1 and MODEL_1_OLAP; a dataset keeps the ids it was first published with.
    if ( reporting != null ) {
      reporting.setId( ids.getModelId() );
    }
    if ( analysis != null && analysis != reporting ) {
      analysis.setId( ids.getModelId() + BaseModelerWorkspaceHelper.OLAP_SUFFIX );
    }
    boolean olap = analysis != null && hasMeasures( ws );
    for ( LogicalModel m : domain.getLogicalModels() ) {
      m.setProperty( "AGILE_BI_GENERATED_SCHEMA", "TRUE" );
      m.setProperty( "WIZARD_GENERATED_SCHEMA", "TRUE" );
    }
    if ( reporting != null && d.getDescription() != null && !d.getDescription().trim().isEmpty() ) {
      reporting.setDescription( new LocalizedString( LOCALE, d.getDescription().trim() ) );
    }
    String mondrian = null;
    if ( olap ) {
      analysis.setProperty( "MondrianCatalogRef", ids.getCatalogName() );
      try {
        mondrian = new MondrianModelExporter( analysis, LOCALE ).createMondrianModelXML();
      } catch ( Exception e ) {
        throw new DatasetApiException( Code.INTERNAL, "", "The Mondrian schema could not be generated: " + e.getMessage(), null, e );
      }
    } else if ( analysis != null ) {
      domain.getLogicalModels().remove( analysis );
    }
    return new Generated( domain, xmi( domain ), mondrian, ids, warnings );
  }

  /** The Agile BI modeler's suggestion for a definition: every column a dimension, numeric columns also summed. */
  public Model suggest( DatasetDefinition d, PhysicalSource source ) {
    ModelerWorkspace ws = workspace( d, source, PublishedIds.of( d ) );
    try {
      helper( ws ).autoModelFlat( ws );
    } catch ( ModelerException e ) {
      throw new DatasetApiException( Code.INTERNAL, "", "No model could be suggested: " + e.getMessage(), null, e );
    }
    Map<String, String> byPhysical = new HashMap<>();
    source.getColumns().forEach( ( name, physical ) -> byPhysical.put( physical, name ) );
    Model model = new Model();
    for ( DimensionMetaData dm : ws.getModel().getDimensions() ) {
      Dimension dim = new Dimension();
      dim.setName( dm.getName() );
      dim.setTime( dm.isTimeDimension() );
      for ( HierarchyMetaData h : dm ) {
        for ( LevelMetaData level : h ) {
          dim.getLevels().add( byPhysical.get( targetColumn( level.getLogicalColumn() ) ) );
        }
      }
      model.getDimensions().add( dim );
    }
    for ( MeasureMetaData mm : ws.getModel().getMeasures() ) {
      Measure m = new Measure();
      m.setName( mm.getName() );
      m.setColumn( byPhysical.get( targetColumn( mm.getLogicalColumn() ) ) );
      m.setAggregation( aggregation( mm.getDefaultAggregation() ) );
      model.getMeasures().add( m );
    }
    return model;
  }

  // ------------------------------------------------------------------------------------------------
  // The domain the modeler starts from: one physical table and its logical table.

  private ModelerWorkspace workspace( DatasetDefinition d, PhysicalSource source, PublishedIds ids ) {
    if ( !Props.isInitialized() ) {
      Props.init( Props.TYPE_PROPERTIES_EMPTY ); // the metadata layer reads its id prefixes from Kettle's settings
    }
    Domain domain = new Domain();
    domain.setId( ids.getDomainId() );
    domain.setLocales( new ArrayList<>( List.of( new LocaleType( LOCALE, "English (US)" ) ) ) );

    SqlPhysicalModel physical = new SqlPhysicalModel();
    physical.setId( source.getConnection() );
    physical.setDatasource( source.getDataSource() );
    domain.addPhysicalModel( physical );

    SqlPhysicalTable table = new SqlPhysicalTable( physical );
    if ( source.getSql() != null ) {
      table.setId( INLINE_SQL_TABLE_ID );
      table.setTargetTableType( TargetTableType.INLINE_SQL );
      table.setTargetTable( source.getSql() );
    } else {
      table.setId( PHYSICAL_TABLE_ID );
      if ( source.getSchema() != null ) {
        table.setTargetSchema( source.getSchema() );
      }
      table.setTargetTable( source.getTable() );
    }
    table.setName( new LocalizedString( LOCALE, ids.getCatalogName() ) );
    Map<String, Column> byName = new HashMap<>();
    for ( Column c : PhysicalSource.kept( d ) ) {
      byName.put( c.getName(), c );
    }
    for ( Map.Entry<String, String> e : source.getColumns().entrySet() ) {
      Column c = byName.get( e.getKey() );
      SqlPhysicalColumn pc = new SqlPhysicalColumn( table );
      pc.setId( e.getValue() );
      pc.setTargetColumn( e.getValue() );
      pc.setFieldType( FieldType.guessFieldType( e.getValue() ) );
      pc.setAggregationType( AggregationType.NONE );
      pc.setName( new LocalizedString( LOCALE, label( c ) ) );
      pc.setDataType( dataType( c.getType() ) );
      if ( c.getDescription() != null && !c.getDescription().trim().isEmpty() ) {
        pc.setDescription( new LocalizedString( LOCALE, c.getDescription().trim() ) );
      }
      if ( c.getFormat() != null && !c.getFormat().trim().isEmpty() ) {
        pc.setProperty( "mask", c.getFormat().trim() );
      }
      table.getPhysicalColumns().add( pc );
    }
    physical.addPhysicalTable( table );

    LogicalModel logical = new LogicalModel();
    logical.setId( ids.getModelId() );
    logical.setPhysicalModel( physical );
    domain.addLogicalModel( logical );
    LogicalTable lt = new LogicalTable( logical, table );
    lt.setName( new LocalizedString( LOCALE, ids.getCatalogName() ) );
    lt.setId( Util.proposeSqlBasedLogicalTableId( LOCALE, lt, table ) );
    for ( IPhysicalColumn pc : table.getPhysicalColumns() ) {
      LogicalColumn lc = new LogicalColumn();
      lc.setPhysicalColumn( pc );
      lc.setLogicalTable( lt );
      lc.setId( Util.proposeSqlBasedLogicalColumnId( LOCALE, lt, (SqlPhysicalColumn) pc ) );
      lt.addLogicalColumn( lc );
    }
    logical.addLogicalTable( lt );

    ModelerWorkspace ws = new ModelerWorkspace( new Helper() );
    ws.setDomain( domain );
    ws.setModelName( ids.getCatalogName() );
    ws.setRelationalModelName( ids.getCatalogName() );
    return ws;
  }

  // ------------------------------------------------------------------------------------------------
  // The definition's model, as modeler dimensions and measures.

  private static void apply( ModelerWorkspace ws, DatasetDefinition d, PhysicalSource source, Model model,
                             List<String> warnings ) {
    AvailableTable table = ws.getAvailableTables().getAsAvailableTablesList().get( 0 );
    Map<String, AvailableField> fields = new HashMap<>();
    for ( AvailableField f : table.getAvailableFields() ) {
      fields.put( f.getPhysicalColumn().getId(), f );
    }
    Map<String, String> physical = source.getColumns();
    List<DatasetProblem> problems = new ArrayList<>();
    MainModelNode main = ws.getModel();
    for ( int i = 0; i < model.getDimensions().size(); i++ ) {
      Dimension dim = model.getDimensions().get( i );
      DimensionMetaData dm = new DimensionMetaData( dim.getName() );
      HierarchyMetaData h = new HierarchyMetaData( dim.getName() );
      h.setParent( dm );
      dm.add( h );
      List<TimeRole> roles = dim.isTime() ? timeRoles( d, dim ) : null;
      if ( dim.isTime() && roles == null ) {
        warnings.add( "Dimension \"" + dim.getName() + "\" was published as a plain dimension: name its levels' columns "
          + "after years, quarters, months, weeks or days (or end with a date column) to make it a time dimension" );
      }
      dm.setTimeDimension( roles != null );
      for ( int j = 0; j < dim.getLevels().size(); j++ ) {
        AvailableField f = field( fields, physical, dim.getLevels().get( j ), "model.dimensions[" + i + "].levels[" + j + "]", problems );
        if ( f != null ) {
          ColumnBackedNode node = ws.createColumnBackedNode( f, ModelerPerspective.ANALYSIS );
          LevelMetaData level = ws.createLevelForParentWithNode( h, node );
          level.setName( columnLabel( d, dim.getLevels().get( j ) ) );
          if ( roles != null ) {
            level.setDataRole( roles.get( j ) );
          }
          h.add( level );
        }
      }
      main.getDimensions().add( dm );
    }
    for ( int i = 0; i < model.getMeasures().size(); i++ ) {
      Measure m = model.getMeasures().get( i );
      AvailableField f = field( fields, physical, m.getColumn(), "model.measures[" + i + "].column", problems );
      if ( f != null ) {
        MeasureMetaData mm = ws.createMeasureForNode( f );
        mm.setName( m.getName() );
        mm.setDefaultAggregation( aggregationType( m.getAggregation() ) );
        if ( m.getFormat() != null && !m.getFormat().trim().isEmpty() ) {
          mm.setFormat( m.getFormat().trim() );
        }
        main.getMeasures().add( mm );
      }
    }
    if ( !problems.isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, problems.get( 0 ).getPath(),
        "The model can't be published: " + problems.get( 0 ), problems, null );
    }
  }

  /**
   * The modeler field of a definition column. Fields are keyed by table column (the physical column's
   * id); {@code physical} maps the definition's column names to those.
   */
  private static AvailableField field( Map<String, AvailableField> fields, Map<String, String> physical, String column,
                                       String path, List<DatasetProblem> problems ) {
    String target = column == null ? null : physical.get( column );
    AvailableField f = target == null ? null : fields.get( target );
    if ( f == null ) {
      problems.add( new DatasetProblem( path, "names \"" + column + "\", which isn't a kept column of the dataset" ) );
    }
    return f;
  }

  /**
   * The time type of each level of a time dimension, from its column's name (year, quarter, month…),
   * or days for a date column at the bottom; null when a level can't be told, since Mondrian
   * refuses a time dimension with an untyped level.
   */
  static List<TimeRole> timeRoles( DatasetDefinition d, Dimension dim ) {
    List<TimeRole> roles = new ArrayList<>();
    for ( int j = 0; j < dim.getLevels().size(); j++ ) {
      String name = dim.getLevels().get( j );
      TimeRole role = timeRole( name == null ? "" : name.toLowerCase( java.util.Locale.ROOT ) );
      if ( role == null && j == dim.getLevels().size() - 1 ) {
        ColumnType type = columnType( d, name );
        role = type == ColumnType.DATE || type == ColumnType.TIMESTAMP ? TimeRole.DAYS : null;
      }
      if ( role == null ) {
        return null;
      }
      roles.add( role );
    }
    return roles.isEmpty() ? null : roles;
  }

  private static TimeRole timeRole( String n ) {
    if ( n.contains( "half" ) ) {
      return TimeRole.HALFYEARS;
    } else if ( n.contains( "year" ) ) {
      return TimeRole.YEARS;
    } else if ( n.contains( "quarter" ) || n.contains( "qtr" ) ) {
      return TimeRole.QUARTERS;
    } else if ( n.contains( "month" ) ) {
      return TimeRole.MONTHS;
    } else if ( n.contains( "week" ) ) {
      return TimeRole.WEEKS;
    } else if ( n.contains( "day" ) || n.contains( "date" ) ) {
      return TimeRole.DAYS;
    } else if ( n.contains( "hour" ) ) {
      return TimeRole.HOURS;
    } else if ( n.contains( "minute" ) ) {
      return TimeRole.MINUTES;
    } else if ( n.contains( "second" ) ) {
      return TimeRole.SECONDS;
    }
    return null;
  }

  private static ColumnType columnType( DatasetDefinition d, String name ) {
    for ( Column c : d.getColumns() ) {
      if ( c.getName().equals( name ) ) {
        return c.getType();
      }
    }
    return null;
  }

  private static String columnLabel( DatasetDefinition d, String name ) {
    for ( Column c : d.getColumns() ) {
      if ( c.getName().equals( name ) ) {
        return label( c );
      }
    }
    return name;
  }

  private static boolean hasMeasures( ModelerWorkspace ws ) {
    return !ws.getModel().getMeasures().isEmpty();
  }

  private static String targetColumn( LogicalColumn c ) {
    return c == null ? null : ( (SqlPhysicalColumn) c.getPhysicalColumn() ).getTargetColumn();
  }

  // ------------------------------------------------------------------------------------------------

  private static String label( Column c ) {
    return c.getLabel() != null && !c.getLabel().trim().isEmpty() ? c.getLabel().trim() : c.getName();
  }

  static DataType dataType( ColumnType type ) {
    if ( type == null ) {
      return DataType.STRING;
    }
    switch ( type ) {
      case INTEGER:
      case DECIMAL:
        return DataType.NUMERIC;
      case BOOLEAN:
        return DataType.BOOLEAN;
      case DATE:
      case TIMESTAMP:
        return DataType.DATE;
      default:
        return DataType.STRING;
    }
  }

  static AggregationType aggregationType( Aggregation a ) {
    if ( a == null ) {
      return AggregationType.SUM;
    }
    switch ( a ) {
      case COUNT:
        return AggregationType.COUNT;
      case COUNT_DISTINCT:
        return AggregationType.COUNT_DISTINCT;
      case AVERAGE:
        return AggregationType.AVERAGE;
      case MINIMUM:
        return AggregationType.MINIMUM;
      case MAXIMUM:
        return AggregationType.MAXIMUM;
      default:
        return AggregationType.SUM;
    }
  }

  static Aggregation aggregation( AggregationType t ) {
    if ( t == null ) {
      return Aggregation.SUM;
    }
    switch ( t ) {
      case COUNT:
        return Aggregation.COUNT;
      case COUNT_DISTINCT:
        return Aggregation.COUNT_DISTINCT;
      case AVERAGE:
        return Aggregation.AVERAGE;
      case MINIMUM:
        return Aggregation.MINIMUM;
      case MAXIMUM:
        return Aggregation.MAXIMUM;
      default:
        return Aggregation.SUM;
    }
  }

  /**
   * The XMI without its export time, so the same domain always gives the same bytes. The modeler
   * collects each column's allowed aggregations in a hash set, whose order changes from one JVM to
   * the next; they are put in declaration order first.
   */
  static String xmi( Domain domain ) {
    for ( LogicalModel m : domain.getLogicalModels() ) {
      for ( LogicalTable t : m.getLogicalTables() ) {
        t.getLogicalColumns().forEach( DatasetModelGenerator::sortAggregations );
      }
      m.getCategories().forEach( c -> c.getLogicalColumns().forEach( DatasetModelGenerator::sortAggregations ) );
    }
    String xmi = new XmiParser().generateXmi( domain );
    return xmi.replaceFirst( " timestamp=\"[^\"]*\"", "" );
  }

  private static void sortAggregations( LogicalColumn c ) {
    List<AggregationType> list = c.getAggregationList();
    if ( list != null && list.size() > 1 ) {
      List<AggregationType> sorted = new ArrayList<>( list );
      sorted.sort( java.util.Comparator.comparingInt( Enum::ordinal ) );
      c.setAggregationList( sorted );
    }
  }

  private static BaseModelerWorkspaceHelper helper( ModelerWorkspace ws ) {
    return (BaseModelerWorkspaceHelper) ws.getWorkspaceHelper();
  }

  /** The modeler's server-side helper: no UI, just the model nodes. */
  static final class Helper extends BaseModelerWorkspaceHelper {
    Helper() {
      super( LOCALE );
    }

    @Override
    protected MainModelNode getMainModelNode( ModelerWorkspace ws ) {
      MainModelNode m = ws.getModel();
      if ( m == null ) {
        m = new MainModelNode();
        m.setWorkspace( ws );
        return m;
      }
      m.getMeasures().clear();
      m.getDimensions().clear();
      return m;
    }

    @Override
    protected RelationalModelNode getRelationalModelNode( ModelerWorkspace ws ) {
      RelationalModelNode m = ws.getRelationalModel();
      if ( m == null ) {
        m = new RelationalModelNode();
        m.setWorkspace( ws );
        return m;
      }
      m.getCategories().clear();
      return m;
    }
  }
}
