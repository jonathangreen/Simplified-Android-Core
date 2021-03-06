package org.nypl.simplified.tests.books.profiles

import com.io7m.jfunctional.Option
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.nypl.drm.core.AdobeAdeptActivationReceiverType
import org.nypl.drm.core.AdobeAdeptConnectorType
import org.nypl.drm.core.AdobeAdeptExecutorType
import org.nypl.drm.core.AdobeAdeptProcedureType
import org.nypl.drm.core.AdobeDeviceID
import org.nypl.drm.core.AdobeUserID
import org.nypl.drm.core.AdobeVendorID
import org.nypl.simplified.accounts.api.AccountAuthenticationAdobeClientToken
import org.nypl.simplified.accounts.api.AccountAuthenticationAdobePostActivationCredentials
import org.nypl.simplified.accounts.api.AccountAuthenticationAdobePreActivationCredentials
import org.nypl.simplified.accounts.api.AccountAuthenticationCredentials
import org.nypl.simplified.accounts.api.AccountID
import org.nypl.simplified.accounts.api.AccountLoginState
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoggedIn
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoggingInWaitingForExternalAuthentication
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData.AccountLoginConnectionFailure
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData.AccountLoginCredentialsIncorrect
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData.AccountLoginDRMFailure
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData.AccountLoginNotRequired
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData.AccountLoginServerError
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginErrorData.AccountLoginServerParseError
import org.nypl.simplified.accounts.api.AccountLoginState.AccountLoginFailed
import org.nypl.simplified.accounts.api.AccountLoginState.AccountNotLoggedIn
import org.nypl.simplified.accounts.api.AccountLoginStringResourcesType
import org.nypl.simplified.accounts.api.AccountPassword
import org.nypl.simplified.accounts.api.AccountProviderAuthenticationDescription
import org.nypl.simplified.accounts.api.AccountProviderAuthenticationDescription.KeyboardInput
import org.nypl.simplified.accounts.api.AccountProviderType
import org.nypl.simplified.accounts.api.AccountUsername
import org.nypl.simplified.accounts.database.api.AccountType
import org.nypl.simplified.books.controller.ProfileAccountLoginTask
import org.nypl.simplified.http.core.HTTPResultError
import org.nypl.simplified.http.core.HTTPResultException
import org.nypl.simplified.http.core.HTTPResultOK
import org.nypl.simplified.http.core.HTTPResultType
import org.nypl.simplified.parser.api.ParseError
import org.nypl.simplified.parser.api.ParseResult
import org.nypl.simplified.parser.api.ParseWarning
import org.nypl.simplified.patron.api.PatronDRMAdobe
import org.nypl.simplified.patron.api.PatronSettings
import org.nypl.simplified.patron.api.PatronUserProfile
import org.nypl.simplified.patron.api.PatronUserProfileParserType
import org.nypl.simplified.patron.api.PatronUserProfileParsersType
import org.nypl.simplified.profiles.api.ProfileID
import org.nypl.simplified.profiles.api.ProfileReadableType
import org.nypl.simplified.profiles.controller.api.ProfileAccountLoginRequest
import org.nypl.simplified.taskrecorder.api.TaskResult
import org.nypl.simplified.tests.books.controller.TaskDumps
import org.nypl.simplified.tests.http.MockingHTTP
import org.nypl.simplified.tests.strings.MockAccountLoginStringResources
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.UUID

abstract class ProfileAccountLoginTaskContract {

  private lateinit var adeptConnector: AdobeAdeptConnectorType
  private lateinit var adeptExecutor: AdobeAdeptExecutorType
  private lateinit var profileID: ProfileID
  private lateinit var accountID: AccountID
  private lateinit var patronParsers: PatronUserProfileParsersType
  private lateinit var loginStrings: AccountLoginStringResourcesType
  private lateinit var account: AccountType
  private lateinit var profile: ProfileReadableType
  private lateinit var http: MockingHTTP

  private var loginState: AccountLoginState? = null

  abstract val logger: Logger

