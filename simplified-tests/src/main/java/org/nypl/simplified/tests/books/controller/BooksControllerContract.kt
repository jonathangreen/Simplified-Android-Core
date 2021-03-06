package org.nypl.simplified.tests.books.controller

import android.content.ContentResolver
import android.content.Context
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.io7m.jfunctional.Option
import io.reactivex.subjects.PublishSubject
import org.hamcrest.core.IsInstanceOf
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.mockito.Mockito
import org.nypl.simplified.accounts.api.AccountAuthenticationCredentials
import org.nypl.simplified.accounts.api.AccountEvent
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoggedIn
import org.nypl.simplified.accounts.api.AccountLoginState.AccountNotLoggedIn
import org.nypl.simplified.accounts.api.AccountLoginStringResourcesType
import org.nypl.simplified.accounts.api.AccountLogoutStringResourcesType
import org.nypl.simplified.accounts.api.AccountPassword
import org.nypl.simplified.accounts.api.AccountProviderResolutionStringsType
import org.nypl.simplified.accounts.api.AccountUsername
import org.nypl.simplified.accounts.database.AccountBundledCredentialsEmpty
import org.nypl.simplified.accounts.database.AccountsDatabases
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryType
import org.nypl.simplified.analytics.api.AnalyticsType
import org.nypl.simplified.books.api.BookEvent
import org.nypl.simplified.books.api.BookID
import org.nypl.simplified.books.audio.AudioBookManifestStrategiesType
import org.nypl.simplified.books.book_database.api.BookFormats
import org.nypl.simplified.books.book_registry.BookRegistry
import org.nypl.simplified.books.book_registry.BookRegistryType
import org.nypl.simplified.books.book_registry.BookStatus
import org.nypl.simplified.books.book_registry.BookStatusEvent
import org.nypl.simplified.books.bundled.api.BundledContentResolverType
import org.nypl.simplified.books.controller.Controller
import org.nypl.simplified.books.controller.api.BookBorrowStringResourcesType
import org.nypl.simplified.books.controller.api.BookRevokeStringResourcesType
import org.nypl.simplified.books.controller.api.BooksControllerType
import org.nypl.simplified.clock.Clock
import org.nypl.simplified.clock.ClockType
import org.nypl.simplified.downloader.core.DownloaderHTTP
import org.nypl.simplified.downloader.core.DownloaderType
import org.nypl.simplified.feeds.api.FeedHTTPTransport
import org.nypl.simplified.feeds.api.FeedLoader
import org.nypl.simplified.feeds.api.FeedLoaderType
import org.nypl.simplified.files.DirectoryUtilities
import org.nypl.simplified.http.core.HTTPProblemReport
import org.nypl.simplified.http.core.HTTPResultError
import org.nypl.simplified.http.core.HTTPResultOK
import org.nypl.simplified.http.core.HTTPType
import org.nypl.simplified.opds.auth_document.api.AuthenticationDocumentParsersType
import org.nypl.simplified.opds.core.OPDSAcquisitionFeedEntryParser
import org.nypl.simplified.opds.core.OPDSFeedParser
import org.nypl.simplified.opds.core.OPDSFeedParserType
import org.nypl.simplified.opds.core.OPDSParseException
import org.nypl.simplified.opds.core.OPDSSearchParser
import org.nypl.simplified.patron.api.PatronUserProfileParsersType
import org.nypl.simplified.profiles.ProfilesDatabases
import org.nypl.simplified.profiles.api.ProfileDatabaseException
import org.nypl.simplified.profiles.api.ProfileEvent
import org.nypl.simplified.profiles.api.ProfilesDatabaseType
import org.nypl.simplified.profiles.api.idle_timer.ProfileIdleTimerType
import org.nypl.simplified.profiles.controller.api.ProfileAccountCreationStringResourcesType
import org.nypl.simplified.profiles.controller.api.ProfileAccountDeletionStringResourcesType
import org.nypl.simplified.tests.EventAssertions
import org.nypl.simplified.tests.MockAccountProviders
import org.nypl.simplified.tests.MockAnalytics
import org.nypl.simplified.tests.MutableServiceDirectory
import org.nypl.simplified.tests.books.accounts.FakeAccountCredentialStorage
import org.nypl.simplified.tests.books.idle_timer.InoperableIdleTimer
import org.nypl.simplified.tests.http.MockingHTTP
import org.nypl.simplified.tests.strings.MockAccountCreationStringResources
import org.nypl.simplified.tests.strings.MockAccountDeletionStringResources
import org.nypl.simplified.tests.strings.MockAccountLoginStringResources
import org.nypl.simplified.tests.strings.MockAccountLogoutStringResources
import org.nypl.simplified.tests.strings.MockAccountProviderResolutionStrings
import org.nypl.simplified.tests.strings.MockBorrowStringResources
import org.nypl.simplified.tests.strings.MockRevokeStringResources
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.NoSuchElementException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class BooksControllerContract {

  private val logger = LoggerFactory.getLogger(BooksControllerContract::class.java)

  @JvmField
  @Rule
  val expected = ExpectedException.none()

  private lateinit var accountEvents: PublishSubject<AccountEvent>
  private lateinit var accountEventsReceived: MutableList<AccountEvent>
  private lateinit var audioBookManifestStrategies: AudioBookManifestStrategiesType
  private lateinit var authDocumentParsers: AuthenticationDocumentParsersType
  private lateinit var bookEvents: MutableList<BookEvent>
  private lateinit var bookRegistry: BookRegistryType
  private lateinit var cacheDirectory: File
  private lateinit var contentResolver: ContentResolver
  private lateinit var credentialsStore: FakeAccountCredentialStorage
  private lateinit var directoryDownloads: File
  private lateinit var directoryProfiles: File
  private lateinit var downloader: DownloaderType
  private lateinit var executorBooks: ListeningExecutorService
  private lateinit var executorDownloads: ListeningExecutorService
  private lateinit var executorFeeds: ListeningExecutorService
  private lateinit var executorTimer: ListeningExecutorService
  private lateinit var http: MockingHTTP
  private lateinit var patronUserProfileParsers: PatronUserProfileParsersType
  private lateinit var profileEvents: PublishSubject<ProfileEvent>
  private lateinit var profileEventsReceived: MutableList<ProfileEvent>
  private lateinit var profiles: ProfilesDatabaseType

  protected abstract fun context(): Context

  private val accountProviderResolutionStrings =
    MockAccountProviderResolutionStrings()
  private val accountLoginStringResources =
    MockAccountLoginStringResources()
  private val accountLogoutStringResources =
    MockAccountLogoutStringResources()
  private val bookBorrowStringResources =
    MockBorrowStringResources()
  private val revokeStringResources =
    MockRevokeStringResources()
  private val profileAccountDeletionStringResources =
    MockAccountDeletionStringResources()
  private val profileAccountCreationStringResources =
    MockAccountCreationStringResources()
  private val analytics =
    MockAnalytics()

  private fun correctCredentials(): AccountAuthenticationCredentials {
    return AccountAuthenticationCredentials.Basic(
      userName = AccountUsername("abcd"),
      password = AccountPassword("1234"),
      adobeCredentials = null,
      authenticationDescription = null
    )
  }

  private fun createController(
    exec: ExecutorService,
    feedExecutor: ListeningExecutorService,
    accountEvents: PublishSubject<AccountEvent>,
    profileEvents: PublishSubject<ProfileEvent>,
    http: HTTPType,
    books: BookRegistryType,
    profiles: ProfilesDatabaseType,
    downloader: DownloaderType,
    accountProviders: AccountProviderRegistryType,
    timerExec: ExecutorService,
    patronUserProfileParsers: PatronUserProfileParsersType
  ): BooksControllerType {

    val parser = OPDSFeedParser.newParser(
      OPDSAcquisitionFeedEntryParser.newParser(BookFormats.supportedBookMimeTypes()))
    val transport =
      FeedHTTPTransport.newTransport(http)

    val bundledContent =
      BundledContentResolverType { uri -> throw FileNotFoundException(uri.toString()) }

    val feedLoader =
      FeedLoader.create(
        exec = feedExecutor,
        parser = parser,
        searchParser = OPDSSearchParser.newParser(),
        transport = transport,
        bookRegistry = books,
        bundledContent = bundledContent,
        contentResolver = this.contentResolver
      )

    val services = MutableServiceDirectory()
    services.putService(
      AudioBookManifestStrategiesType::class.java, this.audioBookManifestStrategies)
    services.putService(
      AnalyticsType::class.java, this.analytics)
    services.putService(
      AccountLoginStringResourcesType::class.java, this.accountLoginStringResources)
    services.putService(
      AccountLogoutStringResourcesType::class.java, this.accountLogoutStringResources)
    services.putService(
      AccountProviderResolutionStringsType::class.java, this.accountProviderResolutionStrings)
    services.putService(
      AccountProviderRegistryType::class.java, accountProviders)
    services.putService(
      AuthenticationDocumentParsersType::class.java, this.authDocumentParsers)
    services.putService(
      BookRegistryType::class.java, this.bookRegistry)
    services.putService(
      BookBorrowStringResourcesType::class.java, this.bookBorrowStringResources)
    services.putService(
      BundledContentResolverType::class.java, bundledContent)
    services.putService(
      DownloaderType::class.java, downloader)
    services.putService(
      FeedLoaderType::class.java, feedLoader)
    services.putService(
      OPDSFeedParserType::class.java, parser)
    services.putService(
      HTTPType::class.java, http)
    services.putService(
      PatronUserProfileParsersType::class.java, patronUserProfileParsers)
    services.putService(
      ProfileAccountCreationStringResourcesType::class.java, profileAccountCreationStringResources)
    services.putService(
      ProfileAccountDeletionStringResourcesType::class.java, profileAccountDeletionStringResources)
    services.putService(
      ProfilesDatabaseType::class.java, profiles)
    services.putService(
      BookRevokeStringResourcesType::class.java, revokeStringResources)
    services.putService(
      ProfileIdleTimerType::class.java, InoperableIdleTimer())
    services.putService(
      ClockType::class.java, Clock)

    return Controller.createFromServiceDirectory(
      services = services,
      executorService = exec,
      accountEvents = accountEvents,
      profileEvents = profileEvents,
      cacheDirectory = this.cacheDirectory,
      contentResolver = this.contentResolver
    )
  }

  @Before
  @Throws(Exception::class)
  fun setUp() {
    this.audioBookManifestStrategies = Mockito.mock(AudioBookManifestStrategiesType::class.java)
    this.credentialsStore = FakeAccountCredentialStorage()
    this.http = MockingHTTP()
    this.authDocumentParsers = Mockito.mock(AuthenticationDocumentParsersType::class.java)
    this.executorDownloads = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.executorBooks = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.executorTimer = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.executorFeeds = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.directoryDownloads = DirectoryUtilities.directoryCreateTemporary()
    this.directoryProfiles = DirectoryUtilities.directoryCreateTemporary()
    this.profileEvents = PublishSubject.create<ProfileEvent>()
    this.profileEventsReceived = Collections.synchronizedList(ArrayList())
    this.accountEvents = PublishSubject.create<AccountEvent>()
    this.accountEventsReceived = Collections.synchronizedList(ArrayList())
    this.profiles = profilesDatabaseWithoutAnonymous(this.accountEvents, this.directoryProfiles)
    this.bookEvents = Collections.synchronizedList(ArrayList())
    this.bookRegistry = BookRegistry.create()
    this.contentResolver = Mockito.mock(ContentResolver::class.java)
    this.cacheDirectory = File.createTempFile("book-borrow-tmp", "dir")
    this.cacheDirectory.delete()
    this.cacheDirectory.mkdirs()
    this.downloader = DownloaderHTTP.newDownloader(this.executorDownloads, this.directoryDownloads, this.http)
    this.patronUserProfileParsers = Mockito.mock(PatronUserProfileParsersType::class.java)
  }

  @After
  @Throws(Exception::class)
  fun tearDown() {
    this.executorBooks.shutdown()
    this.executorFeeds.shutdown()
    this.executorDownloads.shutdown()
    this.executorTimer.shutdown()
  }

  /**
   * If the remote side returns a non 401 error code, syncing should fail with an IO exception.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncRemoteNon401() {
    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultError(
        400,
        "BAD REQUEST",
        0L,
        HashMap(),
        0L,
        ByteArrayInputStream(ByteArray(0)),
        Option.none<HTTPProblemReport>()))

    this.expected.expect(ExecutionException::class.java)
    this.expected.expectCause(IsInstanceOf.instanceOf(IOException::class.java))
    controller.booksSync(account).get()
  }

  /**
   * If the remote side returns a 401 error code, the current credentials should be thrown away.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncRemote401() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultError(
        401,
        "UNAUTHORIZED",
        0L,
        HashMap(),
        0L,
        ByteArrayInputStream(ByteArray(0)),
        Option.none<HTTPProblemReport>()))

    controller.booksSync(account).get()
    Assert.assertEquals(AccountNotLoggedIn, account.loginState)
  }

  /**
   * If the provider does not support authentication, then syncing is impossible and does nothing.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncWithoutAuthSupport() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeProvider("urn:fake:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    Assert.assertEquals(AccountLoggedIn(correctCredentials()), account.loginState)
    controller.booksSync(account).get()
    Assert.assertEquals(AccountLoggedIn(correctCredentials()), account.loginState)
  }

  /**
   * If the remote side requires authentication but no credentials were provided, nothing happens.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncMissingCredentials() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!

    Assert.assertEquals(AccountNotLoggedIn, account.loginState)
    controller.booksSync(account).get()
    Assert.assertEquals(AccountNotLoggedIn, account.loginState)
  }

  /**
   * If the remote side returns garbage for a feed, an error is raised.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncBadFeed() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultOK<InputStream>(
        "OK",
        200,
        ByteArrayInputStream(byteArrayOf(0x23, 0x10, 0x39, 0x59)),
        4L,
        HashMap(),
        0L))

    this.expected.expect(ExecutionException::class.java)
    this.expected.expectCause(IsInstanceOf.instanceOf(OPDSParseException::class.java))
    controller.booksSync(account).get()
  }

  /**
   * If the remote side returns books the account doesn't have, new database entries are created.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncNewEntries() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultOK(
        "OK",
        200,
        resource("testBooksSyncNewEntries.xml"),
        resourceSize("testBooksSyncNewEntries.xml"),
        HashMap(),
        0L))

    this.bookRegistry.bookEvents().subscribe({ this.bookEvents.add(it) })

    Assert.assertEquals(0L, this.bookRegistry.books().size.toLong())
    controller.booksSync(account).get()
    Assert.assertEquals(3L, this.bookRegistry.books().size.toLong())

    this.bookRegistry.bookOrException(
      BookID.create("39434e1c3ea5620fdcc2303c878da54cc421175eb09ce1a6709b54589eb8711f"))
    this.bookRegistry.bookOrException(
      BookID.create("f9a7536a61caa60f870b3fbe9d4304b2d59ea03c71cbaee82609e3779d1e6e0f"))
    this.bookRegistry.bookOrException(
      BookID.create("251cc5f69cd2a329bb6074b47a26062e59f5bb01d09d14626f41073f63690113"))

    EventAssertions.isTypeAndMatches(
      BookStatusEvent::class.java,
      this.bookEvents,
      0
    ) { e -> Assert.assertEquals(e.type(), BookStatusEvent.Type.BOOK_CHANGED) }
    EventAssertions.isTypeAndMatches(
      BookStatusEvent::class.java,
      this.bookEvents,
      1
    ) { e -> Assert.assertEquals(e.type(), BookStatusEvent.Type.BOOK_CHANGED) }
    EventAssertions.isTypeAndMatches(
      BookStatusEvent::class.java,
      this.bookEvents,
      2
    ) { e -> Assert.assertEquals(e.type(), BookStatusEvent.Type.BOOK_CHANGED) }
  }

  /**
   * If the remote side returns few books than the account has, database entries are removed.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksSyncRemoveEntries() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    /*
     * Populate the database by syncing against a feed that contains books.
     */

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultOK(
        "OK",
        200,
        resource("testBooksSyncNewEntries.xml"),
        resourceSize("testBooksSyncNewEntries.xml"),
        HashMap(),
        0L))

    controller.booksSync(account).get()

    this.bookRegistry.bookOrException(
      BookID.create("39434e1c3ea5620fdcc2303c878da54cc421175eb09ce1a6709b54589eb8711f"))
    this.bookRegistry.bookOrException(
      BookID.create("f9a7536a61caa60f870b3fbe9d4304b2d59ea03c71cbaee82609e3779d1e6e0f"))
    this.bookRegistry.bookOrException(
      BookID.create("251cc5f69cd2a329bb6074b47a26062e59f5bb01d09d14626f41073f63690113"))

    this.bookRegistry.bookEvents().subscribe({ this.bookEvents.add(it) })

    /*
     * Now run the sync again but this time with a feed that removes books.
     */

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultOK(
        "OK",
        200,
        resource("testBooksSyncRemoveEntries.xml"),
        resourceSize("testBooksSyncRemoveEntries.xml"),
        HashMap(),
        0L))

    controller.booksSync(account).get()
    Assert.assertEquals(1L, this.bookRegistry.books().size.toLong())

    EventAssertions.isTypeAndMatches(
      BookStatusEvent::class.java,
      this.bookEvents,
      0
    ) { e -> Assert.assertEquals(e.type(), BookStatusEvent.Type.BOOK_CHANGED) }
    EventAssertions.isTypeAndMatches(
      BookStatusEvent::class.java,
      this.bookEvents,
      1
    ) { e -> Assert.assertEquals(e.type(), BookStatusEvent.Type.BOOK_REMOVED) }
    EventAssertions.isTypeAndMatches(
      BookStatusEvent::class.java,
      this.bookEvents,
      2
    ) { e -> Assert.assertEquals(e.type(), BookStatusEvent.Type.BOOK_REMOVED) }

    this.bookRegistry.bookOrException(
      BookID.create("39434e1c3ea5620fdcc2303c878da54cc421175eb09ce1a6709b54589eb8711f"))

    checkBookIsNotInRegistry("f9a7536a61caa60f870b3fbe9d4304b2d59ea03c71cbaee82609e3779d1e6e0f")
    checkBookIsNotInRegistry("251cc5f69cd2a329bb6074b47a26062e59f5bb01d09d14626f41073f63690113")
  }

  private fun checkBookIsNotInRegistry(id: String) {
    try {
      this.bookRegistry.bookOrException(BookID.create(id))
      Assert.fail("Book should not exist!")
    } catch (e: NoSuchElementException) {
      // Correctly raised
    }
  }

  /**
   * Deleting a book works.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksDelete() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultOK(
        "OK",
        200,
        resource("testBooksDelete.xml"),
        resourceSize("testBooksDelete.xml"),
        HashMap(),
        0L))

    controller.booksSync(account).get()

    val bookId = BookID.create("39434e1c3ea5620fdcc2303c878da54cc421175eb09ce1a6709b54589eb8711f")

    Assert.assertFalse(
      "Book must not have a saved EPUB file",
      this.bookRegistry.bookOrException(bookId)
        .book
        .isDownloaded)

    /*
     * Manually reach into the database and create a book in order to have something to delete.
     */

    run {
      val databaseEntry = account.bookDatabase.entry(bookId)

      //      databaseEntry.writeEPUB(File.createTempFile("book", ".epub"));
      //      this.bookRegistry.update(
      //          BookWithStatus.create(
      //              databaseEntry.book(), BookStatus.fromBook(databaseEntry.book())));
    }

    //    final OptionType<File> createdFile =
    //        this.bookRegistry.bookOrException(bookId).book().file();
    //    Assert.assertTrue(
    //        "Book must have a saved EPUB file",
    //        createdFile.isSome());
    //
    //    final File file = ((Some<File>) createdFile).get();
    //    Assert.assertTrue("EPUB must exist", file.isFile());

    this.bookRegistry.bookEvents().subscribe({ this.bookEvents.add(it) })
    controller.bookDelete(account, bookId).get()

    Assert.assertTrue(
      "Book must not have a saved EPUB file",
      this.bookRegistry.book(bookId).isNone)

    // Assert.assertFalse("EPUB must not exist", file.exists());
  }

  /**
   * Dismissing a failed revocation that didn't actually fail does nothing.
   *
   * @throws Exception On errors
   */

  @Test(timeout = 3_000L)
  @Throws(Exception::class)
  fun testBooksRevokeDismissHasNotFailed() {

    val controller =
      createController(
        exec = this.executorBooks,
        feedExecutor = this.executorFeeds,
        http = http,
        books = this.bookRegistry,
        profiles = this.profiles,
        downloader = this.downloader,
        accountProviders = MockAccountProviders.fakeAccountProviders(),
        timerExec = this.executorTimer,
        accountEvents = this.accountEvents,
        profileEvents = this.profileEvents,
        patronUserProfileParsers = this.patronUserProfileParsers)

    val provider = MockAccountProviders.fakeAuthProvider("urn:fake-auth:0")
    val profile = this.profiles.createProfile(provider, "Kermit")
    this.profiles.setProfileCurrent(profile.id)
    val account = profile.accountsByProvider()[provider.id]!!
    account.setLoginState(AccountLoggedIn(correctCredentials()))

    this.http.addResponse(
      "http://www.example.com/accounts0/loans.xml",
      HTTPResultOK(
        "OK",
        200,
        resource("testBooksSyncNewEntries.xml"),
        resourceSize("testBooksSyncNewEntries.xml"),
        HashMap(),
        0L))

    controller.booksSync(account).get()

    val bookId = BookID.create("39434e1c3ea5620fdcc2303c878da54cc421175eb09ce1a6709b54589eb8711f")

    val statusBefore = this.bookRegistry.bookOrException(bookId).status
    Assert.assertThat(statusBefore, IsInstanceOf.instanceOf(BookStatus.Loaned.LoanedNotDownloaded::class.java))

    controller.bookRevokeFailedDismiss(account, bookId).get()

    val statusAfter = this.bookRegistry.bookOrException(bookId).status
    Assert.assertEquals(statusBefore, statusAfter)
  }

  private fun resource(file: String): InputStream {
    return BooksControllerContract::class.java.getResourceAsStream(file)
  }

  @Throws(IOException::class)
  private fun resourceSize(file: String): Long {
    var total = 0L
    val buffer = ByteArray(8192)
    resource(file).use { stream ->
      while (true) {
        val r = stream.read(buffer)
        if (r <= 0) {
          break
        }
        total += r.toLong()
      }
    }
    return total
  }

  @Throws(ProfileDatabaseException::class)
  private fun profilesDatabaseWithoutAnonymous(
    accountEvents: PublishSubject<AccountEvent>,
    dirProfiles: File
  ): ProfilesDatabaseType {
    return ProfilesDatabases.openWithAnonymousProfileDisabled(
      context(),
      this.analytics,
      accountEvents,
      MockAccountProviders.fakeAccountProviders(),
      AccountBundledCredentialsEmpty.getInstance(),
      this.credentialsStore,
      AccountsDatabases,
      dirProfiles)
  }

  private fun onAccountResolution(
    id: URI,
    message: String
  ) {
    this.logger.debug("resolution: {}: {}", id, message)
  }
}
