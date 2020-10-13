package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directives}
import cats.effect.IO
import cats.implicits._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.ImplicitConversions.ioOnSuccessMagnet
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction}
import org.broadinstitute.dsde.workbench.sam.service.{PolicyEvaluatorService, ResourceService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait SecurityDirectives {
  def policyEvaluatorService: PolicyEvaluatorService
  def resourceService: ResourceService

  def requireAction(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId, samRequestContext: SamRequestContext): Directive0 =
    requireOneOfAction(resource, Set(action), userId, samRequestContext)

  /**
    * see requireOneOfParentAction
    */
  def requireParentAction(resource: FullyQualifiedResourceId, newParent: Option[FullyQualifiedResourceId], parentAction: ResourceAction, userId: WorkbenchUserId, samRequestContext: SamRequestContext): Directive0 =
    requireOneOfParentAction(resource, newParent, Set(parentAction), userId, samRequestContext)

  /**
    * Ensures the user has one of parentActions on the parent of childResource. Passes if no parent exists.
    *
    * @param childResource the child resource
    * @param newParent if this is None this function will check permissions on the current parent of childResource,
    *                  if this is Some this function will check permissions on the specified resource
    * @param parentActions the actions to check for
    * @param userId
    * @param samRequestContext
    * @return
    */
  def requireOneOfParentAction(childResource: FullyQualifiedResourceId, newParent: Option[FullyQualifiedResourceId], parentActions: Set[ResourceAction], userId: WorkbenchUserId, samRequestContext: SamRequestContext): Directive0 =
    Directives.mapInnerRoute { innerRoute =>
      onSuccess(hasParentPermissionOneOf(childResource, newParent, parentActions, userId, samRequestContext)) { hasPermission =>
        if (hasPermission) {
          innerRoute
        } else {
          val parentResourceString = newParent match {
            case None => s"parent of ${childResource.resourceTypeName.value}/${childResource.resourceId.value}"
            case Some(newParentId) => s"${newParentId.resourceTypeName.value}/${newParentId.resourceId.value} or it may not exist"
          }
          val forbiddenErrorMessage = s"You may not perform any of ${parentActions.mkString("[", ", ", "]").toUpperCase} on $parentResourceString"
          determineErrorMessage(childResource, userId, forbiddenErrorMessage, samRequestContext)
        }
      }
    }

  def requireOneOfAction(resource: FullyQualifiedResourceId, requestedActions: Set[ResourceAction], userId: WorkbenchUserId, samRequestContext: SamRequestContext): Directive0 =
    Directives.mapInnerRoute { innerRoute =>
      onSuccess(hasPermissionOneOf(resource, requestedActions, userId, samRequestContext)) { hasPermission =>
        if (hasPermission) {
          innerRoute
        } else {
          val forbiddenErrorMessage = s"You may not perform any of ${requestedActions.mkString("[", ", ", "]").toUpperCase} on ${resource.resourceTypeName.value}/${resource.resourceId.value}"
          determineErrorMessage(resource, userId, forbiddenErrorMessage, samRequestContext)
        }
      }
    }

  /**
    * in the case where we don't have the required action, we need to figure out if we should return
    * a Not Found (you have no access) vs a Forbidden (you have access, just not the right kind)
    */
  private def determineErrorMessage(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, forbiddenErrorMessage: String, samRequestContext: SamRequestContext) = {
    onSuccess(policyEvaluatorService.listUserResourceActions(resource, userId, samRequestContext)) { actions =>
      if (actions.isEmpty) {
        Directives.failWith(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.NotFound, s"Resource ${resource.resourceTypeName.value}/${resource.resourceId.value} not found")))
      } else {
        Directives.failWith(
          new WorkbenchExceptionWithErrorReport(ErrorReport(
            StatusCodes.Forbidden,
            forbiddenErrorMessage
          )))
      }
    }
  }

  private def hasPermissionOneOf(resource: FullyQualifiedResourceId, actions: Iterable[ResourceAction], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    actions.toList.existsM(policyEvaluatorService.hasPermission(resource, _, userId, samRequestContext))

  private def hasParentPermissionOneOf(resource: FullyQualifiedResourceId, newParent: Option[FullyQualifiedResourceId], actions: Iterable[ResourceAction], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] = {
    val parentIO = newParent match {
      case Some(_) => IO.pure(newParent)
      case None => resourceService.getResourceParent(resource, samRequestContext)
    }

    parentIO.flatMap {
      case Some(resourceParent) => hasPermissionOneOf(resourceParent, actions, userId, samRequestContext)
      case None =>
        // there is no parent so permission is granted
        IO.pure(true)
    }
  }
}
