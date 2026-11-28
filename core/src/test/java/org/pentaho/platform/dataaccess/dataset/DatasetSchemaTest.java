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

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Keeps the published JSON Schema in step with the Java model: every field the server reads is in
 * the schema, every schema field exists in the model, and enum values match.
 */
public class DatasetSchemaTest {

  private static final ObjectMapper MAPPER = DatasetJson.mapper();

  private static JsonNode schema() throws IOException {
    try ( InputStream in = DatasetDefinition.class.getResourceAsStream( "dataset-definition.schema.json" ) ) {
      assertNotNull( "schema resource is packaged next to DatasetDefinition", in );
      return MAPPER.readTree( in );
    }
  }

  private static Set<String> beanProperties( Class<?> type ) {
    BeanDescription description = MAPPER.getDeserializationConfig().introspect( MAPPER.constructType( type ) );
    return description.findProperties().stream().map( BeanPropertyDefinition::getName ).collect( Collectors.toCollection( TreeSet::new ) );
  }

  private static Set<String> schemaProperties( JsonNode node ) {
    Set<String> names = new TreeSet<>();
    node.get( "properties" ).fieldNames().forEachRemaining( names::add );
    return names;
  }

  @Test
  public void describesExactlyTheFieldsTheServerReads() throws IOException {
    JsonNode schema = schema();
    Map<String, Class<?>> defs = new LinkedHashMap<>();
    defs.put( "source", DatasetDefinition.Source.class );
    defs.put( "file", DatasetDefinition.FileSource.class );
    defs.put( "column", DatasetDefinition.Column.class );
    defs.put( "columnRef", DatasetDefinition.ColumnRef.class );
    defs.put( "model", DatasetDefinition.Model.class );
    defs.put( "dimension", DatasetDefinition.Dimension.class );
    defs.put( "measure", DatasetDefinition.Measure.class );
    defs.put( "relationship", DatasetDefinition.Relationship.class );
    defs.put( "security", DatasetDefinition.Security.class );
    defs.put( "rowRule", DatasetDefinition.RowRule.class );
    defs.put( "refresh", DatasetDefinition.Refresh.class );
    defs.put( "legacy", DatasetDefinition.LegacyIds.class );

    assertEquals( "root", beanProperties( DatasetDefinition.class ), schemaProperties( schema ) );
    for ( Map.Entry<String, Class<?>> def : defs.entrySet() ) {
      JsonNode node = schema.get( "$defs" ).get( def.getKey() );
      assertNotNull( "$defs." + def.getKey(), node );
      assertEquals( "$defs." + def.getKey(), beanProperties( def.getValue() ), schemaProperties( node ) );
    }
    Set<String> schemaDefs = new TreeSet<>();
    schema.get( "$defs" ).fieldNames().forEachRemaining( schemaDefs::add );
    assertEquals( "every $defs entry maps to a class", new TreeSet<>( defs.keySet() ), schemaDefs );
  }

  @Test
  public void listsTheSameEnumValuesAsTheModel() throws IOException {
    JsonNode defs = schema().get( "$defs" );
    assertEnum( defs.at( "/source/properties/type/enum" ), DatasetDefinition.SourceType.class );
    assertEnum( defs.at( "/file/properties/format/enum" ), DatasetDefinition.FileFormat.class );
    assertEnum( defs.at( "/column/properties/type/enum" ), DatasetDefinition.ColumnType.class );
    assertEnum( defs.at( "/measure/properties/aggregation/enum" ), DatasetDefinition.Aggregation.class );
    assertEnum( defs.at( "/relationship/properties/joinType/enum" ), DatasetDefinition.JoinType.class );
    assertEnum( defs.at( "/relationship/properties/cardinality/enum" ), DatasetDefinition.Cardinality.class );
    assertEnum( defs.at( "/refresh/properties/mode/enum" ), DatasetDefinition.RefreshMode.class );
  }

  @Test
  public void pinsTheVersionThisReleaseWrites() throws IOException {
    assertEquals( DatasetUpgrades.CURRENT_VERSION, schema().at( "/properties/schemaVersion/const" ).intValue() );
  }

  private static void assertEnum( JsonNode values, Class<? extends Enum<?>> type ) {
    assertEquals( type.getSimpleName(),
      Arrays.stream( type.getEnumConstants() ).map( Enum::name ).collect( Collectors.toList() ),
      StreamSupport.stream( values.spliterator(), false ).map( JsonNode::asText ).collect( Collectors.toList() ) );
  }
}
