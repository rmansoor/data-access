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

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.pentaho.platform.dataaccess.dataset.DatasetJson;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Who a dataset is shared with.
 * <p>
 * Unrestricted (the default) means everyone with the data-access view permission can use it, as
 * with every data source today. Restricted means only the listed users and roles, plus the owner
 * and administrators. Sent and returned as
 * {@code {"restricted": true, "entries": [{"recipient": "Sales", "type": "ROLE", "access": "READ"}]}}.
 */
@JsonPropertyOrder( { "restricted", "entries" } )
public class DatasetShare {

  public static final int MAX_ENTRIES = 500;
  public static final int MAX_RECIPIENT = 255;

  public enum RecipientType { USER, ROLE }

  /** READ: see and use it. WRITE: also change and delete it. */
  public enum Access { READ, WRITE }

  @JsonPropertyOrder( { "recipient", "type", "access" } )
  public static class Entry {
    private String recipient;
    private RecipientType type;
    private Access access;

    public Entry() {
    }

    public Entry( String recipient, RecipientType type, Access access ) {
      this.recipient = recipient;
      this.type = type;
      this.access = access;
    }

    public String getRecipient() {
      return recipient;
    }

    public void setRecipient( String recipient ) {
      this.recipient = recipient;
    }

    public RecipientType getType() {
      return type;
    }

    public void setType( RecipientType type ) {
      this.type = type;
    }

    public Access getAccess() {
      return access;
    }

    public void setAccess( Access access ) {
      this.access = access;
    }

    @Override
    public boolean equals( Object o ) {
      if ( !( o instanceof Entry ) ) {
        return false;
      }
      Entry e = (Entry) o;
      return Objects.equals( recipient, e.recipient ) && type == e.type && access == e.access;
    }

    @Override
    public int hashCode() {
      return Objects.hash( recipient, type, access );
    }

    @Override
    public String toString() {
      return type + ":" + recipient + "=" + access;
    }
  }

  private boolean restricted;
  private List<Entry> entries = new ArrayList<>();

  /** Shared with everyone who may use data sources. */
  public static DatasetShare unrestricted() {
    return new DatasetShare();
  }

  public static DatasetShare restricted( List<Entry> entries ) {
    DatasetShare s = new DatasetShare();
    s.setRestricted( true );
    s.setEntries( new ArrayList<>( entries ) );
    return s;
  }

  public boolean isRestricted() {
    return restricted;
  }

  public void setRestricted( boolean restricted ) {
    this.restricted = restricted;
  }

  public List<Entry> getEntries() {
    return entries;
  }

  public void setEntries( List<Entry> entries ) {
    this.entries = entries == null ? new ArrayList<>() : entries;
  }

  /** The same sharing, entries in a stable order (users first, then roles, by name). */
  public DatasetShare normalized() {
    List<Entry> sorted = new ArrayList<>( entries );
    sorted.sort( Comparator.comparing( ( Entry e ) -> e.getType() == null ? "" : e.getType().name() ).reversed()
      .thenComparing( e -> String.valueOf( e.getRecipient() ) ) );
    DatasetShare s = new DatasetShare();
    s.setRestricted( restricted );
    s.setEntries( sorted );
    return s;
  }

  /**
   * A version token for this sharing, sent as the ACL's ETag. Sharing is stored apart from the
   * definition, so it can't use the definition's version; a digest of the normalized content
   * changes exactly when the sharing does.
   */
  public String tag() {
    try {
      byte[] text = DatasetJson.mapper().writer().without( com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT )
        .writeValueAsBytes( normalized() );
      byte[] digest = MessageDigest.getInstance( "SHA-256" ).digest( text );
      StringBuilder hex = new StringBuilder();
      for ( int i = 0; i < 8; i++ ) {
        hex.append( String.format( "%02x", digest[ i ] ) );
      }
      return "acl-" + hex;
    } catch ( JsonProcessingException | NoSuchAlgorithmException e ) {
      throw new IllegalStateException( "Could not compute the sharing version", e );
    }
  }

  @Override
  public boolean equals( Object o ) {
    if ( !( o instanceof DatasetShare ) ) {
      return false;
    }
    DatasetShare a = normalized();
    DatasetShare b = ( (DatasetShare) o ).normalized();
    return a.restricted == b.restricted && a.entries.equals( b.entries );
  }

  @Override
  public int hashCode() {
    DatasetShare n = normalized();
    return Objects.hash( n.restricted, n.entries );
  }

  @Override
  public String toString() {
    return restricted ? "restricted" + normalized().entries : "unrestricted";
  }
}
