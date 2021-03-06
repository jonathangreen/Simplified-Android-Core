package org.nypl.simplified.accounts.json

import org.nypl.simplified.accounts.api.AccountProviderDescriptionCollectionParserType
import org.nypl.simplified.accounts.api.AccountProviderDescriptionCollectionParsersType
import java.io.InputStream
import java.net.URI

/**
 * A provider of account description collection parsers.
 *
 * Note: MUST have a no-argument public constructor for use in [java.util.ServiceLoader].
 */

class AccountProviderDescriptionCollectionParsers : AccountProviderDescriptionCollectionParsersType {

  override fun createParser(
    uri: URI,
    stream: InputStream,
    warningsAsErrors: Boolean
  ): AccountProviderDescriptionCollectionParserType {
    return AccountProviderDescriptionCollectionParser(uri, stream, warningsAsErrors)
  }
}
