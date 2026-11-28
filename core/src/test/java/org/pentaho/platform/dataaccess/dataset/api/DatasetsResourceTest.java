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

package org.pentaho.platform.dataaccess.dataset.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;
import org.pentaho.platform.dataaccess.dataset.service.DatasetPermissions;
import org.pentaho.platform.dataaccess.dataset.service.DatasetService;
import org.pentaho.platform.dataaccess.dataset.service.InMemoryDatasetSharing;
import org.pentaho.platform.dataaccess.dataset.service.InMemoryDatasetStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The HTTP contract: statuses, ETags, If-Match rules and JSON error bodies. */
public class DatasetsResourceTest {

  private static final String CSV = "{\"schemaVersion\":1,\"name\":\"Monthly orders\",\"source\":{\"type\":\"FILE\","
    + "\"file\":{\"format\":\"CSV\",\"path\":\"/home/suzy/orders.csv\"}},\"columns\":[{\"name\":\"amount\",\"type\":\"DECIMAL\"}]}";

  private DatasetsResource resource;
  private boolean canManage;

  @Before
  public void setUp() {
    InMemoryDatasetStore store = new InMemoryDatasetStore();
    canManage = true;
    DatasetPermissions permissions = new DatasetPermissions() {
      @Override
      public boolean canView() {
        return true;
      }

      @Override
      public boolean canManage() {
        return canManage;
      }
    };
    InMemoryDatasetSharing sharing = new InMemoryDatasetSharing( () -> "suzy", java.util.List::of );
    resource = new DatasetsResource( () -> new DatasetService( store, permissions, () -> "suzy", sharing ) );
  }

  private static JsonNode body( Response r ) throws Exception {
    return DatasetJson.mapper().readTree( (String) r.getEntity() );
  }

  private static String etag( Response r ) {
    EntityTag tag = r.getEntityTag();
    return tag == null ? null : "\"" + tag.getValue() + "\"";
  }

  @Test
  public void createsReadsUpdatesAndDeletesWithETags() throws Exception {
    Response created = resource.create( CSV );
    assertEquals( 201, created.getStatus() );
    assertEquals( "data-access/api/v2/datasets/monthly-orders", created.getLocation().toString() );
    String v1 = etag( created );
    assertEquals( "\"1\"", v1 );
    assertEquals( "suzy", body( created ).get( "owner" ).asText() );

    Response read = resource.get( "monthly-orders", null );
    assertEquals( 200, read.getStatus() );
    assertEquals( v1, etag( read ) );
    assertEquals( 304, resource.get( "monthly-orders", v1 ).getStatus() );

    Response updated = resource.update( "monthly-orders", v1, CSV.replace( "Monthly orders", "Orders by month" ) );
    assertEquals( 200, updated.getStatus() );
    String v2 = etag( updated );
    assertEquals( "Orders by month", body( updated ).get( "name" ).asText() );

    assertEquals( 204, resource.delete( "monthly-orders", v2 ).getStatus() );
    assertEquals( 404, resource.get( "monthly-orders", null ).getStatus() );
  }

  @Test
  public void refusesAStaleUpdateWith412AndChangesNothing() throws Exception {
    String v1 = etag( resource.create( CSV ) );
    String v2 = etag( resource.update( "monthly-orders", v1, CSV.replace( "Monthly orders", "Second" ) ) );

    Response stale = resource.update( "monthly-orders", v1, CSV.replace( "Monthly orders", "Lost update" ) );
    assertEquals( 412, stale.getStatus() );
    assertEquals( "stale-version", body( stale ).get( "code" ).asText() );
    Response now = resource.get( "monthly-orders", null );
    assertEquals( "Second", body( now ).get( "name" ).asText() );
    assertEquals( v2, etag( now ) );
  }

  @Test
  public void requiresIfMatchOnUpdateAndDelete() throws Exception {
    resource.create( CSV );
    Response update = resource.update( "monthly-orders", null, CSV );
    assertEquals( 428, update.getStatus() );
    assertEquals( "version-required", body( update ).get( "code" ).asText() );
    assertEquals( 428, resource.delete( "monthly-orders", " " ).getStatus() );
  }

  @Test
  public void acceptsWeakAndWildcardIfMatch() {
    String v1 = etag( resource.create( CSV ) );
    assertEquals( 200, resource.update( "monthly-orders", "W/" + v1, CSV ).getStatus() );
    assertEquals( 200, resource.update( "monthly-orders", "*", CSV ).getStatus() );
    assertEquals( "3", DatasetsResource.version( "W/\"3\"" ) );
    assertEquals( "3", DatasetsResource.version( "3" ) );
  }