  @Before
  fun testSetup() {
    this.http = MockingHTTP()
    this.profile =
      Mockito.mock(ProfileReadableType::class.java)
    this.account =
      Mockito.mock(AccountType::class.java)
    this.loginStrings =
      MockAccountLoginStringResources()
    this.patronParsers =
      Mockito.mock(PatronUserProfileParsersType::class.java)
    this.adeptConnector =
      Mockito.mock(AdobeAdeptConnectorType::class.java)
    this.adeptExecutor =
      Mockito.mock(AdobeAdeptExecutorType::class.java)

    this.accountID =
      AccountID(UUID.randomUUID())
    this.profileID =
      ProfileID(UUID.randomUUID())
  }

  @After
  fun testTearDown() {
  }

  /**
   * Logging in to an account that doesn't require logins doesn't work.
   */

  @Test
  fun testLoginNotRequired() {
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = null,
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 8,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Description",
          labels = mapOf(),
          logoURI = null
        ),
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.authentication)
      .thenReturn(null)
    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertTrue(state.taskResult.errors().last() is AccountLoginNotRequired)
  }

  /**
   * If the server responds with a 401, logging in fails.
   */

  @Test
  fun testLoginServer401() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultError(
        401,
        "UNAUTHORIZED",
        0L,
        mutableMapOf(),
        0L,
        ByteArrayInputStream(ByteArray(0)),
        Option.none()
      )
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertTrue(state.taskResult.errors().last() is AccountLoginCredentialsIncorrect)
  }

  /**
   * If the server responds with a non-401 error, logging in fails.
   */

  @Test
  fun testLoginServerNon401() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultError(
        404,
        "NOT FOUND",
        0L,
        mutableMapOf(),
        0L,
        ByteArrayInputStream(ByteArray(0)),
        Option.none()
      )
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertEquals(
      AccountLoginServerError(
        "loginServerError 404 NOT FOUND",
        URI.create("urn:patron"),
        404,
        "NOT FOUND",
        null
      ), state.taskResult.errors().last()
    )
  }

  /**
   * If a connection attempt to the server results in an exception, logging in fails.
   */

  @Test
  fun testLoginServerException() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultException(
        URI("urn:patron"),
        Exception()
      )
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertTrue(state.taskResult.errors().last() is AccountLoginConnectionFailure)
  }

  /**
   * If no patron URI is provided, logging in fails.
   */

  @Test
  fun testLoginNoPatronURI() {
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = null,
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 8,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Description",
          labels = mapOf(),
          logoURI = null
        ),
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(null)

    Mockito.`when`(provider.authentication)
      .thenReturn(
        AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = "CODABAR",
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 10,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Library Login",
          labels = mapOf(),
          logoURI = null
        )
      )

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultException(
        URI("urn:patron"),
        Exception()
      )
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed
  }

  /**
   * If a patron user profile cannot be parsed, logging in fails.
   */

  @Test
  fun testLoginPatronProfileUnparseable() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val parseWarnings =
      listOf(ParseWarning(URI.create("urn:patron"), "Warning", exception = Exception()))
    val parseErrors =
      listOf(ParseError(URI.create("urn:patron"), "Error", exception = Exception()))

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Failure(parseWarnings, parseErrors))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed

    val expected = AccountLoginServerParseError(
      "loginPatronSettingsRequestParseFailed",
      parseWarnings,
      parseErrors
    )
    val received = state.taskResult.errors().last()
    Assert.assertEquals(expected, received)
  }

  /**
   * If a patron user profile can be parsed and it advertises no DRM, then logging in succeeds.
   */

  @Test
  fun testLoginNoDRM() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoggedIn

    Assert.assertEquals(
      AccountAuthenticationCredentials.Basic(
        userName = request.username,
        password = request.password,
        adobeCredentials = null,
        authenticationDescription = "Description"
      ),
      state.credentials
    )
  }

  /**
   * If a patron user profile can be parsed and it advertises Adobe DRM, and Adobe DRM is supported,
   * then logging in succeeds.
   */

  @Test
  fun testLoginAdobeDRM() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = "CODABAR",
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 10,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Library Login",
        labels = mapOf(),
        logoURI = null
      )

    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))

    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(
          PatronDRMAdobe(
            vendor = "OmniConsumerProducts",
            scheme = URI("http://librarysimplified.org/terms/drm/scheme/ACS"),
            clientToken = "NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K",
            deviceManagerURI = URI("https://example.com/devices")
          )
        ),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    this.http.addResponse(
      URI.create("https://example.com/devices"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    /*
     * When the code calls activateDevice(), it succeeds if the connector returns a single
     * activation.
     */

    Mockito.`when`(
      this.adeptConnector.activateDevice(
        anyNonNull(),
        anyNonNull(),
        anyNonNull(),
        anyNonNull()
      )
    ).then { invocation ->
      val receiver = invocation.arguments[0] as AdobeAdeptActivationReceiverType
      receiver.onActivationsCount(1)
      receiver.onActivation(
        0,
        AdobeVendorID("OmniConsumerProducts"),
        AdobeDeviceID("484799fb-d1aa-4b5d-8179-95e0b115ace4"),
        "user",
        AdobeUserID("someone"),
        null
      )
    }

    Mockito.`when`(this.adeptExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adeptConnector)
      }

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = this.adeptExecutor,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoggedIn

    val originalCredentials =
      AccountAuthenticationCredentials.Basic(
        userName = request.username,
        password = request.password,
        adobeCredentials = null,
        authenticationDescription = "Library Login"
      )

    val newCredentials =
      originalCredentials.withAdobePreActivationCredentials(
        AccountAuthenticationAdobePreActivationCredentials(
          vendorID = AdobeVendorID("OmniConsumerProducts"),
          clientToken = AccountAuthenticationAdobeClientToken.parse("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
          deviceManagerURI = URI("https://example.com/devices"),
          postActivationCredentials = AccountAuthenticationAdobePostActivationCredentials(
            deviceID = AdobeDeviceID("484799fb-d1aa-4b5d-8179-95e0b115ace4"),
            userID = AdobeUserID("someone")
          )
        )
      )

    Assert.assertEquals(newCredentials, state.credentials)
  }

  /**
   * If the account shows that DRM is required, but none is supported, then fail.
   */

  @Test
  fun testLoginAdobeDRMNotSupported() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )

    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(
          PatronDRMAdobe(
            vendor = "OmniConsumerProducts",
            scheme = URI("http://librarysimplified.org/terms/drm/scheme/ACS"),
            clientToken = "NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K",
            deviceManagerURI = URI("https://example.com/devices")
          )
        ),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoggedIn

    val originalCredentials =
      AccountAuthenticationCredentials.Basic(
        userName = request.username,
        password = request.password,
        adobeCredentials = null,
        authenticationDescription = "Description"
      )

    val newCredentials =
      originalCredentials.withAdobePreActivationCredentials(
        AccountAuthenticationAdobePreActivationCredentials(
          vendorID = AdobeVendorID("OmniConsumerProducts"),
          clientToken = AccountAuthenticationAdobeClientToken.parse("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
          deviceManagerURI = URI("https://example.com/devices"),
          postActivationCredentials = null
        )
      )

    Assert.assertEquals(newCredentials, state.credentials)
  }

  /**
   * If no activations are delivered by the Adobe DRM connector, then activation fails.
   */

  @Test
  fun testLoginAdobeDRMNoActivations() {
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = null,
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 8,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Description",
          labels = mapOf(),
          logoURI = null
        ),
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))

    Mockito.`when`(provider.authentication)
      .thenReturn(
        AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = "CODABAR",
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 10,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Library Login",
          labels = mapOf(),
          logoURI = null
        )
      )

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(
          PatronDRMAdobe(
            vendor = "OmniConsumerProducts",
            scheme = URI("http://librarysimplified.org/terms/drm/scheme/ACS"),
            clientToken = "NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K",
            deviceManagerURI = URI("https://example.com/devices")
          )
        ),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    /*
     * When the code calls activateDevice(), it succeeds if the connector returns a single
     * activation.
     */

    Mockito.`when`(
      this.adeptConnector.activateDevice(
        anyNonNull(),
        anyNonNull(),
        anyNonNull(),
        anyNonNull()
      )
    ).then { invocation ->
      val receiver =
        invocation.arguments[0] as AdobeAdeptActivationReceiverType
      Unit
    }

    Mockito.`when`(this.adeptExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adeptConnector)
      }

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = this.adeptExecutor,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed
  }

  /**
   * If the Adobe DRM connector delivers an error, then activation fails.
   */

  @Test
  fun testLoginAdobeDRMActivationError() {
    val authDescription =
      AccountProviderAuthenticationDescription.Basic(
        barcodeFormat = null,
        keyboard = KeyboardInput.DEFAULT,
        passwordMaximumLength = 8,
        passwordKeyboard = KeyboardInput.DEFAULT,
        description = "Description",
        labels = mapOf(),
        logoURI = null
      )
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = authDescription,
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))

    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(
          PatronDRMAdobe(
            vendor = "OmniConsumerProducts",
            scheme = URI("http://librarysimplified.org/terms/drm/scheme/ACS"),
            clientToken = "NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K",
            deviceManagerURI = URI("https://example.com/devices")
          )
        ),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    /*
     * When the code calls activateDevice(), it fails if the connector returns an error.
     */

    Mockito.`when`(
      this.adeptConnector.activateDevice(
        anyNonNull(),
        anyNonNull(),
        anyNonNull(),
        anyNonNull()
      )
    ).then { invocation ->
      val receiver = invocation.arguments[0] as AdobeAdeptActivationReceiverType
      receiver.onActivationError("E_FAIL_OFTEN_AND_LOUDLY")
    }

    Mockito.`when`(this.adeptExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adeptConnector)
      }

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = this.adeptExecutor,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertEquals(
      AccountLoginDRMFailure("loginDeviceActivationFailed", "E_FAIL_OFTEN_AND_LOUDLY"),
      state.taskResult.errors().last()
    )
  }

  /**
   * Trying to log in to an account using an unsupported mechanism, fails.
   */

  @Test
  fun testLoginWrongType0() {
    val request =
      ProfileAccountLoginRequest.Basic(
        accountId = this.accountID,
        description = AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = null,
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 8,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Description",
          labels = mapOf(),
          logoURI = null
        ),
        username = AccountUsername("user"),
        password = AccountPassword("password")
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.authentication)
      .thenReturn(
        AccountProviderAuthenticationDescription.OAuthWithIntermediary(
          description = "Description",
          logoURI = null,
          authenticate = URI.create("urn:example")
        )
      )

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertTrue(state.taskResult.errors().last() is AccountLoginNotRequired)
  }

  /**
   * Trying to log in to an account using an unsupported mechanism, fails.
   */

  @Test
  fun testLoginWrongType1() {
    val request =
      ProfileAccountLoginRequest.OAuthWithIntermediaryInitiate(
        accountId = this.accountID,
        description = AccountProviderAuthenticationDescription.OAuthWithIntermediary(
          description = "Description",
          logoURI = null,
          authenticate = URI.create("urn:example")
        )
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.authentication)
      .thenReturn(
        AccountProviderAuthenticationDescription.Basic(
          barcodeFormat = null,
          keyboard = KeyboardInput.DEFAULT,
          passwordMaximumLength = 8,
          passwordKeyboard = KeyboardInput.DEFAULT,
          description = "Description",
          labels = mapOf(),
          logoURI = null
        )
      )

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val task =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request
      )

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    val state =
      this.account.loginState as AccountLoginFailed

    Assert.assertTrue(state.taskResult.errors().last() is AccountLoginNotRequired)
  }

  /**
   * Logging in with OAuth succeeds.
   */

  @Test
  fun testLoginOAuthCompleteNoDRM() {
    val authDescription =
      AccountProviderAuthenticationDescription.OAuthWithIntermediary(
        description = "Description",
        logoURI = null,
        authenticate = URI.create("urn:example")
      )
    val request0 =
      ProfileAccountLoginRequest.OAuthWithIntermediaryInitiate(
        accountId = this.accountID,
        description = authDescription
      )
    val request1 =
      ProfileAccountLoginRequest.OAuthWithIntermediaryComplete(
        accountId = this.accountID,
        token = "A TOKEN!"
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task0 =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request0
      )

    val result0 = task0.call()
    TaskDumps.dump(logger, result0)

    this.account.loginState as AccountLoggingInWaitingForExternalAuthentication

    val task1 =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request1
      )

    val result1 = task1.call()
    TaskDumps.dump(logger, result1)

    val state =
      this.account.loginState as AccountLoggedIn

    Assert.assertEquals(
      AccountAuthenticationCredentials.OAuthWithIntermediary(
        adobeCredentials = null,
        authenticationDescription = "Description",
        accessToken = "A TOKEN!"
      ),
      state.credentials
    )
  }

  /**
   * Receiving an OAuth token in an account that wasn't waiting for one ignores the request.
   */

  @Test
  fun testLoginOAuthNotWaiting() {
    val authDescription =
      AccountProviderAuthenticationDescription.OAuthWithIntermediary(
        description = "Description",
        logoURI = null,
        authenticate = URI.create("urn:example")
      )
    val request0 =
      ProfileAccountLoginRequest.OAuthWithIntermediaryComplete(
        accountId = this.accountID,
        token = "A TOKEN!"
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task0 =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request0
      )

    this.loginState = AccountNotLoggedIn

    val result0 = task0.call() as TaskResult.Success
    TaskDumps.dump(logger, result0)

    this.account.loginState as AccountNotLoggedIn
  }

  /**
   * Cancelling an OAuth request in an account that wasn't waiting for one ignores the request.
   */

  @Test
  fun testLoginOAuthNotWaitingCancel() {
    val authDescription =
      AccountProviderAuthenticationDescription.OAuthWithIntermediary(
        description = "Description",
        logoURI = null,
        authenticate = URI.create("urn:example")
      )
    val request0 =
      ProfileAccountLoginRequest.OAuthWithIntermediaryCancel(
        accountId = this.accountID,
        description = authDescription
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task0 =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request0
      )

    this.loginState = AccountNotLoggedIn

    val result0 = task0.call() as TaskResult.Success
    TaskDumps.dump(logger, result0)

    this.account.loginState as AccountNotLoggedIn
  }

  /**
   * Cancelling OAuth works.
   */

  @Test
  fun testLoginOAuthCancel() {
    val authDescription =
      AccountProviderAuthenticationDescription.OAuthWithIntermediary(
        description = "Description",
        logoURI = null,
        authenticate = URI.create("urn:example")
      )
    val request0 =
      ProfileAccountLoginRequest.OAuthWithIntermediaryInitiate(
        accountId = this.accountID,
        description = authDescription
      )
    val request1 =
      ProfileAccountLoginRequest.OAuthWithIntermediaryCancel(
        accountId = this.accountID,
        description = authDescription
      )

    val provider =
      Mockito.mock(AccountProviderType::class.java)

    Mockito.`when`(provider.patronSettingsURI)
      .thenReturn(URI.create("urn:patron"))
    Mockito.`when`(provider.authentication)
      .thenReturn(authDescription)

    Mockito.`when`(this.profile.id)
      .thenReturn(this.profileID)
    Mockito.`when`(this.profile.accounts())
      .thenReturn(sortedMapOf(Pair(this.accountID, this.account)))
    Mockito.`when`(this.account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(this.account.provider)
      .thenReturn(provider)
    Mockito.`when`(this.account.setLoginState(anyNonNull()))
      .then {
        val newState = it.getArgument<AccountLoginState>(0)
        this.logger.debug("new state: {}", newState)
        this.loginState = newState
        this.loginState
      }
    Mockito.`when`(this.account.loginState)
      .then { this.loginState }

    val parser =
      Mockito.mock(PatronUserProfileParserType::class.java)

    val patronStream =
      Mockito.mock(InputStream::class.java)

    val profile =
      PatronUserProfile(
        settings = PatronSettings(false),
        drm = listOf(),
        authorization = null
      )

    Mockito.`when`(parser.parse())
      .thenReturn(ParseResult.Success(listOf(), profile))
    Mockito.`when`(this.patronParsers.createParser(URI.create("urn:patron"), patronStream, false))
      .thenReturn(parser)

    this.http.addResponse(
      URI.create("urn:patron"),
      HTTPResultOK(
        "OK",
        200,
        patronStream,
        0L,
        mutableMapOf(),
        0L
      ) as HTTPResultType<InputStream>
    )

    val task0 =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request0
      )

    val result0 = task0.call()
    TaskDumps.dump(logger, result0)

    this.account.loginState as AccountLoggingInWaitingForExternalAuthentication

    val task1 =
      ProfileAccountLoginTask(
        adeptExecutor = null,
        http = this.http,
        profile = this.profile,
        account = this.account,
        loginStrings = this.loginStrings,
        patronParsers = this.patronParsers,
        request = request1
      )

    val result1 = task1.call()
    TaskDumps.dump(logger, result1)

    val state =
      this.account.loginState as AccountNotLoggedIn
  }

  private fun <T> anyNonNull(): T =
    Mockito.argThat { x -> x != null }
}
