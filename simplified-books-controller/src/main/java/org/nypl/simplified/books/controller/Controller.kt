package org.nypl.simplified.books.controller

import android.content.ContentResolver
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FluentFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.io7m.jfunctional.Some
import com.io7m.junreachable.UnreachableCodeException
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.Subject
import org.librarysimplified.services.api.ServiceDirectoryType
import org.nypl.drm.core.AdobeAdeptExecutorType
import org.nypl.simplified.accounts.api.AccountCreateErrorDetails
import org.nypl.simplified.accounts.api.AccountDeleteErrorDetails
import org.nypl.simplified.accounts.api.AccountEvent
import org.nypl.simplified.accounts.api.AccountID
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLogoutErrorData
import org.nypl.simplified.accounts.api.AccountLoginStringResourcesType
import org.nypl.simplified.accounts.api.AccountLogoutStringResourcesType
import org.nypl.simplified.accounts.api.AccountProviderResolutionStringsType
import org.nypl.simplified.accounts.api.AccountProviderType
import org.nypl.simplified.accounts.database.api.AccountType
import org.nypl.simplified.accounts.database.api.AccountsDatabaseNonexistentException
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryEvent
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryType
import org.nypl.simplified.analytics.api.AnalyticsType
import org.nypl.simplified.books.api.BookID
import org.nypl.simplified.books.book_registry.BookRegistryType
import org.nypl.simplified.books.book_registry.BookStatus
import org.nypl.simplified.books.book_registry.BookStatusDownloadErrorDetails
import org.nypl.simplified.books.book_registry.BookStatusRevokeErrorDetails
import org.nypl.simplified.books.book_registry.BookWithStatus
import org.nypl.simplified.books.controller.api.BookRevokeStringResourcesType
import org.nypl.simplified.books.controller.api.BooksControllerType
import org.nypl.simplified.downloader.core.DownloadType
import org.nypl.simplified.feeds.api.Feed
import org.nypl.simplified.feeds.api.FeedEntry
import org.nypl.simplified.feeds.api.FeedLoaderType
import org.nypl.simplified.futures.FluentFutureExtensions
import org.nypl.simplified.futures.FluentFutureExtensions.flatMap
import org.nypl.simplified.futures.FluentFutureExtensions.map
import org.nypl.simplified.http.core.HTTPType
import org.nypl.simplified.opds.auth_document.api.AuthenticationDocumentParsersType
import org.nypl.simplified.opds.core.OPDSAcquisition
import org.nypl.simplified.opds.core.OPDSAcquisitionFeedEntry
import org.nypl.simplified.opds.core.OPDSFeedParserType
import org.nypl.simplified.patron.api.PatronUserProfileParsersType
import org.nypl.simplified.profiles.api.ProfileCreationEvent
import org.nypl.simplified.profiles.api.ProfileDeletionEvent
import org.nypl.simplified.profiles.api.ProfileDescription
import org.nypl.simplified.profiles.api.ProfileEvent
import org.nypl.simplified.profiles.api.ProfileID
import org.nypl.simplified.profiles.api.ProfileNoneCurrentException
import org.nypl.simplified.profiles.api.ProfileNonexistentAccountProviderException
import org.nypl.simplified.profiles.api.ProfileReadableType
import org.nypl.simplified.profiles.api.ProfileSelection
import org.nypl.simplified.profiles.api.ProfileType
import org.nypl.simplified.profiles.api.ProfileUpdated
import org.nypl.simplified.profiles.api.ProfilesDatabaseType
import org.nypl.simplified.profiles.api.ProfilesDatabaseType.AnonymousProfileEnabled
import org.nypl.simplified.profiles.api.ProfilesDatabaseType.AnonymousProfileEnabled.ANONYMOUS_PROFILE_ENABLED
import org.nypl.simplified.profiles.api.idle_timer.ProfileIdleTimerType
import org.nypl.simplified.profiles.controller.api.ProfileAccountCreationStringResourcesType
import org.nypl.simplified.profiles.controller.api.ProfileAccountDeletionStringResourcesType
import org.nypl.simplified.profiles.controller.api.ProfileAccountLoginRequest
import org.nypl.simplified.profiles.controller.api.ProfileFeedRequest
import org.nypl.simplified.profiles.controller.api.ProfilesControllerType
import org.nypl.simplified.taskrecorder.api.TaskResult
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.SortedMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * The default controller implementation.
 */

