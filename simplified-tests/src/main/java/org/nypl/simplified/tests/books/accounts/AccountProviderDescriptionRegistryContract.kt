package org.nypl.simplified.tests.books.accounts

import android.content.Context
import org.joda.time.DateTime
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.nypl.simplified.accounts.api.AccountProviderDescription
import org.nypl.simplified.accounts.api.AccountProviderResolutionErrorDetails
import org.nypl.simplified.accounts.api.AccountProviderResolutionListenerType
import org.nypl.simplified.accounts.api.AccountProviderType
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryEvent.SourceFailed
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryEvent.StatusChanged
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryEvent.Updated
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryStatus
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryStatus.Idle
import org.nypl.simplified.accounts.registry.api.AccountProviderRegistryStatus.Refreshing
import org.nypl.simplified.accounts.source.spi.AccountProviderSourceType
import org.nypl.simplified.accounts.source.spi.AccountProviderSourceType.SourceResult
import org.nypl.simplified.taskrecorder.api.TaskRecorder
import org.nypl.simplified.taskrecorder.api.TaskResult
import org.nypl.simplified.tests.MockAccountProviders
import org.slf4j.Logger
import java.net.URI

abstract class AccountProviderDescriptionRegistryContract {

  private lateinit var events: MutableList<org.nypl.simplified.accounts.registry.api.AccountProviderRegistryEvent>

  protected abstract val logger: Logger

  protected abstract val context: Context

  protected abstract fun createRegistry(
    defaultProvider: AccountProviderType,
    sources: List<AccountProviderSourceType>
  ): org.nypl.simplified.accounts.registry.api.AccountProviderRegistryType

  @JvmField
  @Rule
  val expectedException: ExpectedException = ExpectedException.none()

  @Before
  fun testSetup() {
    this.events = mutableListOf()
  }

  /**
   * An empty registry contains nothing.
   */

