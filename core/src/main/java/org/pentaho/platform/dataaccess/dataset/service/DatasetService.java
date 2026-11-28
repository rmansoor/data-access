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

package org.pentaho.platform.dataaccess.dataset.service;

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.DatasetPaths;
import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.DatasetUpgrades;
import org.pentaho.platform.dataaccess.dataset.DatasetValidator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.FileSource;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.Source;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.SourceType;
import org.pentaho.platform.dataaccess.dataset.model.DatasetModelGenerator;
import org.pentaho.platform.dataaccess.dataset.model.ModelPublisher;
import org.pentaho.platform.dataaccess.dataset.model.PhysicalSource;
import org.pentaho.platform.dataaccess.dataset.model.PublishedIds;
import org.pentaho.platform.dataaccess.dataset.source.CsvStager;
import org.pentaho.platform.dataaccess.dataset.source.DatasetFiles;
import org.pentaho.platform.dataaccess.dataset.source.Sample;
import org.pentaho.platform.dataaccess.dataset.source.StagedTable;
import org.pentaho.platform.dataaccess.dataset.source.StagingConfig;
import org.pentaho.platform.dataaccess.dataset.source.StagingSettings;
import org.pentaho.platform.dataaccess.dataset.source.TableList;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;
import org.pentaho.platform.dataaccess.dataset.source.SourceLimits;

import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

/**
 * The rules behind the datasets API, independent of HTTP: permissions, ids, ownership and
 * optimistic locking. Drafts may be incomplete (people save as they go); completeness is checked by
 * {@link #validate(String)} and, later, enforced at publish.
 */
public class DatasetService {

  static final int MAX_GENERATED_ID = 60;
  private static final Log LOG = LogFactory.getLog( DatasetService.class );

  private final DatasetStore store;
  private final DatasetPermissions permissions;
  private final Supplier<String> currentUser;
  private final DatasetSharing sharing;
  private final SourceAdapters sources;
  private final DatasetValidator validator = new DatasetValidator();
  private final DatasetJson json = new DatasetJson();
  private final DatasetModelGenerator generator = new DatasetModelGenerator();

  /** With sharing kept by this service instance only; for tests and single-user tools. */
  public DatasetService( DatasetStore store, DatasetPermissions permissions, Supplier<String> currentUser ) {
    this( store, permissions, currentUser, new InMemoryDatasetSharing( currentUser, List::of ) );
  }

  public DatasetService( DatasetStore store, DatasetPermissions permissions, Supplier<String> currentUser,
                         DatasetSharing sharing ) {
    this( store, permissions, currentUser, sharing, SourceAdapters.none() );
  }

  public DatasetService( DatasetStore store, DatasetPermissions permissions, Supplier<String> currentUser,
                         DatasetSharing sharing, SourceAdapters sources ) {
    this.store = store;
    this.permissions = permissions;
    this.currentUser = currentUser;
    this.sharing = sharing;
    this.sources = sources;
  }

  /** Every dataset the caller may see, by name. */
  public List<StoredDataset> list() {
    requireView();
    return store.list().stream()
      .filter( s -> mayRead( s.getDefinition() ) )
      .sorted( Comparator.comparing( ( StoredDataset s ) -> String.valueOf( s.getDefinition().getName() ).toLowerCase( Locale.ROOT ) )
        .thenComparing( s -> s.getDefinition().getId() ) )
      .collect( Collectors.toList() );
  }

  public StoredDataset get( String id ) {
    requireView();
    return readable( id );
  }

  /**
   * Creates a draft. Without an id, one is made from the name ("Monthly orders" -> "monthly-orders",
   * then "monthly-orders-2"…). The owner defaults to the caller.
   */
  public StoredDataset create( DatasetDefinition definition ) {
    requireManage();
    requireName( definition );
    if ( definition.getId() == null || definition.getId().trim().isEmpty() ) {
      definition.setId( uniqueId( definition.getName() ) );
    } else {
      requireValidId( definition.getId(), "id" );
    }
    if ( definition.getOwner() == null || definition.getOwner().trim().isEmpty() ) {
      definition.setOwner( currentUser.get() );
    }
    keepServerFields( definition, null );
    definition.setSchemaVersion( DatasetUpgrades.CURRENT_VERSION );
    return store.create( definition );
  }

  /**
   * Replaces a draft. {@code expectedVersion} is the version the caller read (the ETag); without it
   * the update is refused, so nobody overwrites a change they never saw.
   */
  public StoredDataset update( String id, DatasetDefinition definition, String expectedVersion ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    if ( definition.getId() == null ) {
      definition.setId( id );
    } else if ( !definition.getId().equals( id ) ) {
      throw new DatasetApiException( Code.INVALID_DEFINITION, "id",
        "is \"" + definition.getId() + "\" but the address names \"" + id + "\"; a dataset's id can't change", null, null );
    }
    requireName( definition );
    DatasetDefinition current = writable( id ).getDefinition();
    if ( definition.getOwner() == null || definition.getOwner().trim().isEmpty() ) {
      definition.setOwner( current.getOwner() );
    } else if ( !definition.getOwner().equals( current.getOwner() ) && !isOwnerOrAdmin( current ) ) {
      throw new DatasetApiException( Code.FORBIDDEN, "owner", "Only the owner or an administrator can hand a dataset to someone else",
        null, null );
    }
    keepServerFields( definition, current );
    definition.setSchemaVersion( DatasetUpgrades.CURRENT_VERSION );
    return store.update( definition, expectedVersion );
  }