class Controller private constructor(
  private val cacheDirectory: File,
  private val contentResolver: ContentResolver,
  private val accountEvents: Subject<AccountEvent>,
  private val profileEvents: Subject<ProfileEvent>,
  private val services: ServiceDirectoryType,
  private val taskExecutor: ListeningExecutorService
) : BooksControllerType, ProfilesControllerType {

  private val accountLoginStringResources =
    this.services.requireService(AccountLoginStringResourcesType::class.java)
  private val accountLogoutStringResources =
    this.services.requireService(AccountLogoutStringResourcesType::class.java)
  private val accountProviderResolutionStrings =
    this.services.requireService(AccountProviderResolutionStringsType::class.java)
  private val accountProviders =
    this.services.requireService(AccountProviderRegistryType::class.java)
  private val adobeDrm =
    this.services.optionalService(AdobeAdeptExecutorType::class.java)
  private val analytics =
    this.services.requireService(AnalyticsType::class.java)
  private val authDocumentParsers =
    this.services.requireService(AuthenticationDocumentParsersType::class.java)
  private val bookRegistry =
    this.services.requireService(BookRegistryType::class.java)
  private val feedLoader =
    this.services.requireService(FeedLoaderType::class.java)
  private val feedParser =
    this.services.requireService(OPDSFeedParserType::class.java)
  private val http =
    this.services.requireService(HTTPType::class.java)
  private val patronUserProfileParsers =
    this.services.requireService(PatronUserProfileParsersType::class.java)
  private val profileAccountCreationStringResources =
    this.services.requireService(ProfileAccountCreationStringResourcesType::class.java)
  private val profileAccountDeletionStringResources =
    this.services.requireService(ProfileAccountDeletionStringResourcesType::class.java)
  private val profiles =
    this.services.requireService(ProfilesDatabaseType::class.java)
  private val revokeStrings =
    this.services.requireService(BookRevokeStringResourcesType::class.java)
  private val profileIdleTimer =
    this.services.requireService(ProfileIdleTimerType::class.java)

  private val bookTaskRequiredServices =
    BookTaskRequiredServices.createFromServices(this.contentResolver, this.services)

  private val profileSelectionSubscription: Disposable
  private val accountRegistrySubscription: Disposable
  private val downloads: ConcurrentHashMap<BookID, DownloadType> =
    ConcurrentHashMap(32)

  private val logger =
    LoggerFactory.getLogger(Controller::class.java)

  init {
    this.accountRegistrySubscription =
      this.accountProviders.events.subscribe { event -> this.onAccountRegistryEvent(event) }
    this.profileSelectionSubscription =
      this.profileEvents.ofType(ProfileSelection.ProfileSelectionCompleted::class.java)
        .subscribe { event -> this.onProfileSelectionCompleted(event) }

    /*
     * If the anonymous profile is enabled, then ensure that it is "selected" and will
     * therefore very shortly have all of its books loaded.
     */

    if (this.profiles.anonymousProfileEnabled() == ANONYMOUS_PROFILE_ENABLED) {
      this.logger.debug("initializing anonymous profile")
      this.profileSelect(this.profileCurrent().id)
    }
  }

  private fun onProfileSelectionCompleted(
    event: ProfileSelection.ProfileSelectionCompleted
  ) {
    if (!this.profileAnyIsCurrent()) {
      return
    }

    /*
     * Attempt to sync books if a profile is selected.
     */

    try {
      this.logger.debug("triggering syncing of all accounts in profile")
      this.profiles.currentProfileUnsafe()
        .accounts()
        .values
        .forEach { this.booksSync(it) }
    } catch (e: Exception) {
      this.logger.error("failed to trigger book syncing: ", e)
    }
  }

  /**
   * Respond to account registry events.
   */

  private fun onAccountRegistryEvent(event: AccountProviderRegistryEvent) {
    if (!this.profileAnyIsCurrent()) {
      return
    }

    return when (event) {
      is AccountProviderRegistryEvent.Updated ->
        this.onAccountRegistryProviderUpdatedEvent(event)
      is AccountProviderRegistryEvent.SourceFailed,
      AccountProviderRegistryEvent.StatusChanged -> {
      }
    }
  }

  private fun onAccountRegistryProviderUpdatedEvent(event: AccountProviderRegistryEvent.Updated) {
    val profileCurrentOpt = this.profiles.currentProfile()
    if (profileCurrentOpt is Some<ProfileType>) {
      val profileCurrent = profileCurrentOpt.get()
      this.submitTask {
        ProfileAccountProviderUpdatedTask(
          profile = profileCurrent,
          accountProviderID = event.id,
          accountProviders = this.accountProviders)
      }
    } else {
      this.logger.debug("no profile is current")
    }
  }

  private fun <A> submitTask(task: () -> A): FluentFuture<A> {
    val future = SettableFuture.create<A>()
    this.taskExecutor.execute {
      try {
        future.set(task.invoke())
      } catch (e: Throwable) {
        this.logger.error("exception raised during task execution: ", e)
        future.setException(e)
        throw e
      }
    }
    return FluentFuture.from(future)
  }

  private fun <A> submitTask(task: Callable<A>): FluentFuture<A> {
    val future = SettableFuture.create<A>()
    this.taskExecutor.execute {
      try {
        future.set(task.call())
      } catch (e: Throwable) {
        this.logger.error("exception raised during task execution: ", e)
        future.setException(e)
        throw e
      }
    }
    return FluentFuture.from(future)
  }

  override fun profiles(): SortedMap<ProfileID, ProfileReadableType> {
    return this.castMap(this.profiles.profiles())
  }

  override fun profileAnonymousEnabled(): AnonymousProfileEnabled {
    return this.profiles.anonymousProfileEnabled()
  }

  @Throws(ProfileNoneCurrentException::class)
  override fun profileCurrent(): ProfileReadableType {
    return this.profiles.currentProfileUnsafe()
  }

  override fun profileEvents(): Observable<ProfileEvent> {
    return this.profileEvents
  }

  override fun profileDelete(
    profileID: ProfileID
  ): FluentFuture<ProfileDeletionEvent> {
    return this.submitTask(ProfileDeletionTask(
      this.profiles,
      this.profileEvents,
      profileID
    ))
  }

  override fun profileCreate(
    accountProvider: AccountProviderType,
    description: ProfileDescription
  ): FluentFuture<ProfileCreationEvent> {
    return this.submitTask(ProfileCreationTask(
      profiles = this.profiles,
      profileEvents = this.profileEvents,
      accountProvider = accountProvider,
      description = description
    ))
  }

  override fun profileSelect(
    profileID: ProfileID
  ): FluentFuture<Unit> {
    return this.submitTask(ProfileSelectionTask(
      analytics = this.analytics,
      bookRegistry = this.bookRegistry,
      events = this.profileEvents,
      id = profileID,
      profiles = this.profiles
    ))
  }

  override fun profileAccountLogin(
    request: ProfileAccountLoginRequest
  ): FluentFuture<TaskResult<AccountLoginErrorData, Unit>> {
    return this.submitTask { this.runProfileAccountLogin(request) }
      .flatMap { result -> this.runSyncIfLoginSucceeded(result, request.accountId) }
  }

  private fun runProfileAccountLogin(
    request: ProfileAccountLoginRequest
  ): TaskResult<AccountLoginErrorData, Unit> {
    val profile = this.profileCurrent()
    val account = profile.account(request.accountId)
    return ProfileAccountLoginTask(
      adeptExecutor = this.adobeDrm,
      http = this.http,
      profile = profile,
      account = account,
      loginStrings = this.accountLoginStringResources,
      patronParsers = this.patronUserProfileParsers,
      request = request
    ).call()
  }

  private fun runSyncIfLoginSucceeded(
    result: TaskResult<AccountLoginErrorData, Unit>,
    accountID: AccountID
  ): FluentFuture<TaskResult<AccountLoginErrorData, Unit>> {
    return when (result) {
      is TaskResult.Success -> {
        this.logger.debug("logging in succeeded: syncing account")
        val profile = this.profileCurrent()
        val account = profile.account(accountID)
        this.booksSync(account).map { result }
      }
      is TaskResult.Failure -> {
        this.logger.debug("logging in didn't succeed: not syncing account")
        FluentFutureExtensions.fluentFutureOfValue(result)
      }
    }
  }

  override fun profileAccountCreateOrReturnExisting(
    provider: URI
  ): FluentFuture<TaskResult<AccountCreateErrorDetails, AccountType>> {
    return this.submitTask(ProfileAccountCreateOrReturnExistingTask(
      accountEvents = this.accountEvents,
      accountProviderID = provider,
      accountProviders = this.accountProviders,
      profiles = this.profiles,
      strings = this.profileAccountCreationStringResources
    ))
  }

  override fun profileAccountCreateCustomOPDS(
    opdsFeed: URI
  ): FluentFuture<TaskResult<AccountCreateErrorDetails, AccountType>> {
    return this.submitTask(ProfileAccountCreateCustomOPDSTask(
      accountEvents = this.accountEvents,
      accountProviderRegistry = this.accountProviders,
      authDocumentParsers = this.authDocumentParsers,
      http = this.http,
      opdsURI = opdsFeed,
      opdsFeedParser = this.feedParser,
      profiles = this.profiles,
      resolutionStrings = this.accountProviderResolutionStrings,
      strings = this.profileAccountCreationStringResources
    ))
  }

  override fun profileAccountCreate(
    provider: URI
  ): FluentFuture<TaskResult<AccountCreateErrorDetails, AccountType>> {
    return this.submitTask(ProfileAccountCreateTask(
      accountEvents = this.accountEvents,
      accountProviderID = provider,
      accountProviders = this.accountProviders,
      profiles = this.profiles,
      strings = this.profileAccountCreationStringResources
    ))
  }

  override fun profileAccountDeleteByProvider(
    provider: URI
  ): FluentFuture<TaskResult<AccountDeleteErrorDetails, Unit>> {
    return this.submitTask(ProfileAccountDeleteTask(
      accountEvents = this.accountEvents,
      accountProviderID = provider,
      profiles = this.profiles,
      profileEvents = this.profileEvents,
      strings = this.profileAccountDeletionStringResources
    ))
  }

  @Throws(ProfileNoneCurrentException::class, AccountsDatabaseNonexistentException::class)
  override fun profileAccountFindByProvider(provider: URI): AccountType {
    val profile = this.profileCurrent()
    return profile.accountsByProvider()[provider]
      ?: throw AccountsDatabaseNonexistentException("No account with provider: $provider")
  }

  override fun accountEvents(): Observable<AccountEvent> {
    return this.accountEvents
  }

  @Throws(ProfileNoneCurrentException::class, ProfileNonexistentAccountProviderException::class)
  override fun profileCurrentlyUsedAccountProviders(): ImmutableList<AccountProviderType> {
    return ImmutableList.sortedCopyOf(
      this.profileCurrent()
        .accountsByProvider()
        .values
        .map { account -> account.provider })
  }

  override fun profileAccountLogout(
    accountID: AccountID
  ): FluentFuture<TaskResult<AccountLogoutErrorData, Unit>> {
    return this.submitTask {
      val profile = this.profileCurrent()
      val account = profile.account(accountID)
      ProfileAccountLogoutTask(
        adeptExecutor = this.adobeDrm,
        account = account,
        bookRegistry = this.bookRegistry,
        http = this.http,
        logoutStrings = this.accountLogoutStringResources,
        profile = profile
      ).call()
    }
  }

  override fun profileUpdate(
    update: (ProfileDescription) -> ProfileDescription
  ): FluentFuture<ProfileUpdated> {
    return this.submitTask(
      ProfileUpdateTask(
        this.profileEvents,
        requestedProfileId = null,
        profiles = this.profiles,
        update = update
      )
    )
  }

  override fun profileUpdateFor(
    profile: ProfileID,
    update: (ProfileDescription) -> ProfileDescription
  ): FluentFuture<ProfileUpdated> {
    return this.submitTask(
      ProfileUpdateTask(
        this.profileEvents,
        requestedProfileId = profile,
        profiles = this.profiles,
        update = update
      )
    )
  }

  @Throws(ProfileNoneCurrentException::class)
  override fun profileFeed(
    request: ProfileFeedRequest
  ): FluentFuture<Feed.FeedWithoutGroups> {
    return this.submitTask(ProfileFeedTask(
      bookRegistry = this.bookRegistry,
      profiles = this,
      request = request
    ))
  }

  @Throws(ProfileNoneCurrentException::class, AccountsDatabaseNonexistentException::class)
  override fun profileAccountForBook(
    bookID: BookID
  ): AccountType {
    val bookWithStatus = this.bookRegistry.bookOrNull(bookID)
    if (bookWithStatus != null) {
      return this.profileCurrent().account(bookWithStatus.book.account)
    }
    throw UnreachableCodeException()
  }

  override fun profileIdleTimer(): ProfileIdleTimerType {
    return this.profileIdleTimer
  }

  private fun accountForActual(
    accountID: AccountID
  ): AccountType {
    this.logger.debug("account for: {}", accountID.uuid)
    return try {
      val profileCurrent = this.profileCurrent()
      profileCurrent.account(accountID)
    } catch (e: Throwable) {
      this.logger.error("failed to fetch account: ", e)
      throw IOException(e)
    }
  }

  private fun accountFor(
    accountID: AccountID
  ): FluentFuture<AccountType> {
    return this.submitTask {
      return@submitTask this.accountForActual(accountID)
    }
  }

  override fun bookBorrowWithDefaultAcquisition(
    accountID: AccountID,
    bookID: BookID,
    entry: OPDSAcquisitionFeedEntry
  ): FluentFuture<TaskResult<BookStatusDownloadErrorDetails, Unit>> {
    this.publishRequestingDownload(bookID)
    return this.submitTask(BookBorrowWithDefaultAcquisitionTask(
      accountId = accountID,
      bookId = bookID,
      cacheDirectory = this.cacheDirectory,
      downloads = this.downloads,
      entry = entry,
      services = this.bookTaskRequiredServices
    ))
  }

  override fun bookBorrow(
    accountID: AccountID,
    bookID: BookID,
    acquisition: OPDSAcquisition,
    entry: OPDSAcquisitionFeedEntry
  ): FluentFuture<TaskResult<BookStatusDownloadErrorDetails, Unit>> {
    this.publishRequestingDownload(bookID)
    return this.submitTask(BookBorrowTask(
      accountId = accountID,
      acquisition = acquisition,
      bookId = bookID,
      cacheDirectory = this.cacheDirectory,
      downloads = this.downloads,
      entry = entry,
      services = this.bookTaskRequiredServices
    ))
  }

  private fun publishRequestingDownload(bookID: BookID) {
    this.bookRegistry.bookOrNull(bookID)?.let { bookWithStatus ->
      this.bookRegistry.update(BookWithStatus(
        book = bookWithStatus.book,
        status = BookStatus.RequestingDownload(bookID)))
    }
  }

  override fun bookBorrowFailedDismiss(
    account: AccountType,
    bookID: BookID
  ) {
    this.submitTask(BookBorrowFailedDismissTask(
      bookDatabase = account.bookDatabase,
      bookRegistry = this.bookRegistry,
      id = bookID
    ))
  }

  override fun bookBorrowFailedDismiss(
    accountID: AccountID,
    bookID: BookID
  ) {
    this.accountFor(accountID).map { account ->
      this.bookBorrowFailedDismiss(account, bookID)
    }
  }

  private fun downloadCancel(bookID: BookID) {
    this.logger.debug("[{}] download cancel", bookID.brief())
    val existingDownload = this.downloads[bookID]
    if (existingDownload != null) {
      this.logger.debug("[{}] cancelling download {}", existingDownload)
      existingDownload.cancel()
      this.downloads.remove(bookID)
    }
  }

  override fun bookDownloadCancel(
    account: AccountType,
    bookID: BookID
  ) {
    this.downloadCancel(bookID)
  }

  override fun bookDownloadCancel(
    accountID: AccountID,
    bookID: BookID
  ) {
    this.downloadCancel(bookID)
  }

  override fun bookReport(
    account: AccountType,
    feedEntry: FeedEntry.FeedEntryOPDS,
    reportType: String
  ): FluentFuture<Unit> {
    return this.submitTask(BookReportTask(
      http = this.http,
      account = account,
      feedEntry = feedEntry,
      reportType = reportType
    ))
  }

  override fun booksSync(
    account: AccountType
  ): FluentFuture<Unit> {
    return this.submitTask(BookSyncTask(
      account = account,
      accountRegistry = this.accountProviders,
      bookRegistry = this.bookRegistry,
      booksController = this,
      feedParser = this.feedParser,
      http = this.http
    ))
  }

  override fun bookRevoke(
    account: AccountType,
    bookId: BookID
  ): FluentFuture<TaskResult<BookStatusRevokeErrorDetails, Unit>> {
    this.publishRequestingDelete(bookId)
    return this.submitTask(BookRevokeTask(
      account = account,
      adobeDRM = this.adobeDrm,
      bookID = bookId,
      bookRegistry = this.bookRegistry,
      feedLoader = this.feedLoader,
      revokeStrings = this.revokeStrings
    ))
  }

  override fun bookRevoke(
    accountID: AccountID,
    bookId: BookID
  ): FluentFuture<TaskResult<BookStatusRevokeErrorDetails, Unit>> {
    this.publishRequestingDelete(bookId)
    return this.accountFor(accountID).flatMap { account ->
      this.bookRevoke(account, bookId)
    }
  }

  override fun bookDelete(
    account: AccountID,
    bookId: BookID
  ): FluentFuture<Unit> {
    this.publishRequestingDelete(bookId)
    return this.submitTask(BookDeleteTask(
      accountId = account,
      bookRegistry = this.bookRegistry,
      bookId = bookId,
      profiles = this.profiles
    ))
  }

  override fun bookDelete(
    account: AccountType,
    bookId: BookID
  ): FluentFuture<Unit> {
    this.publishRequestingDelete(bookId)
    return this.submitTask(BookDeleteTask(
      accountId = account.id,
      bookRegistry = this.bookRegistry,
      bookId = bookId,
      profiles = this.profiles
    ))
  }

  private fun publishRequestingDelete(bookId: BookID) {
    this.bookRegistry.bookOrNull(bookId)?.let { bookWithStatus ->
      this.bookRegistry.update(BookWithStatus(
        book = bookWithStatus.book,
        status = BookStatus.RequestingRevoke(bookId)))
    }
  }

  override fun bookRevokeFailedDismiss(
    accountID: AccountID,
    bookID: BookID
  ): FluentFuture<Unit> {
    return this.accountFor(accountID).flatMap { account ->
      this.bookRevokeFailedDismiss(account, bookID)
    }
  }

  override fun bookRevokeFailedDismiss(
    account: AccountType,
    bookID: BookID
  ): FluentFuture<Unit> {
    return this.submitTask(BookRevokeFailedDismissTask(
      bookDatabase = account.bookDatabase,
      bookRegistry = this.bookRegistry,
      bookId = bookID
    ))
  }

  override fun profileAnyIsCurrent(): Boolean =
    this.profiles.currentProfile().isSome

  /**
   * Perform an unchecked (but safe) cast of the given map type. The cast is safe because
   * `V <: VB`.
   */

  private fun <K, VB, V : VB> castMap(m: SortedMap<K, V>): SortedMap<K, VB> {
    return m as SortedMap<K, VB>
  }

  companion object {

    fun createFromServiceDirectory(
      services: ServiceDirectoryType,
      executorService: ExecutorService,
      accountEvents: Subject<AccountEvent>,
      profileEvents: Subject<ProfileEvent>,
      cacheDirectory: File,
      contentResolver: ContentResolver
    ): Controller {
      return Controller(
        services = services,
        cacheDirectory = cacheDirectory,
        contentResolver = contentResolver,
        accountEvents = accountEvents,
        profileEvents = profileEvents,
        taskExecutor = MoreExecutors.listeningDecorator(executorService)
      )
    }
  }
}
