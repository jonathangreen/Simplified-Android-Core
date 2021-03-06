package org.nypl.simplified.books.controller

import io.reactivex.subjects.Subject
import org.nypl.simplified.accounts.api.AccountDeleteErrorDetails
import org.nypl.simplified.accounts.api.AccountDeleteErrorDetails.AccountCannotDeleteLastAccount
import org.nypl.simplified.accounts.api.AccountDeleteErrorDetails.AccountUnexpectedException
import org.nypl.simplified.accounts.api.AccountEvent
import org.nypl.simplified.accounts.api.AccountEventDeletion
import org.nypl.simplified.accounts.api.AccountID
import org.nypl.simplified.accounts.database.api.AccountsDatabaseLastAccountException
import org.nypl.simplified.profiles.api.ProfileEvent
import org.nypl.simplified.profiles.api.ProfilesDatabaseType
import org.nypl.simplified.profiles.controller.api.ProfileAccountDeletionStringResourcesType
import org.nypl.simplified.taskrecorder.api.TaskRecorder
import org.nypl.simplified.taskrecorder.api.TaskResult
import org.nypl.simplified.taskrecorder.api.TaskStep
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.Callable

class ProfileAccountDeleteTask(
  private val accountEvents: Subject<AccountEvent>,
  private val accountProviderID: URI,
  private val profiles: ProfilesDatabaseType,
  private val profileEvents: Subject<ProfileEvent>,
  private val strings: ProfileAccountDeletionStringResourcesType
) : Callable<TaskResult<AccountDeleteErrorDetails, Unit>> {

  private val logger = LoggerFactory.getLogger(ProfileAccountDeleteTask::class.java)
  private val taskRecorder = TaskRecorder.create<AccountDeleteErrorDetails>()

  private fun publishFailureEvent(step: TaskStep<AccountDeleteErrorDetails>) =
    this.accountEvents.onNext(AccountEventDeletion.AccountEventDeletionFailed(
      step.resolution.message, this.taskRecorder.finishFailure<Unit>()))

  private fun publishProgressEvent(step: TaskStep<AccountDeleteErrorDetails>) =
    this.accountEvents.onNext(AccountEventDeletion.AccountEventDeletionInProgress(step.description))

  private fun publishSuccessEvent(accountThen: AccountID) =
    this.accountEvents.onNext(AccountEventDeletion.AccountEventDeletionSucceeded(
      this.strings.deletionSucceeded, accountThen))

  override fun call(): TaskResult<AccountDeleteErrorDetails, Unit> {
    return try {
      this.logger.debug("deleting account for provider {}", this.accountProviderID)
      this.publishProgressEvent(this.taskRecorder.beginNewStep(this.strings.deletingAccount))

      val profile = this.profiles.currentProfileUnsafe()
      val account = profile.deleteAccountByProvider(this.accountProviderID)

      this.publishSuccessEvent(account)
      this.taskRecorder.finishSuccess(Unit)
    } catch (e: AccountsDatabaseLastAccountException) {
      this.publishFailureEvent(
        this.taskRecorder.currentStepFailed(
          this.strings.onlyOneAccountRemaining,
          AccountCannotDeleteLastAccount(this.strings.onlyOneAccountRemaining),
          e))
      this.taskRecorder.finishFailure()
    } catch (e: Throwable) {
      this.publishFailureEvent(
        this.taskRecorder.currentStepFailed(
          this.strings.unexpectedException,
          AccountUnexpectedException(this.strings.unexpectedException, e),
          e))
      this.taskRecorder.finishFailure()
    }
  }
}