  @Test
  fun testEmpty() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf())

    registry.events.subscribe { e -> this.events.add(e) }

    val notFound =
      registry.findAccountProviderDescription(
        URI.create("urn:uuid:6ba13d1e-c790-4247-9c80-067c6a7257f0"))

    Assert.assertEquals(Idle, registry.status)
    Assert.assertEquals(null, notFound)
  }

  /**
   * A crashing source raises the right events.
   */

  @Test
  fun testCrashingSource() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(CrashingSource()))

    registry.events.subscribe { e -> this.events.add(e) }
    registry.refresh(true)

    Assert.assertEquals(Idle, registry.status)
    Assert.assertEquals(3, this.events.size)

    run {
      this.events.removeAt(0) as StatusChanged
    }

    run {
      val event = this.events.removeAt(0) as SourceFailed
      Assert.assertEquals(CrashingSource::class.java, event.clazz)
    }

    run {
      this.events.removeAt(0) as StatusChanged
    }
  }

  /**
   * Refreshing with a usable source works.
   */

  @Test
  fun testRefresh() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(OKSource()))

    registry.events.subscribe { this.events.add(it) }
    registry.refresh(true)

    val description0 =
      registry.findAccountProviderDescription(URI.create("urn:0"))
    val description1 =
      registry.findAccountProviderDescription(URI.create("urn:1"))
    val description2 =
      registry.findAccountProviderDescription(URI.create("urn:2"))

    Assert.assertEquals(Idle, registry.status)

    Assert.assertEquals(URI.create("urn:0"), description0!!.id)
    Assert.assertEquals(URI.create("urn:1"), description1!!.id)
    Assert.assertEquals(URI.create("urn:2"), description2!!.id)

    Assert.assertEquals(5, this.events.size)

    run {
      this.events.removeAt(0) as StatusChanged
    }
    run {
      Assert.assertEquals(URI.create("urn:0"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:1"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:2"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      this.events.removeAt(0) as StatusChanged
    }
  }

  /**
   * Refreshing with a source that provides outdated definitions results in the outdated
   * definitions being ignored.
   */

  @Test
  fun testRefreshIgnoreOld() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(OKSource(), OKAncientSource()))

    registry.events.subscribe { this.events.add(it) }
    registry.refresh(true)

    val description0 =
      registry.findAccountProviderDescription(URI.create("urn:0"))
    val description1 =
      registry.findAccountProviderDescription(URI.create("urn:1"))
    val description2 =
      registry.findAccountProviderDescription(URI.create("urn:2"))

    Assert.assertEquals(Idle, registry.status)

    Assert.assertEquals(URI.create("urn:0"), description0!!.id)
    Assert.assertEquals(URI.create("urn:1"), description1!!.id)
    Assert.assertEquals(URI.create("urn:2"), description2!!.id)

    Assert.assertNotEquals(
      DateTime.parse("1900-01-01T00:00:00Z"), description0.updated)
    Assert.assertNotEquals(
      DateTime.parse("1900-01-01T00:00:00Z"), description1.updated)
    Assert.assertNotEquals(
      DateTime.parse("1900-01-01T00:00:00Z"), description2.updated)

    Assert.assertEquals(5, this.events.size)

    run {
      this.events.removeAt(0) as StatusChanged
    }
    run {
      Assert.assertEquals(URI.create("urn:0"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:1"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:2"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      this.events.removeAt(0) as StatusChanged
    }
  }

  /**
   * Even if a source crashes, the working sources are used.
   */

  @Test
  fun testRefreshWithCrashing() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(OKSource(), CrashingSource()))

    registry.events.subscribe { this.events.add(it) }
    registry.refresh(true)

    val description0 =
      registry.findAccountProviderDescription(URI.create("urn:0"))
    val description1 =
      registry.findAccountProviderDescription(URI.create("urn:1"))
    val description2 =
      registry.findAccountProviderDescription(URI.create("urn:2"))

    Assert.assertEquals(Idle, registry.status)

    Assert.assertEquals(URI.create("urn:0"), description0!!.id)
    Assert.assertEquals(URI.create("urn:1"), description1!!.id)
    Assert.assertEquals(URI.create("urn:2"), description2!!.id)

    Assert.assertEquals(6, this.events.size)
    run {
      this.events.removeAt(0) as StatusChanged
    }
    run {
      Assert.assertEquals(URI.create("urn:0"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:1"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:2"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(CrashingSource::class.java, (this.events.removeAt(0) as SourceFailed).clazz)
    }
    run {
      this.events.removeAt(0) as StatusChanged
    }
  }

  /**
   * Even if a source fails, the working sources are used.
   */

  @Test
  fun testRefreshWithFailing() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(OKSource(), FailingSource()))

    registry.events.subscribe { this.events.add(it) }
    registry.refresh(true)

    val description0 =
      registry.findAccountProviderDescription(URI.create("urn:0"))
    val description1 =
      registry.findAccountProviderDescription(URI.create("urn:1"))
    val description2 =
      registry.findAccountProviderDescription(URI.create("urn:2"))

    Assert.assertEquals(Idle, registry.status)

    Assert.assertEquals(URI.create("urn:0"), description0!!.id)
    Assert.assertEquals(URI.create("urn:1"), description1!!.id)
    Assert.assertEquals(URI.create("urn:2"), description2!!.id)

    Assert.assertEquals(6, this.events.size)

    run {
      this.events.removeAt(0) as StatusChanged
    }
    run {
      Assert.assertEquals(URI.create("urn:0"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:1"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(URI.create("urn:2"), (this.events.removeAt(0) as Updated).id)
    }
    run {
      Assert.assertEquals(FailingSource::class.java, (this.events.removeAt(0) as SourceFailed).clazz)
    }
    run {
      this.events.removeAt(0) as StatusChanged
    }
  }

  /**
   * Trying to update with an outdated description returns the newer description.
   */

  @Test
  fun testUpdateIgnoreOld() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(OKSource(), OKAncientSource()))

    registry.events.subscribe { this.events.add(it) }
    registry.refresh(true)

    val existing0 =
      registry.findAccountProviderDescription(URI.create("urn:0"))!!

    val changed =
      registry.updateDescription(existing0)

    Assert.assertEquals(Idle, registry.status)
    Assert.assertEquals(existing0, changed)
    Assert.assertEquals(existing0, registry.accountProviderDescriptions()[existing0.id])
  }

  /**
   * Trying to update with an outdated provider returns the newer provider.
   */

  @Test
  fun testUpdateIgnoreProviderOld() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf())

    registry.events.subscribe { this.events.add(it) }
    registry.refresh(true)

    val existing0 =
      MockAccountProviders.fakeProvider("urn:fake:0")

    val older0 =
      MockAccountProviders.fakeProvider("urn:fake:0")
        .copy(updated = DateTime.parse("1900-01-01T00:00:00Z"))

    val initial =
      registry.updateProvider(existing0)
    val changed =
      registry.updateProvider(older0)

    Assert.assertEquals(Idle, registry.status)
    Assert.assertEquals(existing0, initial)
    Assert.assertEquals(existing0, changed)
    Assert.assertEquals(registry.resolvedProviders[existing0.id], existing0)
  }

  /**
   * Refreshing publishes the correct status.
   */

  @Test
  fun testRefreshStatus() {
    val registry =
      this.createRegistry(
        MockAccountProviders.fakeProvider("urn:fake:0"),
        listOf(OKSource()))

    val eventsWithRefreshing =
      mutableListOf<AccountProviderRegistryStatus>()

    registry.events.subscribe {
      eventsWithRefreshing.add(registry.status)
    }

    registry.refresh(true)

    Assert.assertEquals(5, eventsWithRefreshing.size)
    Assert.assertEquals(Refreshing::class.java, eventsWithRefreshing[0].javaClass)
    Assert.assertEquals(Refreshing::class.java, eventsWithRefreshing[1].javaClass)
    Assert.assertEquals(Refreshing::class.java, eventsWithRefreshing[2].javaClass)
    Assert.assertEquals(Refreshing::class.java, eventsWithRefreshing[3].javaClass)
    Assert.assertEquals(Idle::class.java, eventsWithRefreshing[4].javaClass)

    Assert.assertEquals(Idle, registry.status)
  }

  companion object {

    val description0 =
      AccountProviderDescription(
        id = URI.create("urn:0"),
        title = "Title 0",
        updated = DateTime.now(),
        links = listOf(),
        images = listOf(),
        isAutomatic = false,
        isProduction = true)

    private fun fail(): TaskResult.Failure<AccountProviderResolutionErrorDetails, AccountProviderType> {
      val taskRecorder =
        TaskRecorder.create<AccountProviderResolutionErrorDetails>()
      val exception = Exception()
      taskRecorder.currentStepFailed(
        message = "x",
        errorValue = AccountProviderResolutionErrorDetails.UnexpectedException(
          message = "Unexpected exception",
          exception = exception,
          accountProviderID = "accountProviderID",
          accountProviderTitle = "accountProviderTitle"
        ),
        exception = exception)
      return taskRecorder.finishFailure()
    }

    val description1 =
      AccountProviderDescription(
        id = URI.create("urn:1"),
        title = "Title 1",
        updated = DateTime.now(),
        links = listOf(),
        images = listOf(),
        isAutomatic = false,
        isProduction = true)

    val description2 =
      AccountProviderDescription(
        id = URI.create("urn:2"),
        title = "Title 2",
        updated = DateTime.now(),
        links = listOf(),
        images = listOf(),
        isAutomatic = false,
        isProduction = true)

    val descriptionOld0 =
      AccountProviderDescription(
        id = URI.create("urn:0"),
        title = "Title 0",
        updated = DateTime.parse("1900-01-01T00:00:00Z"),
        links = listOf(),
        images = listOf(),
        isAutomatic = false,
        isProduction = true)

    val descriptionOld1 =
      AccountProviderDescription(
        id = URI.create("urn:1"),
        title = "Title 1",
        updated = DateTime.parse("1900-01-01T00:00:00Z"),
        links = listOf(),
        images = listOf(),
        isAutomatic = false,
        isProduction = true)

    val descriptionOld2 =
      AccountProviderDescription(
        id = URI.create("urn:2"),
        title = "Title 2",
        updated = DateTime.parse("1900-01-01T00:00:00Z"),
        links = listOf(),
        images = listOf(),
        isAutomatic = false,
        isProduction = true)
  }

  class OKAncientSource : AccountProviderSourceType {
    override fun load(context: Context, includeTestingLibraries: Boolean): SourceResult {
      return SourceResult.SourceSucceeded(
        mapOf(
          Pair(descriptionOld0.id, descriptionOld0),
          Pair(descriptionOld1.id, descriptionOld1),
          Pair(descriptionOld2.id, descriptionOld2)))
    }

    override fun clear(context: Context) {}

    override fun canResolve(description: AccountProviderDescription): Boolean {
      return false
    }

    override fun resolve(
      onProgress: AccountProviderResolutionListenerType,
      description: AccountProviderDescription
    ): TaskResult<AccountProviderResolutionErrorDetails, AccountProviderType> {
      throw IllegalStateException()
    }
  }

  class OKSource : AccountProviderSourceType {
    override fun load(context: Context, includeTestingLibraries: Boolean): SourceResult {
      return SourceResult.SourceSucceeded(
        mapOf(
          Pair(description0.id, description0),
          Pair(description1.id, description1),
          Pair(description2.id, description2)))
    }

    override fun clear(context: Context) {}

    override fun canResolve(description: AccountProviderDescription): Boolean {
      return false
    }

    override fun resolve(
      onProgress: AccountProviderResolutionListenerType,
      description: AccountProviderDescription
    ): TaskResult<AccountProviderResolutionErrorDetails, AccountProviderType> {
      throw IllegalStateException()
    }
  }

  class CrashingSource : AccountProviderSourceType {
    override fun load(context: Context, includeTestingLibraries: Boolean): SourceResult {
      throw Exception()
    }

    override fun clear(context: Context) {}

    override fun canResolve(description: AccountProviderDescription): Boolean {
      return false
    }

    override fun resolve(
      onProgress: AccountProviderResolutionListenerType,
      description: AccountProviderDescription
    ): TaskResult<AccountProviderResolutionErrorDetails, AccountProviderType> {
      throw IllegalStateException()
    }
  }

  class FailingSource : AccountProviderSourceType {
    override fun load(context: Context, includeTestingLibraries: Boolean): SourceResult {
      return SourceResult.SourceFailed(mapOf(), java.lang.Exception())
    }

    override fun clear(context: Context) {}

    override fun canResolve(description: AccountProviderDescription): Boolean {
      return false
    }

    override fun resolve(
      onProgress: AccountProviderResolutionListenerType,
      description: AccountProviderDescription
    ): TaskResult<AccountProviderResolutionErrorDetails, AccountProviderType> {
      throw IllegalStateException()
    }
  }
}
