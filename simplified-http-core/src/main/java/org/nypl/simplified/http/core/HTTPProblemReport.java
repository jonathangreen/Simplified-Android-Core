/*
 * Copyright © 2015 <code@io7m.com> http://io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package org.nypl.simplified.http.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.io7m.jnull.NullCheck;
import org.nypl.simplified.json.core.JSONParserUtilities;

import java.io.IOException;
import java.io.InputStream;

/**
 * An HTTP problem report.
 */

public final class HTTPProblemReport
{
  private final ObjectNode raw;

  /**
   * Construct a problem report.
   *
   * @param raw_json The raw JSON comprising the report
   */

  public HTTPProblemReport(final ObjectNode raw_json)
  {
    this.raw = NullCheck.notNull(raw_json);
  }

  /**
   * Parse a problem report from the given stream.
   *
   * @param s The stream
   *
   * @return A problem report
   *
   * @throws IOException On errors
   */

  public static HTTPProblemReport fromStream(final InputStream s)
    throws IOException
  {
    NullCheck.notNull(s);
    final ObjectMapper jom = new ObjectMapper();
    final JsonNode n = jom.readTree(s);
    final ObjectNode o = JSONParserUtilities.checkObject(null, n);
    return new HTTPProblemReport(o);
  }

  /**
   * @return The raw JSON data
   */

  public ObjectNode getRawJSON()
  {
    return this.raw;
  }
}