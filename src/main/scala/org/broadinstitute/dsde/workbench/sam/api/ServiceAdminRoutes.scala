package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{NotFound, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import spray.json.DefaultJsonProtocol._

trait ServiceAdminRoutes extends SecurityDirectives with SamRequestContextDirectives with SamUserDirectives with SamModelDirectives {

  val resourceService: ResourceService

  // TODO: This should be added to SamRoutes to better handle our rejection in routes (Unauthorized etc.)
  /*
  private def rejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle { case AuthorizationFailedRejection =>
        complete(Forbidden, s"Email is not a service admin account")
      }
      .handleNotFound {
        complete((NotFound, "Not here!"))
      }
      .result()
   */

  def serviceAdminRoutes(requestContext: SamRequestContext): server.Route =
    pathPrefix("admin") {
      pathPrefix("v2") {
        asAdminServiceUser {
          serviceAdminUserRoutes(requestContext)
        }
      }
    }

  private def serviceAdminUserRoutes(samRequestContext: SamRequestContext): server.Route =
    pathPrefix("users") {
      getWithTelemetry(samRequestContext) {
        parameters("id".optional, "googleSubjectId".optional, "azureB2CId".optional, "limit".as[Int].optional) { (id, googleSubjectId, azureB2CId, limit) =>
          complete {
            userService
              .getUsersByQuery(
                id.map(WorkbenchUserId),
                googleSubjectId.map(GoogleSubjectId),
                azureB2CId.map(AzureB2CId),
                limit,
                samRequestContext
              )
              .map(users => (if (users.nonEmpty) OK else NotFound) -> users)
          }
        }
      } ~
      postWithTelemetry(samRequestContext) {
        entity(as[Seq[WorkbenchUserId]]) {
          case Seq() => complete(OK -> Seq.empty[SamUser])
          case userIds: Seq[WorkbenchUserId] if userIds.length > 1000 =>
            throw new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.BadRequest, "Batch request too large. Batch request too large, must be less than 1000")
            )
          case userIds: Seq[WorkbenchUserId] =>
            complete {
              userService.getUsersByIds(userIds, samRequestContext)
            }
        }
      }
    }
}
