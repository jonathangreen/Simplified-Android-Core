package org.nypl.simplified.books.controller.api

import one.irradia.mime.api.MIMEType
import org.nypl.simplified.opds.core.OPDSAcquisition
import org.nypl.simplified.opds.core.OPDSAvailabilityType
import java.io.File

/**
 * An interface providing localized strings for book borrowing operations.
 */

interface BookBorrowStringResourcesType {

  /**
   * Borrowing a book based on the given availability.
   */

  fun borrowBookBorrowForAvailability(availability: OPDSAvailabilityType): String

  /**
   * Downloaded something successfully.
   */

  fun borrowBookFulfillDownloaded(
    file: File,
    contentType: MIMEType
  ): String

  /**
   * The given content type is supported for ACSM files.
   */

  fun borrowBookFulfillACSMCheckContentTypeOK(contentType: MIMEType): String

  /**
   * Saving a downloaded book.
   */

  fun borrowBookSaving(
    receivedContentType: MIMEType,
    expectedContentTypes: Set<MIMEType>
  ): String

  /**
   * Checking that the content type of the downloaded book is correct.
   */

  fun borrowBookSavingCheckingContentType(
    receivedContentType: MIMEType,
    expectedContentTypes: Set<MIMEType>
  ): String

  /**
   * A book had the wrong availability type to borrow.
   */

  fun borrowBookBorrowAvailabilityInappropriate(availability: OPDSAvailabilityType): String

  /**
   * The specified acquisition is not supported.
   */

  fun borrowBookUnsupportedAcquisition(type: OPDSAcquisition.Relation): String

  /**
   * The Adobe DRM connector failed with an error code.
   */

  fun borrowBookFulfillACSMConnectorFailed(errorCode: String): String

  /**
   * Selecting an acquisition...
   */

  val borrowBookSelectingAcquisition: String

  /**
   * An unexpected exception occurred whilst fetching the book cover.
   */

  val borrowBookCoverUnexpectedException: String

  /**
   * Saving the cover...
   */

  val borrowBookSavingCover: String

  /**
   * Fetching a cover...
   */

  val borrowBookFetchingCover: String

  /**
   * An unexpected exception occurred.
   */

  val borrowBookUnexpectedException: String

  /**
   * The Adobe DRM connector failed in an unspecified manner.
   */

  val borrowBookFulfillACSMFailed: String

  /**
   * A device is not activated and so can't be used for DRM operations.
   */

  val borrowBookFulfillACSMGettingDeviceCredentialsNotActivated: String

  /**
   * Fulfilling a book with the DRM connector succeeded.
   */

  val borrowBookFulfillACSMConnectorOK: String

  /**
   * Retrieved the needed device credentials.
   */

  val borrowBookFulfillACSMGettingDeviceCredentialsOK: String

  /**
   * Getting device credentials.
   */

  val borrowBookFulfillACSMGettingDeviceCredentials: String

  /**
   * Reading an ACSM file failed.
   */

  val borrowBookFulfillACSMReadFailed: String

  /**
   * Reading an ACSM file.
   */

  val borrowBookFulfillACSMRead: String

  /**
   * Downloading failed.
   */

  fun borrowBookFulfillDownloadFailed(cause: String): String

  /**
   * Fulfilling a book was cancelled.
   */

  val borrowBookFulfillCancelled: String

  /**
   * The received content type was unacceptable for saving.
   */

  val borrowBookSavingCheckingContentTypeUnacceptable: String

  /**
   * The received content type was OK for saving.
   */

  val borrowBookSavingCheckingContentTypeOK: String

  /**
   * The received bearer token was parsed correctly.
   */

  val borrowBookFulfillBearerTokenOK: String

  /**
   * Fulfilling a book via a bearer token.
   */

  val borrowBookFulfillBearerToken: String

  /**
   * Checking the content type that an ACSM file will deliver.
   */

  val borrowBookFulfillACSMCheckContentType: String

  /**
   * A parsed ACSM file delivers an unsupported content type.
   */

  val borrowBookFulfillACSMUnsupportedContentType: String

  /**
   * Failed to parse an ACSM file.
   */

  val borrowBookFulfillACSMParseFailed: String

  /**
   * Parsing an ACSM file.
   */

  val borrowBookFulfillACSMParse: String

  /**
   * Fulfilling an ACSM by making calls to the DRM connector.
   */

  val borrowBookFulfillACSMConnector: String

  /**
   * Couldn't fulfill a book because the bearer token was unparseable.
   */

  val borrowBookFulfillUnparseableBearerToken: String

  /**
   * Couldn't fulfill a book due to DRM being unsupported.
   */

  val borrowBookFulfillDRMNotSupported: String

  /**
   * Fulfilling a book via an ACSM file.
   */

  val borrowBookFulfillACSM: String

  /**
   * The download timed out.
   */

  val borrowBookFulfillTimedOut: String

  /**
   * Downloading something as part of fulfillment.
   */

  val borrowBookFulfillDownload: String

  /**
   * Can't fulfill a book because there are no usable acquisitions.
   */

  val borrowBookFulfillNoUsableAcquisitions: String

  /**
   * Fulfilling a loan.
   */

  val borrowBookFulfill: String

  /**
   * Copying a book from a content provider failed.
   */

  val borrowBookContentCopyFailed: String

  /**
   * Copying book from a content provider.
   */

  val borrowBookContentCopy: String

  /**
   * Copying a book from bundled content failed.
   */

  val borrowBookBundledCopyFailed: String

  /**
   * Copying book from bundled content.
   */

  val borrowBookBundledCopy: String

  /**
   * Attempting to load a feed failed.
   */

  fun borrowBookFeedLoadingFailed(cause: String): String

  /**
   * Received an unusable OPDS feed as the result of a borrow link.
   */

  val borrowBookBadBorrowFeed: String

  /**
   * Hitting the borrow link and getting the OPDS feed entry as a result.
   */

  val borrowBookGetFeedEntry: String

  /**
   * Updating the book database failed.
   */

  val borrowBookDatabaseFailed: String

  /**
   * Book borrowing started.
   */

  val borrowStarted: String

  /**
   * Setting up the database entry for the book
   */

  val borrowBookDatabaseCreateOrUpdate: String

  /**
   * Updating the book database worked
   */

  val borrowBookDatabaseUpdated: String
}
