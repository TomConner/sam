package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, TermsOfServiceConfigResponse}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, TermsOfServiceDetails, TermsOfServiceHistory, TermsOfServiceHistoryRecord}
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class TermsOfServiceRouteSpec extends AnyFunSpec with Matchers with ScalatestRouteTest with TestSupport {

  describe("GET /tos/text") {
    it("return the tos text") {
      assume(databaseEnabled, databaseEnabledClue)

      val samRoutes = TestSamRoutes(Map.empty)
      eventually {
        Get("/tos/text") ~> samRoutes.route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String].isEmpty shouldBe false
        }
      }
    }
  }

  describe("GET /privacy/text") {
    it("return the privacy policy text") {
      val samRoutes = TestSamRoutes(Map.empty)
      eventually {
        Get("/privacy/text") ~> samRoutes.route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String].isEmpty shouldBe false
        }
      }
    }
  }

  describe("GET /termsOfService/v1") {
    it("return the current tos config") {
      val samRoutes = TestSamRoutes(Map.empty)
      eventually {
        Get("/termsOfService/v1") ~> samRoutes.route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[TermsOfServiceConfigResponse] shouldBe TermsOfServiceConfigResponse(
            enforced = true,
            currentVersion = "0",
            inGracePeriod = false,
            inRollingAcceptanceWindow = false
          )
        }
      }
    }
  }

  describe("GET /termsOfService/v1/docs") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return the terms of service text when no query parameters are passed") {
      Get("/termsOfService/v1/docs") ~> samRoutes.route ~> check {
        responseAs[String] shouldBe samRoutes.tosService.termsOfServiceText
        status shouldBe StatusCodes.OK
      }
    }
    it("should return the terms of service text when 'termsOfService' is passed as a query param.") {
      Get(Uri("/termsOfService/v1/docs").withQuery(Uri.Query("doc=termsOfService"))) ~> samRoutes.route ~> check {
        responseAs[String] shouldBe samRoutes.tosService.termsOfServiceText
        status shouldBe StatusCodes.OK
      }
    }
    it("should return the privacy policy text when 'privacyPolicy' is passed as a query param.") {
      Get(Uri("/termsOfService/v1/docs").withQuery(Uri.Query("doc=privacyPolicy"))) ~> samRoutes.route ~> check {
        responseAs[String] shouldBe samRoutes.tosService.privacyPolicyText
        status shouldBe StatusCodes.OK
      }
    }
    it("should return the terms of service text and privacy policy text when 'termsOfService,privacyPolicy' is passed as a query param.") {
      Get(Uri("/termsOfService/v1/docs").withQuery(Uri.Query("doc=termsOfService,privacyPolicy"))) ~> samRoutes.route ~> check {
        responseAs[String] shouldBe s"${samRoutes.tosService.termsOfServiceText}\n\n${samRoutes.tosService.privacyPolicyText}"
        status shouldBe StatusCodes.OK
      }
    }
  }

  describe("GET /termsOfService/v1/docs/redirect") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should be a valid route") {
      Get("/termsOfService/v1/docs/redirect") ~> samRoutes.route ~> check {
        status shouldBe StatusCodes.NotImplemented
      }
    }
  }

  describe("GET /api/termsOfService/v1/user") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should not be handled") {
      Get("/api/termsOfService/v1/user") ~> samRoutes.route ~> check {
        assert(!handled, "`GET /api/termsOfService/v1/user` should not be a handled route")
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/self") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return an instance of `TermsOfServiceDetails`") {
      Get("/api/termsOfService/v1/user/self") ~> samRoutes.route ~> check {
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceDetails`.") {
          responseAs[TermsOfServiceDetails]
        }
        status shouldEqual StatusCodes.OK
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/{USER_ID}") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return an instance of `TermsOfServiceDetails`") {
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(defaultUser)
        .withAllowedUser(defaultUser)
        .withTosStateForUser(defaultUser, isAccepted = true, "0")

      Get(s"/api/termsOfService/v1/user/${defaultUser.id}") ~> mockSamRoutesBuilder.build.route ~> check {
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceDetails`.") {
          responseAs[TermsOfServiceDetails]
        }
        status shouldEqual StatusCodes.OK
      }
    }

    it("should return 404 when USER_ID is does not exist") {
      val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(defaultUser)
        .withAllowedUser(defaultUser)

      Get("/api/termsOfService/v1/user/12345abc") ~> Route.seal(mockSamRoutesBuilder.build.route) ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    it("should return 400 when called with an invalidly formatted USER_ID") {
      Get("/api/termsOfService/v1/user/bad!_str~ng") ~> Route.seal(samRoutes.route) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/{USER_ID}/history") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return a list of `TermsOfServiceHistoryRecord`") {
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
      val record1 = TermsOfServiceHistoryRecord(TosTable.ACCEPT, "0", Instant.now)
      val record2 = TermsOfServiceHistoryRecord(TosTable.REJECT, "0", Instant.now.minusSeconds(5))
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(defaultUser)
        .withAllowedUser(defaultUser)
        .withTermsOfServiceHistoryForUser(defaultUser, TermsOfServiceHistory(List(record1, record2)))

      Get(s"/api/termsOfService/v1/user/${defaultUser.id}/history") ~> mockSamRoutesBuilder.build.route ~> check {
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceHistoryRecord`.") {
          responseAs[
            String
          ] shouldBe s"""{"history":[{"action":"${record1.action}","timestamp":"${record1.timestamp}","version":"${record1.version}"},{"action":"${record2.action}","timestamp":"${record2.timestamp}","version":"${record2.version}"}]}"""
          responseAs[TermsOfServiceHistory] shouldBe TermsOfServiceHistory(List(record1, record2))
        }
        status shouldEqual StatusCodes.OK
      }
    }

    it("should return 200 with empty list when user has no acceptance history") {
      val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(defaultUser)
        .withAllowedUser(defaultUser)

      Get("/api/termsOfService/v1/user/12345abc/history") ~> Route.seal(mockSamRoutesBuilder.build.route) ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[TermsOfServiceHistory].history shouldBe empty
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/self/history") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return a list of `TermsOfServiceHistoryRecord`") {
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
      val record1 = TermsOfServiceHistoryRecord(TosTable.ACCEPT, "0", Instant.now)
      val record2 = TermsOfServiceHistoryRecord(TosTable.REJECT, "0", Instant.now.minusSeconds(5))
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(defaultUser)
        .withTermsOfServiceHistoryForUser(defaultUser, TermsOfServiceHistory(List(record1, record2)))

      Get(s"/api/termsOfService/v1/user/self/history") ~> mockSamRoutesBuilder.build.route ~> check {
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceHistoryRecord`.") {
          responseAs[
            String
          ] shouldBe s"""{"history":[{"action":"${record1.action}","timestamp":"${record1.timestamp}","version":"${record1.version}"},{"action":"${record2.action}","timestamp":"${record2.timestamp}","version":"${record2.version}"}]}"""
          responseAs[TermsOfServiceHistory] shouldBe TermsOfServiceHistory(List(record1, record2))
        }
        status shouldEqual StatusCodes.OK
      }
    }

    it("should return 404 when user has no acceptance history") {
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withDisabledUser(Generator.genWorkbenchUserGoogle.sample.get)

      Get("/api/termsOfService/v1/user/self/history") ~> Route.seal(mockSamRoutesBuilder.build.route) ~> check {
        status shouldEqual StatusCodes.OK
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceHistory`.") {
          val response = responseAs[TermsOfServiceHistory]
          response.history shouldBe empty
        }
      }
    }
  }

  it("should return 204 when tos accepted") {
    val samRoutes = TestSamRoutes(Map.empty)
    eventually {
      Put("/api/termsOfService/v1/user/self/accept") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
        responseAs[String] shouldBe ""
      }
    }
  }

  it("should return 204 when tos rejected") {
    val samRoutes = TestSamRoutes(Map.empty)
    eventually {
      Put("/api/termsOfService/v1/user/self/reject") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
        responseAs[String] shouldBe ""
      }
    }
  }
}
