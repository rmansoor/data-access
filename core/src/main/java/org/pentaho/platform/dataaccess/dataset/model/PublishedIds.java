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

import org.pentaho.platform.dataaccess.dataset.DatasetDefinition;
import org.pentaho.platform.dataaccess.dataset.DatasetDefinition.LegacyIds;

/**
 * The names reports use to find a published dataset: the metadata domain and model (Interactive
 * Reports, Report Designer) and the Mondrian catalog, which is also the cube's name (Analyzer). They are
 * fixed the first time a dataset is published and kept in {@code legacy}, so renaming the dataset or
 * changing its model never breaks a saved report.
 */
public final class PublishedIds {

  /** The reporting model's id, as the Data Source Wizard named it. */
  public static final String MODEL_ID = "MODEL_1";

  private final String domainId;
  private final String modelId;
  private final String catalogName;

  public PublishedIds( String domainId, String modelId, String catalogName ) {
    this.domainId = domainId;
    this.modelId = modelId;
    this.catalogName = catalogName;
  }

  /** The ids kept in the definition, with the ones not set yet taken from its id and name. */
  public static PublishedIds of( DatasetDefinition d ) {
    LegacyIds l = d.getLegacy();
    String domainId = l != null && notBlank( l.getDomainId() ) ? l.getDomainId() : d.getId() + ".xmi";
    String modelId = l != null && notBlank( l.getModelId() ) ? l.getModelId() : MODEL_ID;
    String catalog = l != null && notBlank( l.getCatalogName() ) ? l.getCatalogName() : d.getName().trim();
    return new PublishedIds( domainId, modelId, catalog );
  }

  /** Whether all three are kept in the definition already. */
  public static boolean assigned( DatasetDefinition d ) {
    LegacyIds l = d.getLegacy();
    return l != null && notBlank( l.getDomainId() ) && notBlank( l.getModelId() ) && notBlank( l.getCatalogName() );
  }

  /** Keeps these ids in the definition. */
  public void keepIn( DatasetDefinition d ) {
    LegacyIds l = d.getLegacy() == null ? new LegacyIds() : d.getLegacy();
    l.setDomainId( domainId );
    l.setModelId( modelId );
    l.setCatalogName( catalogName );
    d.setLegacy( l );
  }

  /** The same ids with another catalog name (when the first choice belongs to another data source). */
  public PublishedIds withCatalogName( String name ) {
    return new PublishedIds( domainId, modelId, name );
  }

  private static boolean notBlank( String s ) {
    return s != null && !s.trim().isEmpty();
  }

  public String getDomainId() {
    return domainId;
  }

  public String getModelId() {
    return modelId;
  }

  public String getCatalogName() {
    return catalogName;
  }
}
