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

import org.pentaho.metadata.model.SqlDataSource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps published domains and catalogs in memory; for tests. */
public class InMemoryModelPublisher implements ModelPublisher {

  /** XMI by domain id. */
  public final Map<String, String> domains = new LinkedHashMap<>();
  /** Mondrian schema by catalog name. */
  public final Map<String, String> catalogs = new LinkedHashMap<>();
  /** The connection each catalog reads, by catalog name. */
  public final Map<String, String> catalogConnections = new LinkedHashMap<>();

  @Override
  public SqlDataSource dataSource( String connection ) {
    SqlDataSource ds = new SqlDataSource();
    ds.setType( SqlDataSource.DataSourceType.JNDI );
    ds.setDatabaseName( connection );
    ds.setDialectType( "HYPERSONIC" );
    return ds;
  }

  @Override
  public boolean domainExists( String domainId ) {
    return domains.containsKey( domainId );
  }

  @Override
  public boolean catalogExists( String catalogName ) {
    return catalogs.containsKey( catalogName );
  }

  @Override
  public void publish( String connection, DatasetModelGenerator.Generated g ) {
    domains.put( g.getIds().getDomainId(), g.getXmi() );
    if ( g.getMondrianSchema() == null ) {
      catalogs.remove( g.getIds().getCatalogName() );
      catalogConnections.remove( g.getIds().getCatalogName() );
    } else {
      catalogs.put( g.getIds().getCatalogName(), g.getMondrianSchema() );
      catalogConnections.put( g.getIds().getCatalogName(), connection );
    }
  }

  @Override
  public void unpublish( PublishedIds ids ) {
    domains.remove( ids.getDomainId() );
    catalogs.remove( ids.getCatalogName() );
    catalogConnections.remove( ids.getCatalogName() );
  }
}
