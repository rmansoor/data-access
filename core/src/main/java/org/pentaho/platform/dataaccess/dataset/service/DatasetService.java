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
import org.pentaho.platform.dataaccess.dataset.source.CsvStager;
import org.pentaho.platform.dataaccess.dataset.source.DatasetFiles;
import org.pentaho.platform.dataaccess.dataset.source.Sample;
import org.pentaho.platform.dataaccess.dataset.source.StagedTable;
import org.pentaho.platform.dataaccess.dataset.source.TableList;
import org.pentaho.platform.dataaccess.dataset.source.SourceAdapters;
import org.pentaho.platform.dataaccess.dataset.source.SourceLimits;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
      sources.stager().drop( d.getSource().getConnection(), staged );
    }
    if ( sources.hasFiles() ) {
      sources.files().deleteAll( id );
    }
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
    String connection = stager.connection();
    if ( source.getConnection() != null && !source.getConnection().trim().isEmpty()
      && !source.getConnection().trim().equals( connection ) ) {
      String why = "must be the staging connection (\"" + connection + "\") or left out; uploaded files are loaded only there";
      throw new DatasetApiException( Code.VALIDATION_FAILED, "source.connection", "The source's connection " + why,
        List.of( new DatasetProblem( "source.connection", why ) ), null );
    }
    String previous = stagedTable( d );
    StagedTable table = stager.stage( id, source, d.getColumns() );
    source.setConnection( connection );
    source.getFile().setStagingTable( table.getTable() );
    StoredDataset updated;
    try {
      updated = store.update( d, expectedVersion );
    } catch ( RuntimeException e ) {
      stager.drop( connection, table.getTable() );
      throw e;
    }
    if ( previous != null && !previous.equals( table.getTable() ) ) {
      stager.drop( connection, previous );
    }
    LOG.debug( "Dataset " + id + " loaded " + table.getRows() + " rows into " + table.getTable() );
    return new Staging( updated, table );
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
