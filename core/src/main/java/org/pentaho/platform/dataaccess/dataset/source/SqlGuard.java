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

package org.pentaho.platform.dataaccess.dataset.source;

import org.pentaho.platform.dataaccess.dataset.DatasetProblem;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException;
import org.pentaho.platform.dataaccess.dataset.service.DatasetApiException.Code;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Accepts exactly one read-only query (SELECT, WITH … SELECT or VALUES) and refuses everything else
 * with a message that says why.
 * <p>
 * The SQL is scanned the way a database reads it: text values, quoted names ("…", `…`, [ … ]) and
 * comments are skipped, so a keyword or semicolon inside them doesn't count. Only what <em>every</em>
 * database treats as inert is skipped; anything some database would run is checked:
 * <ul>
 *   <li>{@code --} starts a comment only before a space (in MySQL {@code 1--1} is arithmetic);</li>
 *   <li>{@code #} and PostgreSQL {@code $$} quotes are not treated as comments or quotes;</li>
 *   <li>MySQL's executable comments ({@code /*! … *}{@code /}) and nested comments are refused;</li>
 *   <li>backslashes inside quotes are refused, because databases disagree on whether they escape the quote.</li>
 * </ul>
 * Refusing something harmless is the price of never accepting something harmful.
 * <p>
 * This is the first of three defences: the query also runs on a read-only connection inside a
 * transaction that is rolled back, with a timeout and a row cap. Side-effecting functions a database
 * offers (pg_sleep, nextval, dblink) are bounded by those, not by this check.
 */
public final class SqlGuard {

  static final String PATH = "source.sql";

  /** Statements and clauses that change data or the schema, or write files (SELECT … INTO). */
  private static final Set<String> FORBIDDEN = Set.of(
    "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO", "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
    "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "COPY", "LOCK", "UNLOCK", "VACUUM", "REINDEX", "CLUSTER",
    "ATTACH", "DETACH", "PRAGMA", "SHUTDOWN", "BACKUP", "RESTORE", "DECLARE", "PREPARE", "HANDLER", "LOAD" );

  private static final Set<String> STARTS = Set.of( "SELECT", "WITH", "VALUES" );

  private SqlGuard() {
  }

  /**
   * Checks the SQL and returns it ready to run: trimmed, without a trailing semicolon.
   *
   * @throws DatasetApiException VALIDATION_FAILED at {@code source.sql}
   */
  public static String check( String sql ) {
    if ( sql == null || sql.trim().isEmpty() ) {
      throw refuse( "is required" );
    }
    Scanner s = new Scanner( sql );
    String first = null;
    int end = sql.length();
    for ( String token = s.next(); token != null; token = s.next() ) {
      if ( token.equals( ";" ) ) {
        if ( s.next() != null ) {
          throw refuse( "must be a single statement; remove everything after the semicolon" );
        }
        end = s.lastSemicolon;
        break;
      }
      if ( first == null && !token.equals( "(" ) ) {
        first = token;
        if ( !STARTS.contains( first ) ) {
          throw refuse( "must be a query (SELECT, WITH or VALUES), not " + describe( first ) );
        }
      }
      if ( FORBIDDEN.contains( token ) ) {
        throw refuse( "can only read data; " + token + " is not allowed" );
      }
    }
    if ( first == null ) {
      throw refuse( "has no statement, only comments" );
    }
    return sql.substring( 0, end ).trim();
  }

  private static String describe( String token ) {
    return token.length() == 1 ? "\"" + token + "\"" : token;
  }

  private static DatasetApiException refuse( String message ) {
    return new DatasetApiException( Code.VALIDATION_FAILED, PATH, "The SQL " + message,
      List.of( new DatasetProblem( PATH, message ) ), null );
  }

  /** Yields upper-cased words, ";" and other single symbols, skipping literals, quoted names and comments. */
  private static final class Scanner {
    private final String sql;
    private int at;
    int lastSemicolon = -1;

    Scanner( String sql ) {
      this.sql = sql;
    }

    String next() {
      while ( at < sql.length() ) {
        char c = sql.charAt( at );
        if ( Character.isWhitespace( c ) ) {
          at++;
        } else if ( c == '-' && peek( 1 ) == '-' && ( peek( 2 ) == 0 || Character.isWhitespace( peek( 2 ) ) ) ) {
          skipLine();
        } else if ( c == '/' && peek( 1 ) == '*' ) {
          if ( peek( 2 ) == '!' ) {
            throw refuse( "has a MySQL executable comment (/*!); remove it" );
          }
          int close = sql.indexOf( "*/", at + 2 );
          if ( close < 0 ) {
            throw refuse( "has a comment that is never closed" );
          }
          if ( sql.substring( at + 2, close ).contains( "/*" ) ) {
            throw refuse( "has a nested comment; databases read those differently" );
          }
          at = close + 2;
        } else if ( c == '\'' ) {
          skipQuoted( '\'', true, "a text value that is never closed" );
        } else if ( c == '"' ) {
          // A name in standard SQL, but a text value in MySQL, where backslash escapes: checked like one.
          skipQuoted( '"', true, "a quoted name that is never closed" );
        } else if ( c == '`' ) {
          skipQuoted( '`', false, "a quoted name that is never closed" );
        } else if ( c == '[' && bracketedName() ) {
          continue;
        } else if ( Character.isLetterOrDigit( c ) || c == '_' || c == '$' ) {
          int start = at;
          while ( at < sql.length() && ( Character.isLetterOrDigit( sql.charAt( at ) ) || sql.charAt( at ) == '_'
            || sql.charAt( at ) == '$' ) ) {
            at++;
          }
          return sql.substring( start, at ).toUpperCase( Locale.ROOT );
        } else {
          if ( c == ';' ) {
            lastSemicolon = at;
          }
          at++;
          return String.valueOf( c );
        }
      }
      return null;
    }

    private char peek( int ahead ) {
      return at + ahead < sql.length() ? sql.charAt( at + ahead ) : 0;
    }

    private void skipLine() {
      while ( at < sql.length() && sql.charAt( at ) != '\n' && sql.charAt( at ) != '\r' ) {
        at++;
      }
    }

    /** Skips a quoted run in which a doubled quote stands for one quote. */
    private void skipQuoted( char quote, boolean literal, String unclosed ) {
      at++;
      while ( at < sql.length() ) {
        char c = sql.charAt( at );
        if ( literal && c == '\\' ) {
          throw refuse( "has a backslash inside quotes; databases disagree on what it means, so write it another way" );
        }
        if ( c == quote ) {
          if ( peek( 1 ) == quote ) {
            at += 2;
            continue;
          }
          at++;
          return;
        }
        at++;
      }
      throw refuse( "has " + unclosed );
    }

    /**
     * A SQL Server name such as [Order Details]: skipped when it holds nothing a statement could hide
     * behind. Otherwise (a PostgreSQL subscript, for instance) its contents are checked like any SQL.
     */
    private boolean bracketedName() {
      int close = sql.indexOf( ']', at + 1 );
      if ( close < 0 ) {
        return false;
      }
      String inside = sql.substring( at + 1, close );
      if ( inside.indexOf( ';' ) >= 0 || inside.indexOf( '\'' ) >= 0 || inside.indexOf( '"' ) >= 0 || inside.contains( "--" )
        || inside.contains( "/*" ) ) {
        return false;
      }
      at = close + 1;
      return true;
    }
  }
}
