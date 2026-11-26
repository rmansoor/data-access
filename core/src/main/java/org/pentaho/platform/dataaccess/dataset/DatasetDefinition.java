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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * A dataset: where its data comes from, its columns, its model, how it relates tables, who may see
 * which rows, and how it is kept fresh. It is the single source of truth for a dataset; the
 * Pentaho Metadata (XMI) domain and Mondrian schema that reports read are generated from it.
 * <p>
 * Stored as JSON (see {@link DatasetJson}) at {@link DatasetPaths#definitionPath(String)}, and
 * described for API clients by {@code dataset-definition.schema.json} next to this class.
 * Validated by {@link DatasetValidator}. Every type here is a plain bean so that Jackson binds it
 * without custom code; properties left at their defaults are not written.
 */
@JsonInclude( JsonInclude.Include.NON_DEFAULT )
@JsonPropertyOrder( { "schemaVersion", "id", "name", "description", "owner", "source", "columns", "model",
  "relationships", "security", "refresh", "legacy" } )
public class DatasetDefinition {

  private int schemaVersion = DatasetUpgrades.CURRENT_VERSION;
  private String id;
  private String name;
  private String description;
  private String owner;
  private Source source;
  private List<Column> columns = new ArrayList<>();
  private Model model;
  private List<Relationship> relationships = new ArrayList<>();
  private Security security;
  private Refresh refresh;
  private LegacyIds legacy;

  /** Always written: readers use it to upgrade older files (see {@link DatasetUpgrades}). */
  @JsonInclude( JsonInclude.Include.ALWAYS )
  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion( int schemaVersion ) {
    this.schemaVersion = schemaVersion;
  }

  public String getId() {
    return id;
  }

  public void setId( String id ) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName( String name ) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription( String description ) {
    this.description = description;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner( String owner ) {
    this.owner = owner;
  }

  public Source getSource() {
    return source;
  }

  public void setSource( Source source ) {
    this.source = source;
  }

  public List<Column> getColumns() {
    return columns;
  }

  public void setColumns( List<Column> columns ) {
    this.columns = columns == null ? new ArrayList<>() : columns;
  }

  public Model getModel() {
    return model;
  }

  public void setModel( Model model ) {
    this.model = model;
  }

  public List<Relationship> getRelationships() {
    return relationships;
  }

  public void setRelationships( List<Relationship> relationships ) {
    this.relationships = relationships == null ? new ArrayList<>() : relationships;
  }

  public Security getSecurity() {
    return security;
  }

  public void setSecurity( Security security ) {
    this.security = security;
  }

  public Refresh getRefresh() {
    return refresh;
  }

  public void setRefresh( Refresh refresh ) {
    this.refresh = refresh;
  }

  public LegacyIds getLegacy() {
    return legacy;
  }

  public void setLegacy( LegacyIds legacy ) {
    this.legacy = legacy;
  }

  // ------------------------------------------------------------------------------------------------
  // Source

  public enum SourceType { TABLES, SQL, FILE }

  public enum FileFormat { CSV }

  /** Where the rows come from: database tables, a SQL query, or a file staged into a table. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "type", "connection", "tables", "sql", "file" } )
  public static class Source {
    private SourceType type;
    /** Name of the JDBC connection (TABLES and SQL); for FILE, the staging connection, if not the default. */
    private String connection;
    /** Fully qualified table names ("schema.table" or "table") for TABLES. */
    private List<String> tables = new ArrayList<>();
    private String sql;
    private FileSource file;

    public SourceType getType() {
      return type;
    }

    public void setType( SourceType type ) {
      this.type = type;
    }

    public String getConnection() {
      return connection;
    }

    public void setConnection( String connection ) {
      this.connection = connection;
    }

    public List<String> getTables() {
      return tables;
    }

    public void setTables( List<String> tables ) {
      this.tables = tables == null ? new ArrayList<>() : tables;
    }

    public String getSql() {
      return sql;
    }

    public void setSql( String sql ) {
      this.sql = sql;
    }

    public FileSource getFile() {
      return file;
    }

    public void setFile( FileSource file ) {
      this.file = file;
    }
  }

  /** A file the dataset was loaded from, and how to read it. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "format", "path", "encoding", "delimiter", "enclosure", "headerRow", "stagingTable" } )
  public static class FileSource {
    private FileFormat format;
    /** Repository or staging path of the uploaded file. */
    private String path;
    private String encoding;
    private String delimiter;
    private String enclosure;
    private boolean headerRow;
    /** The table the file is staged into; set by the server. */
    private String stagingTable;

    public FileFormat getFormat() {
      return format;
    }

    public void setFormat( FileFormat format ) {
      this.format = format;
    }

    public String getPath() {
      return path;
    }

    public void setPath( String path ) {
      this.path = path;
    }

    public String getEncoding() {
      return encoding;
    }

    public void setEncoding( String encoding ) {
      this.encoding = encoding;
    }

    public String getDelimiter() {
      return delimiter;
    }

    public void setDelimiter( String delimiter ) {
      this.delimiter = delimiter;
    }

    public String getEnclosure() {
      return enclosure;
    }

    public void setEnclosure( String enclosure ) {
      this.enclosure = enclosure;
    }

    public boolean isHeaderRow() {
      return headerRow;
    }

    public void setHeaderRow( boolean headerRow ) {
      this.headerRow = headerRow;
    }

    public String getStagingTable() {
      return stagingTable;
    }

    public void setStagingTable( String stagingTable ) {
      this.stagingTable = stagingTable;
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Columns

  public enum ColumnType { STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP }

  /** A column as people see it: its business name, type, format and description. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "name", "table", "sourceName", "type", "label", "description", "format", "hidden" } )
  public static class Column {
    /** Unique within the dataset; what the model refers to. */
    private String name;
    /** For TABLES sources with several tables: the table this column belongs to. */
    private String table;
    /** The column's name in the source, when it differs from {@link #name}. */
    private String sourceName;
    private ColumnType type;
    private String label;
    private String description;
    /** A display format, for example "#,##0.00" or "yyyy-MM-dd". */
    private String format;
    private boolean hidden;

    public String getName() {
      return name;
    }

    public void setName( String name ) {
      this.name = name;
    }

    public String getTable() {
      return table;
    }

    public void setTable( String table ) {
      this.table = table;
    }

    public String getSourceName() {
      return sourceName;
    }

    public void setSourceName( String sourceName ) {
      this.sourceName = sourceName;
    }

    public ColumnType getType() {
      return type;
    }

    public void setType( ColumnType type ) {
      this.type = type;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel( String label ) {
      this.label = label;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription( String description ) {
      this.description = description;
    }

    public String getFormat() {
      return format;
    }

    public void setFormat( String format ) {
      this.format = format;
    }

    public boolean isHidden() {
      return hidden;
    }

    public void setHidden( boolean hidden ) {
      this.hidden = hidden;
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Model

  public enum Aggregation { SUM, COUNT, COUNT_DISTINCT, AVERAGE, MINIMUM, MAXIMUM }

  /** Dimensions people slice by and measures they aggregate. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "dimensions", "measures" } )
  public static class Model {
    private List<Dimension> dimensions = new ArrayList<>();
    private List<Measure> measures = new ArrayList<>();

    public List<Dimension> getDimensions() {
      return dimensions;
    }

    public void setDimensions( List<Dimension> dimensions ) {
      this.dimensions = dimensions == null ? new ArrayList<>() : dimensions;
    }

    public List<Measure> getMeasures() {
      return measures;
    }

    public void setMeasures( List<Measure> measures ) {
      this.measures = measures == null ? new ArrayList<>() : measures;
    }
  }

  /** A dimension with one hierarchy: its levels are column names, top level first. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "name", "levels", "time" } )
  public static class Dimension {
    private String name;
    private List<String> levels = new ArrayList<>();
    /** A time dimension (Year, Quarter, Month…), so analysis tools offer time functions. */
    private boolean time;

    public String getName() {
      return name;
    }

    public void setName( String name ) {
      this.name = name;
    }

    public List<String> getLevels() {
      return levels;
    }

    public void setLevels( List<String> levels ) {
      this.levels = levels == null ? new ArrayList<>() : levels;
    }

    public boolean isTime() {
      return time;
    }

    public void setTime( boolean time ) {
      this.time = time;
    }
  }

  /** An aggregated column. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "name", "column", "aggregation", "format" } )
  public static class Measure {
    private String name;
    private String column;
    private Aggregation aggregation;
    private String format;

    public String getName() {
      return name;
    }

    public void setName( String name ) {
      this.name = name;
    }

    public String getColumn() {
      return column;
    }

    public void setColumn( String column ) {
      this.column = column;
    }

    public Aggregation getAggregation() {
      return aggregation;
    }

    public void setAggregation( Aggregation aggregation ) {
      this.aggregation = aggregation;
    }

    public String getFormat() {
      return format;
    }

    public void setFormat( String format ) {
      this.format = format;
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Relationships

  public enum JoinType { INNER, LEFT_OUTER }

  public enum Cardinality { MANY_TO_ONE, ONE_TO_ONE, ONE_TO_MANY }

  /** A join between two tables of a TABLES source. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "fromTable", "fromColumn", "toTable", "toColumn", "joinType", "cardinality" } )
  public static class Relationship {
    private String fromTable;
    private String fromColumn;
    private String toTable;
    private String toColumn;
    private JoinType joinType;
    private Cardinality cardinality;

    public String getFromTable() {
      return fromTable;
    }

    public void setFromTable( String fromTable ) {
      this.fromTable = fromTable;
    }

    public String getFromColumn() {
      return fromColumn;
    }

    public void setFromColumn( String fromColumn ) {
      this.fromColumn = fromColumn;
    }

    public String getToTable() {
      return toTable;
    }

    public void setToTable( String toTable ) {
      this.toTable = toTable;
    }

    public String getToColumn() {
      return toColumn;
    }

    public void setToColumn( String toColumn ) {
      this.toColumn = toColumn;
    }

    public JoinType getJoinType() {
      return joinType;
    }

    public void setJoinType( JoinType joinType ) {
      this.joinType = joinType;
    }

    public Cardinality getCardinality() {
      return cardinality;
    }

    public void setCardinality( Cardinality cardinality ) {
      this.cardinality = cardinality;
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Security and refresh

  /** Row-level security: with any rule present, people see only rows a rule grants them. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "rules" } )
  public static class Security {
    private List<RowRule> rules = new ArrayList<>();

    public List<RowRule> getRules() {
      return rules;
    }

    public void setRules( List<RowRule> rules ) {
      this.rules = rules == null ? new ArrayList<>() : rules;
    }
  }

  /** Members of {@code role} (or the user {@code user}) see rows whose {@code column} is one of {@code values}. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "role", "user", "column", "values" } )
  public static class RowRule {
    private String role;
    private String user;
    private String column;
    private List<String> values = new ArrayList<>();

    public String getRole() {
      return role;
    }

    public void setRole( String role ) {
      this.role = role;
    }

    public String getUser() {
      return user;
    }

    public void setUser( String user ) {
      this.user = user;
    }

    public String getColumn() {
      return column;
    }

    public void setColumn( String column ) {
      this.column = column;
    }

    public List<String> getValues() {
      return values;
    }

    public void setValues( List<String> values ) {
      this.values = values == null ? new ArrayList<>() : values;
    }
  }

  public enum RefreshMode { MANUAL, SCHEDULED }

  /** How a staged dataset is kept fresh; schedules run through the scheduler plugin. */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "mode", "cron", "timeZone" } )
  public static class Refresh {
    private RefreshMode mode;
    /** Quartz cron expression, for SCHEDULED. */
    private String cron;
    private String timeZone;

    public RefreshMode getMode() {
      return mode;
    }

    public void setMode( RefreshMode mode ) {
      this.mode = mode;
    }

    public String getCron() {
      return cron;
    }

    public void setCron( String cron ) {
      this.cron = cron;
    }

    public String getTimeZone() {
      return timeZone;
    }

    public void setTimeZone( String timeZone ) {
      this.timeZone = timeZone;
    }
  }

  /**
   * Ids of the generated metadata domain, logical model and Mondrian catalog. Kept stable across
   * republishing and across migration from Data Source Wizard sources, so reports keep working.
   */
  @JsonInclude( JsonInclude.Include.NON_DEFAULT )
  @JsonPropertyOrder( { "domainId", "modelId", "catalogName" } )
  public static class LegacyIds {
    private String domainId;
    private String modelId;
    private String catalogName;

    public String getDomainId() {
      return domainId;
    }

    public void setDomainId( String domainId ) {
      this.domainId = domainId;
    }

    public String getModelId() {
      return modelId;
    }

    public void setModelId( String modelId ) {
      this.modelId = modelId;
    }

    public String getCatalogName() {
      return catalogName;
    }

    public void setCatalogName( String catalogName ) {
      this.catalogName = catalogName;
    }
  }
}
