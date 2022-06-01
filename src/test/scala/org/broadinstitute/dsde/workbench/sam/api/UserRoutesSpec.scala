package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.TestSupport.{genSamDependencies, genSamRoutes, googleServicesConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesSpec extends UserRoutesSpecHelper {
  "POST /register/user" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user" should "get the status of an enabled user" in {
    val (user, _, routes) = createTestUser()

    Get("/register/user") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }
}

trait UserRoutesSpecHelper extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport{
  val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
  val defaultUserId = defaultUser.id
  val defaultUserEmail = defaultUser.email

  val adminUser = Generator.genWorkbenchUserGoogle.sample.get

  val petSAUser = Generator.genWorkbenchUserServiceAccount.sample.get
  val petSAUserId = petSAUser.id
  val petSAEmail = petSAUser.email

  def createTestUser(testUser: SamUser = Generator.genWorkbenchUserBoth.sample.get, cloudExtensions: Option[CloudExtensions] = None, googleDirectoryDAO: Option[GoogleDirectoryDAO] = None, tosEnabled: Boolean = false, tosAccepted: Boolean = false): (SamUser, SamDependencies, SamRoutes) = {
    val samDependencies = genSamDependencies(cloudExtensions = cloudExtensions, googleDirectoryDAO = googleDirectoryDAO, tosEnabled = tosEnabled)
    val routes = genSamRoutes(samDependencies, testUser)

    Post("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe testUser.email
      val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)

      if (tosEnabled) res.enabled shouldBe  enabledBaseArray + ("tosAccepted" -> false) + ("adminEnabled" -> true)
      else res.enabled shouldBe enabledBaseArray
    }

    if (tosEnabled && tosAccepted) {
      Post("/register/user/v1/termsofservice", TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")) ~> routes.route ~> check {
        status shouldEqual StatusCodes.OK
        val res = responseAs[UserStatus]
        res.userInfo.userEmail shouldBe testUser.email
        val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
        res.enabled shouldBe enabledBaseArray + ("tosAccepted" -> true) + ("adminEnabled" -> true)
      }
    }

    (testUser, samDependencies, routes)
  }

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, defaultUser, directoryDAO, registrationDAO,
      newSamUser = Option(defaultUser))

    testCode(samRoutes)
  }

}
