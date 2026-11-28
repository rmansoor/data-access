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

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.metadata.model.SqlDataSource;
import org.pentaho.metadata.repository.IMetadataDomainRepository;
import org.pentaho.metadata.util.ThinModelConverter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;
import org.pentaho.platform.dataaccess.dataset.source.ConnectionProvider;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.plugin.action.mondrian.catalog.IMondrianCatalogService;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalog;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianSchema;
import org.pentaho.platform.plugin.action.olap.IOlapService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * Publishes to the platform as the Data Source Wizard did: the domain into the metadata repository
 * and the schema as a Mondrian catalog over the dataset's connection, both as the system, after the
 * caller's own right to manage datasets has been checked.
 */
public class PlatformModelPublisher implements ModelPublisher {

  private static final Log LOG = LogFactory.getLog( PlatformModelPublisher.class );
  private final ConnectionProvider connections;

  public PlatformModelPublisher( ConnectionProvider connections ) {
    this.connections = connections;
  }

  /**
   * The connection by name (the platform resolves its own connections and JNDI names alike), with the
   * dialect its database reports, and every field quoted, as the wizard described its staging connection.
   */
  @Override
  public SqlDataSource dataSource( String connection ) {
    String product;
    try ( Connection c = connections.open( connection ) ) {
      product = c.getMetaData().getDatabaseProductName();
    } catch ( NoSuchElementException e ) {
      throw new DatasetApiException( Code.VALIDATION_FAILED, "source.connection", "No connection named \"" + connection + "\"",
        null, e );
    } catch ( SQLException e ) {
      throw new DatasetApiException( Code.SOURCE_FAILED, "source.connection",
        "The connection \"" + connection + "\" could not be opened: " + e.getMessage(), null, e );
    }
    DatabaseMeta meta = new DatabaseMeta();
    meta.setAccessType( DatabaseMeta.TYPE_ACCESS_JNDI );
    meta.setDBName( connection );
    meta.setName( connection );
    meta.setDatabaseType( dialect( product ) );
    meta.setQuoteAllFields( true );
    return ThinModelConverter.convertFromLegacy( meta );
  }

  /** The Kettle database type for a JDBC product name, as the wizard mapped it. */
  static String dialect( String product ) {
    if ( product == null ) {
      return "GENERIC";
    } else if ( product.contains( "HSQL" ) ) {
      return "Hypersonic";
    } else if ( product.contains( "Microsoft SQL" ) ) {
      return "MSSQLNATIVE";
    }
    return product;
  }

  @Override
  public boolean domainExists( String domainId ) {
    IMetadataDomainRepository repo = metadata();
    return repo.getDomainIds().contains( domainId );
  }

  @Override
  public boolean catalogExists( String catalogName ) {
    return mondrian().getCatalog( catalogName, PentahoSessionHolder.getSession() ) != null;
  }

  @Override
  public void publish( String connection, DatasetModelGenerator.Generated g ) {
    asSystem( () -> {
      metadata().storeDomain( g.getDomain(), true );
      IPentahoSession session = PentahoSessionHolder.getSession();
      IMondrianCatalogService catalogs = mondrian();
      String name = g.getIds().getCatalogName();
      if ( catalogs.getCatalog( name, session ) != null ) {
        flushSchema( name );
        catalogs.removeCatalog( name, session ); // drops annotations applied to the earlier version too
      }
      if ( g.getMondrianSchema() != null ) {
        // EnableXmla stated, as the analysis import does: a catalog without it makes XMLA discovery fail server-wide.
        MondrianCatalog catalog = new MondrianCatalog( name, "Provider=mondrian;DataSource=\"" + connection + "\";EnableXmla=false", "",
          new MondrianSchema( name, new ArrayList<>() ) );
        catalogs.addCatalog( new ByteArrayInputStream( g.getMondrianSchema().getBytes( StandardCharsets.UTF_8 ) ), catalog,
          true, session );
      }
      return null;
    } );
  }

  @Override
  public void unpublish( PublishedIds ids ) {
    asSystem( () -> {
      IMetadataDomainRepository repo = metadata();
      if ( repo.getDomainIds().contains( ids.getDomainId() ) ) {
        repo.removeDomain( ids.getDomainId() );
      }
      IPentahoSession session = PentahoSessionHolder.getSession();
      if ( mondrian().getCatalog( ids.getCatalogName(), session ) != null ) {
        flushSchema( ids.getCatalogName() );
        mondrian().removeCatalog( ids.getCatalogName(), session );
      }
      return null;
    } );
  }

  /**
   * Drops Mondrian's cached copy of the catalog's schema, before the catalog is replaced: replacing it
   * doesn't, so Analyzer would keep answering from the old model until the server restarts. Flushing
   * afterwards would find the new schema (its content is part of the cache key) and miss the old one.
   */
  private static void flushSchema( String catalogName ) {
    IOlapService olap = PentahoSystem.get( IOlapService.class, PentahoSessionHolder.getSession() );
    if ( olap == null ) {
      return;
    }
    try {
      olap.flush( PentahoSessionHolder.getSession(), catalogName );
    } catch ( RuntimeException e ) {
      LOG.warn( "Published, but Mondrian's cache of \"" + catalogName + "\" could not be flushed", e );
    }
  }

  private static IMetadataDomainRepository metadata() {
    IMetadataDomainRepository repo = PentahoSystem.get( IMetadataDomainRepository.class, PentahoSessionHolder.getSession() );
    if ( repo == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server has no metadata repository" );
    }
    return repo;
  }

  private static IMondrianCatalogService mondrian() {
    IMondrianCatalogService s = PentahoSystem.get( IMondrianCatalogService.class, "IMondrianCatalogService",
      PentahoSessionHolder.getSession() );
    if ( s == null ) {
      throw new DatasetApiException( Code.NOT_IMPLEMENTED, "This server has no Mondrian catalog service" );
    }
    return s;
  }

  private static void asSystem( java.util.concurrent.Callable<Void> work ) {
    try {
      SecurityHelper.getInstance().runAsSystem( work );
    } catch ( DatasetApiException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new DatasetApiException( Code.INTERNAL, "", "The dataset could not be published: " + e.getMessage(), null, e );
    }
  }
}