  /** What still has to be fixed before the dataset can be published; empty when it is ready. */
  public List<DatasetProblem> validate( String id ) {
    requireView();
    return validator.validate( readable( id ).getDefinition() );
  }

  public void delete( String id, String expectedVersion ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    DatasetDefinition d = writable( id ).getDefinition();
    store.delete( id, expectedVersion );
    String staged = stagedTable( d );
    if ( staged != null && sources.canStage() ) {
      sources.stager().drop( id, d.getSource().getConnection(), staged );
    }
    if ( sources.hasFiles() ) {
      sources.files().deleteAll( id );
    }
    try {
      sources.schedules().remove( id );
    } catch ( RuntimeException e ) {
      LOG.warn( "Dataset " + id + " was deleted but its schedule could not be removed", e );
    }
    try {
      sources.history().deleteAll( id );
    } catch ( RuntimeException e ) {
      LOG.warn( "Dataset " + id + " was deleted but its refresh history could not be", e );
    }
    if ( PublishedIds.assigned( d ) && sources.canPublish() ) {
      try {
        sources.publisher().unpublish( PublishedIds.of( d ) );
      } catch ( RuntimeException e ) {
        LOG.warn( "Dataset " + id + " was deleted but what it published could not be removed", e );
      }
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Publishing

  /** What publishing stored, and the dataset's version afterwards (new when the ids were fixed now). */
  public static final class Published {
    private final StoredDataset dataset;
    private final PublishedIds ids;
    private final boolean analysis;
    private final List<String> warnings;

    Published( StoredDataset dataset, PublishedIds ids, boolean analysis, List<String> warnings ) {
      this.dataset = dataset;
      this.ids = ids;
      this.analysis = analysis;
      this.warnings = warnings;
    }

    public StoredDataset getDataset() {
      return dataset;
    }

    public PublishedIds getIds() {
      return ids;
    }

    /** Whether a Mondrian catalog was published (the model has measures). */
    public boolean isAnalysis() {
      return analysis;
    }

    public List<String> getWarnings() {
      return warnings;
    }
  }

  /**
   * Publishes the dataset for reporting: its metadata domain and, when its model has measures, its
   * Mondrian catalog. The ids are fixed the first time (from the dataset's id and name, made unique
   * against what the server has) and kept in the definition, so a republish replaces the same domain
   * and catalog. A file must be loaded first.
   */
  public Published publish( String id, String expectedVersion ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    StoredDataset current = writable( id );
    if ( !current.getVersion().equals( expectedVersion ) ) {
      throw new DatasetApiException( Code.STALE_VERSION, "The dataset changed since it was read; read it again before publishing" );
    }
    DatasetDefinition d = json.read( json.write( current.getDefinition() ) );
    // Drafts may be incomplete; what is published must be valid as a whole (model, relationships…).
    List<DatasetProblem> problems = validator.validate( d );
    if ( !problems.isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, problems.get( 0 ).getPath(),
        "The dataset can't be published: " + problems.get( 0 ), problems, null );
    }
    ModelPublisher publisher = sources.publisher();
    String connection = d.getSource().getConnection();
    if ( connection == null || connection.trim().isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "source.connection",
        d.getSource().getType() == SourceType.FILE ? "Load the file before publishing" : "The source has no connection", null, null );
    }
    PhysicalSource source = PhysicalSource.of( d, connection, publisher.dataSource( connection ) );
    boolean fixedNow = !PublishedIds.assigned( d );
    PublishedIds ids = fixedNow ? unique( PublishedIds.of( d ), publisher ) : PublishedIds.of( d );
    DatasetModelGenerator.Generated g = generator.generate( d, source, ids );
    publisher.publish( connection, g );
    StoredDataset stored = current;
    if ( fixedNow ) {
      ids.keepIn( d );
      stored = store.update( d, expectedVersion );
    }
    return new Published( stored, ids, g.getMondrianSchema() != null, g.getWarnings() );
  }