  @Test
  public void answersMalformedDefinitionsWithTheFieldPath() throws Exception {
    Response bad = resource.create( CSV.replace( "\"DECIMAL\"", "\"MONEY\"" ) );
    assertEquals( 400, bad.getStatus() );
    JsonNode error = body( bad );
    assertEquals( "invalid-definition", error.get( "code" ).asText() );
    assertEquals( "columns[0].type", error.get( "path" ).asText() );
    assertTrue( error.get( "message" ).asText().contains( "must be one of" ) );

    assertEquals( "invalid-definition", body( resource.create( "" ) ).get( "code" ).asText() );
    assertEquals( "invalid-definition", body( resource.create( "{not json" ) ).get( "code" ).asText() );
  }

  @Test
  public void reportsValidationAsAListOfProblems() throws Exception {
    resource.create( "{\"schemaVersion\":1,\"name\":\"Empty\"}" );
    JsonNode result = body( resource.validate( "empty" ) );
    assertFalse( result.get( "valid" ).asBoolean() );
    assertEquals( "source", result.get( "problems" ).get( 0 ).get( "path" ).asText() );

    resource.create( CSV );
    assertTrue( body( resource.validate( "monthly-orders" ) ).get( "valid" ).asBoolean() );
  }

  @Test
  public void listsSummaries() throws Exception {
    resource.create( CSV );
    JsonNode item = body( resource.list() ).get( "datasets" ).get( 0 );
    assertEquals( "monthly-orders", item.get( "id" ).asText() );
    assertEquals( "FILE", item.get( "sourceType" ).asText() );
    assertEquals( "suzy", item.get( "owner" ).asText() );
    assertEquals( "1", item.get( "version" ).asText() );
    assertNull( item.get( "description" ) );
    assertNull( "not published yet", item.get( "published" ) );
  }

  @Test
  public void answersEveryErrorAsJsonNeverHtml() throws Exception {
    canManage = false;
    Response forbidden = resource.create( CSV );
    assertEquals( 403, forbidden.getStatus() );
    assertEquals( "application/json", forbidden.getMediaType().toString() );
    assertEquals( "forbidden", body( forbidden ).get( "code" ).asText() );

    Response unsafe = resource.get( "..", null );
    assertEquals( 400, unsafe.getStatus() );
    assertEquals( "invalid-definition", body( unsafe ).get( "code" ).asText() );
  }

  @Test
  public void sharesWithTheAclEndpointsAndItsOwnETag() throws Exception {
    resource.create( CSV );
    Response open = resource.getAcl( "monthly-orders" );
    assertEquals( 200, open.getStatus() );
    assertFalse( body( open ).get( "restricted" ).asBoolean() );
    String tag = etag( open );

    String restricted = "{\"restricted\":true,\"entries\":[{\"recipient\":\"Sales\",\"type\":\"ROLE\",\"access\":\"READ\"}]}";
    assertEquals( 428, resource.setAcl( "monthly-orders", null, restricted ).getStatus() );
    Response set = resource.setAcl( "monthly-orders", tag, restricted );
    assertEquals( 200, set.getStatus() );
    JsonNode entries = body( set ).get( "entries" );
    assertEquals( "the owner is kept", "suzy", entries.get( 0 ).get( "recipient" ).asText() );
    assertEquals( "Sales", entries.get( 1 ).get( "recipient" ).asText() );

    Response stale = resource.setAcl( "monthly-orders", tag, "{\"restricted\":false}" );
    assertEquals( 412, stale.getStatus() );
    assertEquals( 200, resource.setAcl( "monthly-orders", etag( set ), "{\"restricted\":false}" ).getStatus() );
  }

  @Test
  public void answersABadSharingBodyWithTheFieldPath() throws Exception {
    resource.create( CSV );
    String tag = etag( resource.getAcl( "monthly-orders" ) );
    JsonNode typo = body( resource.setAcl( "monthly-orders", tag,
      "{\"restricted\":true,\"entries\":[{\"recipient\":\"x\",\"type\":\"GROUP\",\"access\":\"READ\"}]}" ) );
    assertEquals( "invalid-request", typo.get( "code" ).asText() );
    assertEquals( "entries[0].type", typo.get( "path" ).asText() );

    Response incomplete = resource.setAcl( "monthly-orders", tag, "{\"restricted\":true,\"entries\":[{\"recipient\":\"x\"}]}" );
    assertEquals( 422, incomplete.getStatus() );
    assertEquals( 2, body( incomplete ).get( "problems" ).size() );
  }

