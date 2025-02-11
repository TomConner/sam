package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.model.{TermsOfServiceComplianceStatus, TermsOfServiceDetails, TermsOfServiceHistory, TermsOfServiceHistoryRecord}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.mockito.{ArgumentMatcher, ArgumentMatchers}

import java.time.Instant

case class MockTosServiceBuilder() extends MockitoSugar {
  private val tosService = mock[TosService](RETURNS_SMART_NULLS)

  // Default to nobody having accepted
  setAcceptedStateForAllTo(false)

  def withAllAccepted(): MockTosServiceBuilder = {
    setAcceptedStateForAllTo(true)
    this
  }

  def withNoneAccepted(): MockTosServiceBuilder = {
    setAcceptedStateForAllTo(false)
    this
  }

  def withTermsOfServiceHistoryForUser(samUser: SamUser, tosHistory: TermsOfServiceHistory): MockTosServiceBuilder = {
    lenient()
      .doReturn(IO.pure(tosHistory))
      .when(tosService)
      .getTermsOfServiceHistoryForUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext], any[Integer])
    this
  }

  def withAcceptedStateForUser(samUser: SamUser, isAccepted: Boolean, version: String = "v1"): MockTosServiceBuilder = {
    setAcceptedStateForUserTo(samUser, isAccepted, version)
    this
  }

  private def setAcceptedStateForAllTo(isAccepted: Boolean) = {
    lenient()
      .doAnswer((i: InvocationOnMock) => IO.pure(TermsOfServiceComplianceStatus(i.getArgument[SamUser](0).id, isAccepted, isAccepted)))
      .when(tosService)
      .getTermsOfServiceComplianceStatus(any[SamUser], any[SamRequestContext])

    lenient()
      .doReturn(IO.pure(None))
      .when(tosService)
      .getTermsOfServiceDetailsForUser(any[WorkbenchUserId], any[SamRequestContext])
    lenient()
      .doReturn(IO.pure(TermsOfServiceHistory(List.empty)))
      .when(tosService)
      .getTermsOfServiceHistoryForUser(any[WorkbenchUserId], any[SamRequestContext], any[Integer])
  }

  private def setAcceptedStateForUserTo(samUser: SamUser, isAccepted: Boolean, version: String) = {
    val matchesUser = new ArgumentMatcher[SamUser] {
      override def matches(argument: SamUser): Boolean =
        argument.id.equals(samUser.id)
    }
    lenient()
      .doReturn(IO.pure(TermsOfServiceComplianceStatus(samUser.id, isAccepted, isAccepted)))
      .when(tosService)
      .getTermsOfServiceComplianceStatus(ArgumentMatchers.argThat(matchesUser), any[SamRequestContext])

    val action = if (isAccepted) TosTable.ACCEPT else TosTable.REJECT
    val rightNow = Instant.now
    lenient()
      .doReturn(IO.pure(Option(TermsOfServiceDetails(Option(version), Option(rightNow), permitsSystemUsage = isAccepted, true))))
      .when(tosService)
      .getTermsOfServiceDetailsForUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    lenient()
      .doReturn(IO.pure(TermsOfServiceHistory(List(TermsOfServiceHistoryRecord(action, version, rightNow)))))
      .when(tosService)
      .getTermsOfServiceHistoryForUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext], any[Integer])
  }

  private def initializeDefaults(mockTosService: TosService): Unit = {
    lenient().when(mockTosService.acceptCurrentTermsOfService(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO.pure(true))
    lenient().when(mockTosService.rejectCurrentTermsOfService(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO.pure(true))
  }

  def build: TosService = {
    initializeDefaults(tosService)
    tosService
  }
}