  /** The Agile BI modeler's suggestion for the dataset's model, from its kept columns; nothing is stored. */
  public DatasetDefinition.Model suggestModel( String id ) {
    requireManage();
    requireValidId( id, "" );
    DatasetDefinition d = readable( id ).getDefinition();
    requireReadableSource( d );
    String connection = d.getSource().getConnection();
    if ( connection == null || connection.trim().isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "source.connection",
        d.getSource().getType() == SourceType.FILE ? "Load the file first" : "The source has no connection", null, null );
    }
    return generator.suggest( d, PhysicalSource.of( d, connection, sources.publisher().dataSource( connection ) ) );
  }

  /** A first publish takes the dataset's id and name unless another data source has them; then "name (2)", and so on. */
  private static PublishedIds unique( PublishedIds ids, ModelPublisher publisher ) {
    String domain = ids.getDomainId();
    for ( int n = 2; publisher.domainExists( domain ); n++ ) {
      domain = ids.getDomainId().replaceFirst( "\\.xmi$", "" ) + "-" + n + ".xmi";
    }
    String catalog = ids.getCatalogName();
    for ( int n = 2; publisher.catalogExists( catalog ); n++ ) {
      catalog = ids.getCatalogName() + " (" + n + ")";
    }
    return new PublishedIds( domain, ids.getModelId(), catalog );
  }

  /**
   * Stores a CSV file as the dataset's source file: {@code source.file.path} then names it, and
   * {@code source.file.originalName} records the uploader's file name. A dataset without a source
   * gets a FILE source (CSV, header row). The file it replaces is deleted; its staging table stays
   * until the next load replaces it. Needs the version the caller read, checked before the upload
   * is read so a stale change doesn't store anything.
   *
   * @param originalName the file's name on the uploader's computer; may be null
   */
  public StoredDataset uploadFile( String id, String expectedVersion, String originalName, InputStream body ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    StoredDataset current = writable( id );
    if ( !current.getVersion().equals( expectedVersion ) ) {
      throw new DatasetApiException( Code.STALE_VERSION, "The dataset changed since it was read; read it again before uploading" );
    }
    DatasetDefinition d = json.read( json.write( current.getDefinition() ) );
    Source source = d.getSource();
    if ( source == null ) {
      source = new Source();
      source.setType( SourceType.FILE );
      d.setSource( source );
    } else if ( source.getType() != SourceType.FILE ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "source.type",
        "Files can only be uploaded to a FILE source; this dataset's source is " + source.getType(), null, null );
    }
    FileSource file = source.getFile();
    if ( file == null ) {
      file = new FileSource();
      file.setFormat( DatasetDefinition.FileFormat.CSV );
      file.setHeaderRow( true );
      source.setFile( file );
    }
    DatasetFiles files = sources.files();
    String stored = files.store( id, originalName, body );
    String previous = file.getPath();
    file.setPath( stored );
    file.setOriginalName( displayName( originalName ) );
    // The format follows the file: a workbook's sheet and start row stay while it is a workbook (a newer copy).
    DatasetDefinition.FileFormat format = stored.endsWith( ".xlsx" ) ? DatasetDefinition.FileFormat.XLSX
      : stored.endsWith( ".parquet" ) ? DatasetDefinition.FileFormat.PARQUET : DatasetDefinition.FileFormat.CSV;
    if ( file.getFormat() != format ) {
      file.setSheet( null );
      file.setStartRow( 0 );
    }
    file.setFormat( format );
    StoredDataset updated;
    try {
      updated = store.update( d, expectedVersion );
    } catch ( RuntimeException e ) {
      files.delete( stored );
      throw e;
    }
    if ( previous != null && !previous.equals( stored ) && DatasetFiles.isStored( previous ) ) {
      files.delete( previous );
    }
    return updated;
  }

  /** "C:\\Users\\suzy\\Sales 2026.csv" -> "Sales 2026.csv": the base name, without control characters, at most 255. */
  static String displayName( String originalName ) {
    if ( originalName == null ) {
      return null;
    }
    String n = originalName.substring( Math.max( originalName.lastIndexOf( '/' ), originalName.lastIndexOf( '\\' ) ) + 1 )
      .replaceAll( "\\p{Cntrl}", "" ).trim();
    if ( n.isEmpty() ) {
      return null;
    }
    return n.length() > 255 ? n.substring( 0, 255 ) : n;
  }

  /**
   * The first rows of the dataset's source, with its columns as the source reports them. Anyone who
   * can see the dataset can sample it: the source is the one its managers defined.
   *
   * @param limit rows wanted, 1 to {@link SourceLimits#MAX_SAMPLE_ROWS}; null for {@link SourceLimits#DEFAULT_SAMPLE_ROWS}
   * @param table for a TABLES source, which table; null for the first
   */
  public Sample sample( String id, Integer limit, String table ) {
    requireView();
    DatasetDefinition d = readable( id ).getDefinition();
    int n = limit == null ? SourceLimits.DEFAULT_SAMPLE_ROWS : limit;
    if ( n < 1 || n > SourceLimits.MAX_SAMPLE_ROWS ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "limit", "limit must be between 1 and " + SourceLimits.MAX_SAMPLE_ROWS,
        null, null );
    }
    requireReadableSource( d );
    return sources.forSource( d.getSource() ).sample( d.getSource(), n, table );
  }

  /**
   * The tables and views on a connection, for choosing a TABLES source. For people who manage
   * datasets, since it shows what a database holds.
   */
  public TableList connectionTables( String connection ) {
    requireManage();
    return sources.tables().list( connection );
  }

  /** Where uploaded files are loaded: in effect, as saved by an administrator, and the settings.xml defaults. */
  @com.fasterxml.jackson.annotation.JsonInclude( com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL )
  public static final class StagingState {
    private final String connection;
    private final String schema;
    private final StagingSettings saved;
    private final StagingSettings defaults;

    StagingState( StagingConfig config ) {
      this.connection = config.connection();
      this.schema = config.schema();
      this.saved = config.saved();
      this.defaults = config.defaults();
    }

    public String getConnection() {
      return connection;
    }

    public String getSchema() {
      return schema;
    }

    public StagingSettings getSaved() {
      return saved;
    }

    public StagingSettings getDefaults() {
      return defaults;
    }
  }

  /** Where uploaded files are loaded. For people who manage datasets. */
  public StagingState getStaging() {
    requireManage();
    return new StagingState( sources.staging() );
  }

  /**
   * Sets where uploaded files are loaded from now on; empty values go back to settings.xml.
   * Administrators only. Datasets already loaded keep their tables until they are next loaded.
   */
  public StagingState setStaging( StagingSettings settings ) {
    if ( !permissions.canAdminister() ) {
      throw new DatasetApiException( Code.FORBIDDEN, "Only an administrator can change where uploaded files are loaded" );
    }
    if ( settings == null ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "Send the staging settings as JSON in the request body" );
    }
    String schema = settings.getSchema();
    if ( schema != null && !schema.matches( "[A-Za-z_][A-Za-z0-9_$]{0,127}" ) ) {
      String why = "must be a plain schema name (letters, digits, _ and $)";
      throw new DatasetApiException( Code.VALIDATION_FAILED, "schema", "The schema " + why,
        List.of( new DatasetProblem( "schema", why ) ), null );
    }
    if ( settings.getConnection() != null && !sources.connectionExists( settings.getConnection() ) ) {
      String why = "names no connection this server can open (\"" + settings.getConnection() + "\")";
      throw new DatasetApiException( Code.VALIDATION_FAILED, "connection", "The connection " + why,
        List.of( new DatasetProblem( "connection", why ) ), null );
    }
    StagingConfig config = sources.staging();
    config.save( settings );
    return new StagingState( config );
  }

  /** A dataset after its file was loaded, and the table it was loaded into. */
  public static final class Staging {
    private final StoredDataset dataset;
    private final StagedTable table;

    Staging( StoredDataset dataset, StagedTable table ) {
      this.dataset = dataset;
      this.table = table;
    }

    public StoredDataset getDataset() {
      return dataset;
    }

    public StagedTable getTable() {
      return table;
    }
  }

  /**
   * Loads the dataset's uploaded file into a new table on the staging connection and points the
   * dataset at it ({@code source.file.stagingTable}, {@code source.connection}); the table it used
   * before is dropped. Needs the version the caller read, as an update does, since the definition
   * changes.
   */
  public Staging stage( String id, String expectedVersion ) {
    PreparedLoad load = prepareLoad( id, expectedVersion );
    return performLoad( load, CsvStager.Progress.NONE );
  }

  /**
   * As {@link #stage}, in the background: the checks happen now (permission, version, source),
   * the load on a worker. Follow it with {@link #loadStatus}; stop it with {@link #cancelLoad}.
   * One load per dataset at a time (ALREADY_EXISTS otherwise).
   */
  public StagingJobs.Job startLoad( String id, String expectedVersion ) {
    return runInBackground( prepareLoad( id, expectedVersion ) );
  }

  /** The dataset's running or last background load; NOT_FOUND when there has been none since the server started. */
  public StagingJobs.Job loadStatus( String id ) {
    requireView();
    readable( id );
    StagingJobs.Job job = sources.jobs().last( id );
    if ( job == null ) {
      throw new DatasetApiException( Code.NOT_FOUND, "This dataset hasn't been loaded in the background since the server started" );
    }
    return job;
  }

  /** Asks the dataset's running background load to stop. */
  public StagingJobs.Job cancelLoad( String id ) {
    requireManage();
    writable( id );
    StagingJobs.Job job = sources.jobs().cancel( id );
    if ( job == null ) {
      throw new DatasetApiException( Code.NOT_FOUND, "No load of this dataset is running" );
    }
    return job;
  }

  /**
   * Refreshes a file dataset with a new file, in the background: REPLACE loads it instead of the
   * dataset's files, APPEND loads it after them. The new file must have the dataset's columns (by
   * name, with types that fit), or it is refused with every difference. The load is strict: a value
   * that doesn't fit its column fails it, and the dataset keeps its data. A successful load swaps the
   * new table in and, for a published dataset, republishes it under the same names, so reports read
   * the new rows. Follow it with {@link #loadStatus}; every refresh is recorded ({@link #refreshes}).
   */
  public StagingJobs.Job startRefresh( String id, String expectedVersion, DatasetRefresh.Mode mode, String originalName,
                                       InputStream body ) {
    if ( mode != DatasetRefresh.Mode.REPLACE && mode != DatasetRefresh.Mode.APPEND ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "mode", "mode must be replace or append", null, null );
    }
    PreparedLoad base = prepareLoad( id, expectedVersion );
    if ( mode == DatasetRefresh.Mode.APPEND && stagedTable( base.definition ) == null ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "mode", "The dataset hasn't been loaded yet: load it before appending to it",
        null, null );
    }
    DatasetFiles files = sources.files();
    String stored = files.store( id, originalName, body );
    DatasetDefinition.AppendedFile file = new DatasetDefinition.AppendedFile( stored, displayName( originalName ) );
    try {
      requireSameColumns( base.definition, file );
    } catch ( RuntimeException e ) {
      files.delete( stored );
      throw e;
    }
    PreparedLoad load = base.as( mode, DatasetRefresh.Trigger.USER, true, file );
    return runInBackground( load );
  }

  /**
   * Loads the dataset's files again, strictly, now (for a schedule): nothing changes when a value
   * doesn't fit. As the caller, who must be able to change the dataset.
   */
  public DatasetRefresh reload( String id, DatasetRefresh.Trigger trigger ) {
    StoredDataset current = writable( id );
    PreparedLoad load = prepareLoad( id, current.getVersion() ).as( DatasetRefresh.Mode.LOAD, trigger, true, null );
    performLoad( load, CsvStager.Progress.NONE );
    List<DatasetRefresh> recorded = sources.history().list( id );
    return recorded.isEmpty() ? null : recorded.get( 0 );
  }

  /** The dataset's scheduled reload, if it has one. */
  public java.util.Optional<DatasetSchedule> schedule( String id ) {
    requireView();
    readable( id );
    return sources.schedules().get( id );
  }

  /**
   * Schedules the file dataset to be loaded again (strictly; see {@link #reload}), replacing any
   * schedule it has. The caller owns the job and the reloads run as them.
   */
  public DatasetSchedule setSchedule( String id, DatasetSchedule schedule ) {
    requireManage();
    StoredDataset current = writable( id );
    Source source = current.getDefinition().getSource();
    if ( source == null || source.getType() != SourceType.FILE ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "source.type", "Only file datasets are loaded on a schedule", null, null );
    }
    if ( stagedTable( current.getDefinition() ) == null ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "", "Load the dataset once before scheduling it", null, null );
    }
    return sources.schedules().set( id, "Refresh " + current.getDefinition().getName(), currentUser.get(), schedule );
  }

  /** Stops the dataset's scheduled reloads. */
  public void removeSchedule( String id ) {
    requireManage();
    writable( id );
    sources.schedules().remove( id );
  }

  /** The dataset's loads, newest first: what started each, rows, duration, and errors. */
  public List<DatasetRefresh> refreshes( String id ) {
    requireView();
    readable( id );
    return sources.history().list( id );
  }

  private StagingJobs.Job runInBackground( PreparedLoad load ) {
    IPentahoSession session = PentahoSessionHolder.getSession();
    return sources.jobs().start( load.id, progress -> {
      // The worker acts for the caller who started it.
      PentahoSessionHolder.setSession( session );
      try {
        Staging done = performLoad( load, progress );
        return Map.entry( done.getTable(), done.getDataset().getVersion() );
      } finally {
        PentahoSessionHolder.removeSession();
      }
    } );
  }

  /** A load that passed its checks: what to load, how, and the table it replaces. */
  private static final class PreparedLoad {
    final String id;
    final String expectedVersion;
    final DatasetDefinition definition;
    final CsvStager stager;
    final String previousConnection;
    final String previous;
    final DatasetRefresh.Mode mode;
    final DatasetRefresh.Trigger trigger;
    /** A value that doesn't fit its column fails the load (refreshes), instead of loading as empty (the editor). */
    final boolean strict;
    /** REPLACE, APPEND: the new file, already stored. */
    final DatasetDefinition.AppendedFile newFile;

    PreparedLoad( String id, String expectedVersion, DatasetDefinition definition, CsvStager stager, String previousConnection,
                  String previous, DatasetRefresh.Mode mode, DatasetRefresh.Trigger trigger, boolean strict,
                  DatasetDefinition.AppendedFile newFile ) {
      this.id = id;
      this.expectedVersion = expectedVersion;
      this.definition = definition;
      this.stager = stager;
      this.previousConnection = previousConnection;
      this.previous = previous;
      this.mode = mode;
      this.trigger = trigger;
      this.strict = strict;
      this.newFile = newFile;
    }

    PreparedLoad as( DatasetRefresh.Mode m, DatasetRefresh.Trigger t, boolean s, DatasetDefinition.AppendedFile f ) {
      return new PreparedLoad( id, expectedVersion, definition, stager, previousConnection, previous, m, t, s, f );
    }
  }

  private PreparedLoad prepareLoad( String id, String expectedVersion ) {
    requireManage();
    requireValidId( id, "" );
    requireVersion( expectedVersion );
    StoredDataset current = writable( id );
    if ( !current.getVersion().equals( expectedVersion ) ) {
      throw new DatasetApiException( Code.STALE_VERSION, "The dataset changed since it was read; read it again before loading" );
    }
    DatasetDefinition d = json.read( json.write( current.getDefinition() ) );
    Source source = d.getSource();
    if ( source == null || source.getType() != SourceType.FILE ) {
      throw new DatasetApiException( Code.INVALID_REQUEST, "source.type", "Only FILE sources are loaded into staging tables",
        null, null );
    }
    requireReadableSource( d );
    CsvStager stager = sources.stager();
    stager.connection(); // fails now, not on the worker, when no staging connection is configured
    // source.connection is where the server put the last load (clients can't set it): the previous table is dropped there.
    return new PreparedLoad( id, expectedVersion, d, stager, source.getConnection(), stagedTable( d ), DatasetRefresh.Mode.LOAD,
      DatasetRefresh.Trigger.USER, false, null );
  }

  /** The files a load reads, in order: the dataset's file and those appended to it, or the new file instead or after them. */
  private List<Source> parts( PreparedLoad load ) {
    Source source = load.definition.getSource();
    FileSource f = source.getFile();
    List<Source> parts = new ArrayList<>();
    if ( load.mode != DatasetRefresh.Mode.REPLACE ) {
      parts.add( source );
      for ( DatasetDefinition.AppendedFile a : f.getAppended() ) {
        parts.add( withFile( source, a ) );
      }
    }
    if ( load.newFile != null ) {
      parts.add( withFile( source, load.newFile ) );
    }
    return parts;
  }

  private Source withFile( Source source, DatasetDefinition.AppendedFile file ) {
    DatasetDefinition holder = new DatasetDefinition();
    holder.setSource( source );
    Source copy = json.read( json.write( holder ) ).getSource();
    copy.getFile().setPath( file.getPath() );
    copy.getFile().setOriginalName( file.getOriginalName() );
    copy.getFile().setFormat( formatOf( file.getPath() ) );
    return copy;
  }

  private static DatasetDefinition.FileFormat formatOf( String path ) {
    return path.endsWith( ".xlsx" ) ? DatasetDefinition.FileFormat.XLSX
      : path.endsWith( ".parquet" ) ? DatasetDefinition.FileFormat.PARQUET : DatasetDefinition.FileFormat.CSV;
  }

  /**
   * A new file must have the columns the dataset was loaded with, by name, and fit the types the
   * dataset gives them; otherwise it is refused with every difference.
   */
  private void requireSameColumns( DatasetDefinition d, DatasetDefinition.AppendedFile file ) {
    Source current = d.getSource();
    Sample fresh = sources.forSource( current ).sample( withFile( current, file ), 50, null );
    Map<String, DatasetDefinition.ColumnType> found = new java.util.LinkedHashMap<>();
    fresh.getColumns().forEach( c -> found.put( c.getName(), c.getType() ) );
    List<String> expected = new ArrayList<>();
    current.getFile().getStagedColumns().forEach( c -> expected.add( c.getSourceName() ) );
    if ( expected.isEmpty() ) {
      sources.forSource( current ).sample( current, 1, null ).getColumns().forEach( c -> expected.add( c.getName() ) );
    }
    List<DatasetProblem> problems = new ArrayList<>();
    for ( String name : expected ) {
      if ( !found.containsKey( name ) ) {
        problems.add( new DatasetProblem( "file.columns", "column \"" + name + "\" is missing" ) );
      }
    }
    for ( String name : found.keySet() ) {
      if ( !expected.contains( name ) ) {
        problems.add( new DatasetProblem( "file.columns", "column \"" + name + "\" isn't one of the dataset's" ) );
      }
    }
    for ( DatasetDefinition.Column c : d.getColumns() ) {
      String name = c.getSourceName() == null || c.getSourceName().isEmpty() ? c.getName() : c.getSourceName();
      DatasetDefinition.ColumnType got = found.get( name );
      if ( got != null && c.getType() != null && !fits( got, c.getType() ) ) {
        problems.add( new DatasetProblem( "file.columns", "column \"" + name + "\" holds " + got + " values; the dataset's is "
          + c.getType() ) );
      }
    }
    if ( !problems.isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "file",
        "The file's columns don't match the dataset's: " + problems.get( 0 ).getMessage(), problems, null );
    }
  }

  /** Whether values detected as {@code found} load into a column of type {@code into}. */
  static boolean fits( DatasetDefinition.ColumnType found, DatasetDefinition.ColumnType into ) {
    switch ( into ) {
      case STRING:
        return true;
      case DECIMAL:
        return found == DatasetDefinition.ColumnType.DECIMAL || found == DatasetDefinition.ColumnType.INTEGER;
      case TIMESTAMP:
        return found == DatasetDefinition.ColumnType.TIMESTAMP || found == DatasetDefinition.ColumnType.DATE;
      default:
        return found == into;
    }
  }

  /**
   * Loads into the current staging connection and swaps the new table in: the dataset points at it,
   * the table it replaces is dropped, and a published dataset is republished under the same names.
   * A failed load changes nothing (its table is dropped, a new file discarded). Each load is recorded.
   */
  private Staging performLoad( PreparedLoad load, CsvStager.Progress progress ) {
    DatasetRefresh record = new DatasetRefresh();
    record.setId( java.util.UUID.randomUUID().toString() );
    record.setMode( load.mode );
    record.setTrigger( load.trigger );
    record.setUser( currentUser.get() );
    record.setFile( load.newFile == null ? null : load.newFile.getOriginalName() );
    record.setStarted( java.time.Instant.now().toString() );
    try {
      DatasetDefinition d = load.definition;
      Source source = d.getSource();
      FileSource file = source.getFile();
      String connection = load.stager.connection();
      StagedTable table = load.stager.stage( load.id, parts( load ), d.getColumns(), progress );
      if ( load.strict && table.getRejected() > 0 ) {
        load.stager.drop( load.id, connection, table.getTable() );
        List<DatasetProblem> problems = table.getWarnings().stream().map( w -> new DatasetProblem( "file", w ) )
          .collect( Collectors.toList() );
        throw new DatasetApiException( Code.VALIDATION_FAILED, "file", table.getRejected()
          + " value(s) don't fit their column's type, so nothing was changed: " + ( problems.isEmpty() ? "" : problems.get( 0 ).getMessage() ),
          problems, null );
      }
      source.setConnection( connection );
      file.setStagingTable( table.getTable() );
      file.setStagedColumns( table.getColumns().stream()
        .map( c -> new DatasetDefinition.StagedColumn( c.getSourceName(), c.getName() ) ).collect( Collectors.toList() ) );
      List<String> replaced = new ArrayList<>();
      if ( load.mode == DatasetRefresh.Mode.REPLACE ) {
        replaced.add( file.getPath() );
        file.getAppended().forEach( a -> replaced.add( a.getPath() ) );
        file.setPath( load.newFile.getPath() );
        file.setOriginalName( load.newFile.getOriginalName() );
        file.setFormat( formatOf( load.newFile.getPath() ) );
        file.setAppended( new ArrayList<>() );
      } else if ( load.mode == DatasetRefresh.Mode.APPEND ) {
        file.getAppended().add( load.newFile );
      }
      StoredDataset updated;
      try {
        updated = store.update( d, load.expectedVersion );
      } catch ( RuntimeException e ) {
        load.stager.drop( load.id, connection, table.getTable() );
        throw e;
      }
      if ( load.previous != null && !( load.previous.equals( table.getTable() ) && connection.equals( load.previousConnection ) ) ) {
        load.stager.drop( load.id, load.previousConnection, load.previous );
      }
      if ( sources.hasFiles() ) {
        replaced.stream().filter( DatasetFiles::isStored ).forEach( sources.files()::delete );
      }
      List<String> warnings = new ArrayList<>( table.getWarnings() );
      if ( PublishedIds.assigned( updated.getDefinition() ) && sources.canPublish() ) {
        try {
          republish( updated.getDefinition() );
          record.setRepublished( true );
        } catch ( RuntimeException e ) {
          LOG.warn( "Dataset " + load.id + " was loaded but could not be republished", e );
          record.setRepublished( false );
          warnings.add( 0, "Loaded, but reports still read the previous data: republishing failed (" + e.getMessage() + ")" );
        }
      }
      record.setState( DatasetRefresh.State.SUCCEEDED );
      record.setRows( table.getRows() );
      record.setWarnings( warnings.subList( 0, Math.min( 10, warnings.size() ) ) );
      LOG.debug( "Dataset " + load.id + " loaded " + table.getRows() + " rows into " + table.getTable() );
      return new Staging( updated, table );
    } catch ( RuntimeException e ) {
      if ( load.newFile != null && sources.hasFiles() ) {
        sources.files().delete( load.newFile.getPath() );
      }
      record.setState( DatasetRefresh.State.FAILED );
      record.setError( e instanceof CsvStager.Cancelled ? "Stopped before it finished" : e.getMessage() );
      throw e;
    } finally {
      record.setFinished( java.time.Instant.now().toString() );
      try {
        sources.history().add( load.id, record );
      } catch ( RuntimeException e ) {
        LOG.warn( "Could not record the load of dataset " + load.id, e );
      }
    }
  }

  /** Brings a published dataset's domain and catalog up to date, under the names it was first published with. */
  private void republish( DatasetDefinition d ) {
    ModelPublisher publisher = sources.publisher();
    String connection = d.getSource().getConnection();
    PhysicalSource source = PhysicalSource.of( d, connection, publisher.dataSource( connection ) );
    publisher.publish( connection, generator.generate( d, source, PublishedIds.of( d ) ) );
  }

  /** Who the dataset is shared with. Anyone who can see the dataset can see this. */
  public DatasetShare getSharing( String id ) {
    requireView();
    readable( id );
    return sharing.get( id );
  }

  /**
   * Changes who the dataset is shared with; only its owner or an administrator may. {@code expectedTag}
   * is the sharing's {@link DatasetShare#tag()} from the caller's last read. A restricted dataset
   * always keeps its owner, with write access, so nobody locks the owner out.
   */
  public DatasetShare setSharing( String id, DatasetShare share, String expectedTag ) {
    requireManage();
    DatasetDefinition d = readable( id ).getDefinition();
    if ( !isOwnerOrAdmin( d ) ) {
      throw new DatasetApiException( Code.FORBIDDEN, "Only the dataset's owner or an administrator can change who it is shared with" );
    }
    requireVersion( expectedTag );
    String now = sharing.get( id ).tag();
    if ( !now.equals( expectedTag ) ) {
      throw new DatasetApiException( Code.STALE_VERSION,
        "The sharing changed since it was read; read it again and reapply the change" );
    }
    List<DatasetProblem> problems = problems( share );
    if ( !problems.isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, problems.get( 0 ).getPath(),
        "The sharing is not valid: " + problems.get( 0 ), problems, null );
    }
    DatasetShare toStore = share.normalized();
    if ( toStore.isRestricted() && d.getOwner() != null && !d.getOwner().isEmpty() ) {
      toStore.getEntries().removeIf( e -> e.getType() == DatasetShare.RecipientType.USER && e.getRecipient().equals( d.getOwner() ) );
      toStore.getEntries().add( new DatasetShare.Entry( d.getOwner(), DatasetShare.RecipientType.USER, DatasetShare.Access.WRITE ) );
    }
    sharing.set( id, toStore );
    return sharing.get( id );
  }

  // ------------------------------------------------------------------------------------------------

  /**
   * The ids a dataset was published with, a FILE source's staging connection, table and columns, and its
   * uploaded file's name and original name are the server's to set (by publishing, uploading and loading).
   * From a client they are taken from the stored definition instead: a definition can't name another data
   * source's domain or catalog for a publish to replace, a table for the server to drop, or another
   * dataset's uploaded file to read. A file in the wizard's upload folders may still be named.
   */
  private static void keepServerFields( DatasetDefinition incoming, DatasetDefinition current ) {
    incoming.setLegacy( current == null ? null : current.getLegacy() );
    Source in = incoming.getSource();
    if ( in == null || in.getType() != SourceType.FILE ) {
      return;
    }
    Source was = current == null ? null : current.getSource();
    FileSource wasFile = was != null && was.getType() == SourceType.FILE ? was.getFile() : null;
    in.setConnection( wasFile == null ? null : was.getConnection() );
    FileSource file = in.getFile();
    if ( file == null ) {
      return;
    }
    String path = file.getPath();
    String stored = wasFile == null ? null : wasFile.getPath();
    if ( DatasetFiles.isStored( path ) && !path.equals( stored ) ) {
      String why = "names an uploaded file this dataset doesn't have; upload a file instead (PUT .../file)";
      throw new DatasetApiException( Code.VALIDATION_FAILED, "source.file.path", "The source's file.path " + why,
        List.of( new DatasetProblem( "source.file.path", why ) ), null );
    }
    boolean sameFile = path != null && path.equals( stored );
    file.setOriginalName( sameFile ? wasFile.getOriginalName() : null );
    file.setStagingTable( wasFile == null ? null : wasFile.getStagingTable() );
    file.setStagedColumns( wasFile == null ? null : wasFile.getStagedColumns() );
    file.setAppended( wasFile == null ? null : wasFile.getAppended() );
  }

  private void requireReadableSource( DatasetDefinition d ) {
    List<DatasetProblem> problems = validator.validate( d ).stream()
      .filter( p -> p.getPath().equals( "source" ) || p.getPath().startsWith( "source." ) )
      .collect( Collectors.toList() );
    if ( !problems.isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, problems.get( 0 ).getPath(),
        "The source can't be read yet: " + problems.get( 0 ), problems, null );
    }
  }

  private static String stagedTable( DatasetDefinition d ) {
    Source s = d.getSource();
    FileSource f = s == null || s.getType() != SourceType.FILE ? null : s.getFile();
    String table = f == null ? null : f.getStagingTable();
    return table == null || table.trim().isEmpty() ? null : table;
  }

  /** The dataset, if the caller may see it; otherwise NOT_FOUND, so its existence isn't revealed. */
  private StoredDataset readable( String id ) {
    StoredDataset s = load( id );
    if ( !mayRead( s.getDefinition() ) ) {
      throw notFound( id );
    }
    return s;
  }

  /** The dataset, if the caller may change it. */
  private StoredDataset writable( String id ) {
    StoredDataset s = readable( id );
    DatasetDefinition d = s.getDefinition();
    if ( !isOwnerOrAdmin( d ) && !sharing.canWrite( d.getId() ) ) {
      throw new DatasetApiException( Code.FORBIDDEN, "This dataset is shared with you to use, not to change" );
    }
    return s;
  }

  private boolean mayRead( DatasetDefinition d ) {
    return isOwnerOrAdmin( d ) || sharing.canRead( d.getId() );
  }

  private boolean isOwnerOrAdmin( DatasetDefinition d ) {
    String me = currentUser.get();
    return permissions.canAdminister() || ( me != null && me.equals( d.getOwner() ) );
  }

  private static DatasetApiException notFound( String id ) {
    return new DatasetApiException( Code.NOT_FOUND, "No dataset with id \"" + id + "\"" );
  }

  static List<DatasetProblem> problems( DatasetShare share ) {
    List<DatasetProblem> problems = new ArrayList<>();
    if ( share == null ) {
      problems.add( new DatasetProblem( "", "is required" ) );
      return problems;
    }
    List<DatasetShare.Entry> entries = share.getEntries();
    if ( !share.isRestricted() ) {
      if ( !entries.isEmpty() ) {
        problems.add( new DatasetProblem( "entries", "must be empty unless restricted is true" ) );
      }
      return problems;
    }
    if ( entries.size() > DatasetShare.MAX_ENTRIES ) {
      problems.add( new DatasetProblem( "entries", "has more than " + DatasetShare.MAX_ENTRIES + " entries" ) );
      return problems;
    }
    Set<String> seen = new HashSet<>();
    for ( int i = 0; i < entries.size(); i++ ) {
      DatasetShare.Entry e = entries.get( i );
      String at = "entries[" + i + "]";
      if ( e == null ) {
        problems.add( new DatasetProblem( at, "is empty" ) );
        continue;
      }
      String r = e.getRecipient();
      if ( r == null || r.trim().isEmpty() ) {
        problems.add( new DatasetProblem( at + ".recipient", "is required" ) );
      } else if ( r.length() > DatasetShare.MAX_RECIPIENT || r.chars().anyMatch( Character::isISOControl ) || !r.equals( r.trim() ) ) {
        problems.add( new DatasetProblem( at + ".recipient", "is not a valid user or role name" ) );
      }
      if ( e.getType() == null ) {
        problems.add( new DatasetProblem( at + ".type", "is required (USER or ROLE)" ) );
      }
      if ( e.getAccess() == null ) {
        problems.add( new DatasetProblem( at + ".access", "is required (READ or WRITE)" ) );
      }
      if ( r != null && e.getType() != null && !seen.add( e.getType() + ":" + r ) ) {
        problems.add( new DatasetProblem( at, "repeats " + e.getType().name().toLowerCase( Locale.ROOT ) + " \"" + r + "\"" ) );
      }
    }
    return problems;
  }

  private StoredDataset load( String id ) {
    requireValidId( id, "" );
    return store.load( id ).orElseThrow( () -> notFound( id ) );
  }

  private void requireView() {
    if ( !permissions.canView() ) {
      throw new DatasetApiException( Code.FORBIDDEN, "You don't have permission to see datasets" );
    }
  }

  private void requireManage() {
    if ( !permissions.canManage() ) {
      throw new DatasetApiException( Code.FORBIDDEN, "You don't have permission to create or change datasets" );
    }
  }

  private static void requireName( DatasetDefinition d ) {
    if ( d.getName() == null || d.getName().trim().isEmpty() ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "name", "A dataset needs a name",
        List.of( new DatasetProblem( "name", "is required" ) ), null );
    }
  }

  private static void requireValidId( String id, String path ) {
    if ( !DatasetPaths.isValidId( id ) ) {
      throw new DatasetApiException( Code.INVALID_DEFINITION, path,
        "\"" + id + "\" is not a valid dataset id (letters, digits, '.', '-' and '_'; starts with a letter or digit)", null, null );
    }
  }

  private static void requireVersion( String expectedVersion ) {
    if ( expectedVersion == null || expectedVersion.isEmpty() ) {
      throw new DatasetApiException( Code.VERSION_REQUIRED,
        "Say which version you are changing: send the ETag from your last read as If-Match" );
    }
  }

  /** A readable, unused id from a name. */
  String uniqueId( String name ) {
    String base = slug( name );
    String id = base;
    for ( int n = 2; store.load( id ).isPresent(); n++ ) {
      id = base + "-" + n;
    }
    return id;
  }

  static String slug( String name ) {
    String s = java.text.Normalizer.normalize( name, java.text.Normalizer.Form.NFKD )
      .replaceAll( "\\p{M}", "" )
      .toLowerCase( Locale.ROOT )
      .replaceAll( "[^a-z0-9]+", "-" )
      .replaceAll( "^-+|-+$", "" );
    if ( s.length() > MAX_GENERATED_ID ) {
      s = s.substring( 0, MAX_GENERATED_ID ).replaceAll( "-+$", "" );
    }
    return s.isEmpty() ? "dataset" : s;
  }
}