  @Test
  public void saysWhatIsNotBuiltYet() throws Exception {
    resource.create( CSV );
    // No source adapters are configured in this test.
    assertEquals( 501, resource.sample( "monthly-orders", null, null ).getStatus() );
    assertEquals( "loading needs the version", 428, resource.stage( "monthly-orders", null, false ).getStatus() );
    assertEquals( 501, resource.stage( "monthly-orders", "*", false ).getStatus() );
    assertEquals( 501, resource.stage( "monthly-orders", "*", true ).getStatus() );
    assertEquals( 404, resource.loadStatus( "monthly-orders" ).getStatus() );
    java.io.InputStream body = new java.io.ByteArrayInputStream( "a\n1\n".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    assertEquals( "uploading needs the version", 428, resource.uploadFile( "monthly-orders", null, "a.csv", body ).getStatus() );
    assertEquals( 501, resource.uploadFile( "monthly-orders", "*", "a.csv", body ).getStatus() );
    assertEquals( "publishing needs the version", 428, resource.publish( "monthly-orders", null ).getStatus() );
    Response publish = resource.publish( "monthly-orders", "*" );
    assertEquals( 501, publish.getStatus() );
    assertTrue( body( publish ).get( "message" ).asText().contains( "can't publish" ) );
    assertEquals( 404, resource.publish( "missing", "*" ).getStatus() );
    assertEquals( "a file must be loaded first", 422, resource.suggestModel( "monthly-orders" ).getStatus() );
  }

  @Test
  public void listsCertificationAndRefreshWarningsAndCertifiesByPermission() throws Exception {
    InMemoryDatasetStore store = new InMemoryDatasetStore();
    org.pentaho.platform.dataaccess.dataset.source.SourceAdapters sources = org.pentaho.platform.dataaccess.dataset.source.SourceAdapters.none();
    boolean[] canCertify = { false };
    DatasetPermissions permissions = new DatasetPermissions() {
      @Override
      public boolean canView() {
        return true;
      }

      @Override
      public boolean canManage() {
        return true;
      }

      @Override
      public boolean canCertify() {
        return canCertify[0];
      }
    };
    InMemoryDatasetSharing sharing = new InMemoryDatasetSharing( () -> "suzy", java.util.List::of );
    DatasetService service = new DatasetService( store, permissions, () -> "suzy", sharing, sources );
    DatasetsResource r = new DatasetsResource( () -> service );
    r.create( CSV.replace( "Monthly orders", "Beta" ) );
    r.create( CSV.replace( "Monthly orders", "Alpha" ) );

    JsonNode list = body( r.list() );
    assertFalse( list.get( "canCertify" ).asBoolean() );
    assertEquals( 403, r.certify( "beta", "{\"note\":\"ok\"}" ).getStatus() );

    canCertify[0] = true;
    Response certified = r.certify( "beta", "{\"note\":\"Checked against the ledger\"}" );
    assertEquals( 200, certified.getStatus() );
    assertEquals( "Checked against the ledger", body( certified ).get( "certification" ).get( "note" ).asText() );
    assertEquals( 200, r.certify( "alpha", "" ).getStatus() );
    assertEquals( 400, r.certify( "alpha", "{nope" ).getStatus() );
    assertEquals( 200, r.uncertify( "alpha" ).getStatus() );

    org.pentaho.platform.dataaccess.dataset.service.DatasetRefresh failed = new org.pentaho.platform.dataaccess.dataset.service.DatasetRefresh();
    failed.setState( org.pentaho.platform.dataaccess.dataset.service.DatasetRefresh.State.FAILED );
    failed.setTrigger( org.pentaho.platform.dataaccess.dataset.service.DatasetRefresh.Trigger.SCHEDULE );
    failed.setError( "The file is missing" );
    failed.setFinished( "2026-11-28T09:00:00Z" );
    sources.history().add( "alpha", failed );

    list = body( r.list() );
    assertTrue( list.get( "canCertify" ).asBoolean() );
    JsonNode first = list.get( "datasets" ).get( 0 );
    JsonNode second = list.get( "datasets" ).get( 1 );
    assertEquals( "beta", first.get( "id" ).asText() );
    assertEquals( "suzy", first.get( "certification" ).get( "by" ).asText() );
    assertFalse( first.has( "warning" ) );
    assertEquals( "alpha", second.get( "id" ).asText() );
    assertFalse( second.has( "certification" ) );
    assertEquals( "REFRESH_FAILED", second.get( "warning" ).get( "kind" ).asText() );
    assertEquals( "SCHEDULE", second.get( "warning" ).get( "trigger" ).asText() );
    assertEquals( "The file is missing", second.get( "warning" ).get( "message" ).asText() );
    assertEquals( "2026-11-28T09:00:00Z", second.get( "warning" ).get( "at" ).asText() );
  }
}
